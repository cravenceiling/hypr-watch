'use strict';

/*
 * Hypr Watch UI. Served to the Android WebView and, over an adb-forwarded port, to a PC browser.
 * Native contract: window.setAccessToken(token), global fitEditor(), optional window.AndroidNotes.exportNotes(json),
 * AndroidNotes.backup(passphrase) and AndroidNotes.restore(passphrase) answered by window.onBackupResult(result),
 * AndroidNotes.openBatterySettings(),
 * and the CSS variables --safe-top / --safe-bottom set on <html>.
 */

const TOKEN_KEY = 'hypr-watch-token';
const DONE_OPEN_KEY = 'hypr-watch-done-open';
const WATCH_TITLE_LIMIT = 20;
const POLL_MS = 15000;
const CONFLICT_PATTERN = /changed on another device/i;

const $ = (id) => document.getElementById(id);
const mobileQuery = window.matchMedia('(max-width: 759px)');
const isMobile = () => mobileQuery.matches;
const hasNativeBridge = () => Boolean(window.AndroidNotes);
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const codePoints = (text) => Array.from(text);
const shorten = (text, limit) => codePoints(text).slice(0, limit).join('');

const storage = {
  get(area, key) {
    try {
      return window[area].getItem(key) || '';
    } catch {
      return '';
    }
  },
  set(area, key, value) {
    try {
      window[area].setItem(key, value);
    } catch {
      /* Storage can be unavailable; the app still works for this page view. */
    }
  },
};

const state = {
  token: storage.get('sessionStorage', TOKEN_KEY),
  notes: [],
  renderKey: '',
  status: null,
  loaded: false,
  reachable: true,
  selectedId: null,
  revision: null,
  editorNote: null,
  baseline: '',
  pendingDiscard: null,
  returnFocusId: null,
  busy: { connect: false, sync: false, save: false, retry: false },
  // Counters that let a refresh notice it was overtaken by a newer refresh or by one of this page's writes.
  refreshSeq: 0,
  writeSeq: 0,
};

// The PC helper opens the page with #token=…; keep it out of the visible URL and history.
const fragment = new URLSearchParams(location.hash.slice(1));
if (fragment.has('token')) {
  state.token = fragment.get('token');
  storage.set('sessionStorage', TOKEN_KEY, state.token);
  history.replaceState(null, '', location.pathname);
}

/* ---------- API ---------- */

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

function offlineMessage() {
  return hasNativeBridge()
    ? 'Can’t reach the notes service. Close and reopen Hypr Watch.'
    : 'Can’t reach your phone. Check the USB cable and that Hypr Watch is open.';
}

/** options.timeout (ms) aborts a request that waits on the watch for too long; that is not "offline". */
async function api(path, method = 'GET', body, options = {}) {
  let response;
  const controller = options.timeout ? new AbortController() : null;
  const timer = controller ? setTimeout(() => controller.abort(), options.timeout) : 0;
  try {
    response = await fetch('/api/' + path, {
      method,
      cache: 'no-store',
      headers: {
        Authorization: 'Bearer ' + state.token,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: controller ? controller.signal : undefined,
    });
  } catch {
    if (controller && controller.signal.aborted) throw new ApiError('The watch took too long to answer. Try again.', 504);
    setReachable(false);
    throw new ApiError(offlineMessage(), 0);
  } finally {
    clearTimeout(timer);
  }
  let data = {};
  try {
    data = await response.json();
  } catch {
    /* Non-JSON error bodies fall through to the generic message. */
  }
  setReachable(true);
  if (response.status === 401) {
    showAccess(state.token ? 'That token didn’t work. Run hypr-watch usb --open again for a fresh link.' : '');
    throw new ApiError(data.error || 'Connect with your local access token', 401);
  }
  if (!response.ok) {
    throw new ApiError(data.error || 'Something went wrong. Try again.', response.status);
  }
  if (method !== 'GET') state.writeSeq++;
  return data;
}

function report(error) {
  if (error && error.status === 401) return;
  // The offline banner already explains a lost connection, unless the sheet covers it.
  if (error && error.status === 0 && !document.documentElement.classList.contains('sheet-open')) return;
  notice(error && error.message ? error.message : 'Something went wrong. Try again.');
}

async function attempt(fn) {
  try {
    await fn();
  } catch (error) {
    report(error);
  }
}

/* ---------- Access token ---------- */

window.setAccessToken = async (value) => {
  state.token = value;
  storage.set('sessionStorage', TOKEN_KEY, value);
  hideAccess();
  try {
    await refresh();
  } catch (error) {
    report(error);
  }
};

function showAccess(errorText) {
  const access = $('access');
  const wasHidden = access.hidden;
  access.hidden = false;
  $('list-pane').inert = true;
  $('editor').inert = true;
  setViewsInert(true);
  const error = $('access-error');
  error.textContent = errorText || '';
  error.hidden = !errorText;
  $('access-token').setAttribute('aria-invalid', errorText ? 'true' : 'false');
  if (wasHidden || errorText) $('access-token').focus();
}

function hideAccess() {
  $('access').hidden = true;
  $('list-pane').inert = isMobile() && editor.classList.contains('open');
  $('editor').inert = false;
  setViewsInert(false);
}

function setViewsInert(inert) {
  for (const id of ['health-view', 'settings-view', 'tabs']) $(id).inert = inert;
}

$('access-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const value = $('access-token').value.trim();
  if (!value) {
    showAccess('Paste the token first.');
    return;
  }
  const button = $('access-submit');
  button.disabled = true;
  button.textContent = 'Connecting…';
  await window.setAccessToken(value);
  button.disabled = false;
  button.textContent = 'Connect';
});

/* ---------- Refresh and polling ---------- */

async function refresh() {
  if (!state.token) {
    // In the app, native code supplies the token after load. A PC browser needs the access screen.
    if (!hasNativeBridge()) showAccess('');
    return;
  }
  const seq = ++state.refreshSeq;
  const writes = state.writeSeq;
  const [list, status] = await Promise.all([api('notes'), api('status')]);
  // A poll that started before this page's own save can answer after it. Applying that older list
  // would revert the open note, or report a just-created note as deleted. A later refresh renders instead.
  if (seq !== state.refreshSeq || writes !== state.writeSeq) return;
  state.notes = list.notes;
  state.status = status;
  state.loaded = true;
  // Repaint only when something visible changed; the minute bucket keeps relative times fresh.
  // List chips also depend on the watch connection and no-alert support.
  const key = [JSON.stringify(list.notes), status.watch && status.watch.state, status.silent_notes_supported, Math.floor(Date.now() / 60000)].join('|');
  if (key !== state.renderKey) {
    state.renderKey = key;
    renderNotes();
  }
  renderWatch();
  renderDevice();
  renderHealthSync();
  syncEditorWithServer();
  onFeatureStatus();
}

async function quietRefresh() {
  try {
    await refresh();
  } catch {
    /* Offline and access states render themselves; polling never toasts. */
  }
}

function setReachable(reachable) {
  if (state.reachable === reachable) return;
  state.reachable = reachable;
  $('offline-text').textContent = offlineMessage();
  $('offline').hidden = reachable;
}

$('retry').addEventListener('click', async () => {
  const button = $('retry');
  button.disabled = true;
  button.textContent = 'Trying…';
  await quietRefresh();
  button.disabled = false;
  button.textContent = 'Try again';
});

// While the access screen is up the token is known to be bad; polling would only re-focus its field.
const canPoll = () => state.token && $('access').hidden;

setInterval(() => {
  if (canPoll() && !document.hidden) quietRefresh();
}, POLL_MS);

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && canPoll()) quietRefresh();
  if (document.hidden) pauseNotice();
  else resumeNotice();
  syncLiveHeartRate();
});

/* ---------- Watch state wording ---------- */

function watchConnected() {
  return Boolean(state.status && state.status.watch.state === 'connected');
}

/** Maps a note's watch fields to a short label (list chip) and a sentence (editor). */
function watchInfo(note) {
  const linked = note.watch_id !== null && note.watch_id !== undefined;
  const wanted = note.watch_enabled && !note.done;
  const s = note.watch_state;

  if (s === 'watch_changed') {
    return { tone: 'warn', icon: 'alert', label: 'Changed on watch', detail: 'This reminder was edited on the watch, so sync is paused for it. Remove it on the watch, then turn Send to watch back on.' };
  }
  if (wanted && s === 'watch_full') {
    return { tone: 'warn', icon: 'alert', label: 'Watch full', detail: 'The watch has no free reminder slots. Complete or remove one on the watch to make room.' };
  }
  if (wanted && s === 'needs_watch_title') {
    return { tone: 'warn', icon: 'alert', label: 'Needs watch title', detail: 'Add a watch title so this note can be sent.' };
  }
  if (linked && !wanted) {
    return { tone: 'muted', icon: 'sync', label: 'Leaving watch', detail: 'It leaves the watch on the next sync. You can delete it after that.' };
  }
  if (linked && (s === 'checking_watch_removal' || s === 'awaiting_watch_check')) {
    return { tone: 'muted', icon: 'sync', label: 'Checking watch', detail: 'Checking this reminder on the watch.' };
  }
  if (wanted && !(state.status && state.status.silent_notes_supported)) {
    return { tone: 'muted', icon: 'watch', label: 'Watch sync off', detail: 'Watch sync is off until no-alert mode is verified on this watch.' };
  }
  if (linked) {
    return { tone: 'ok', icon: 'watch', label: 'On watch', detail: 'On your watch. To delete this note, turn off Send to watch and save first.' };
  }
  if (wanted) {
    return watchConnected()
      ? { tone: 'muted', icon: 'sync', label: 'Sending', detail: 'Sending to your watch. This takes a few seconds.' }
      : { tone: 'muted', icon: 'watch', label: 'Waiting for watch', detail: 'Goes to your watch once it connects.' };
  }
  if (s === 'completed_on_watch') {
    return { tone: 'muted', icon: 'check', label: 'Done on watch', detail: 'Completed on the watch. Reopen it to send it back.' };
  }
  if (s === 'removed_on_watch' && !note.watch_enabled) {
    return { tone: 'muted', icon: 'watch', label: 'Removed on watch', detail: 'Removed on the watch. Turn on Send to watch to send it again.' };
  }
  if (note.watch_enabled && note.done) {
    return { tone: 'none', icon: 'check', label: '', detail: 'Done. Reopen it to send it to the watch again.' };
  }
  return null;
}

/* ---------- Note list ---------- */

function svgIcon(name, className = 'icon') {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('class', className);
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(ns, 'use');
  use.setAttribute('href', '#i-' + name);
  svg.append(use);
  return svg;
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const relativeFormat = new Intl.RelativeTimeFormat(navigator.language, { numeric: 'auto', style: 'short' });
const dateFormat = new Intl.DateTimeFormat(navigator.language, { month: 'short', day: 'numeric' });
const longDateFormat = new Intl.DateTimeFormat(navigator.language, { dateStyle: 'medium', timeStyle: 'short' });

function relativeTime(ms) {
  const seconds = Math.round((ms - Date.now()) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 60) return 'just now';
  if (abs < 3600) return relativeFormat.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return relativeFormat.format(Math.round(seconds / 3600), 'hour');
  if (abs < 7 * 86400) return relativeFormat.format(Math.round(seconds / 86400), 'day');
  return dateFormat.format(ms);
}

function noteItem(note) {
  const item = element('li', 'note' + (note.done ? ' is-done' : ''));
  item.dataset.id = note.id;

  const check = element('button', 'check');
  check.type = 'button';
  check.dataset.role = 'check';
  check.setAttribute('aria-label', (note.done ? 'Reopen: ' : 'Mark done: ') + note.title);
  const box = element('span', 'box');
  box.append(svgIcon('check'));
  check.append(box);
  check.addEventListener('click', () => toggleDone(note, item));

  const main = element('div', 'note-main');
  const heading = element('h3', 'note-title');
  const open = element('button', 'note-open', note.title);
  open.type = 'button';
  open.dataset.role = 'open';
  if (note.id === state.selectedId && !isMobile()) open.setAttribute('aria-current', 'true');
  open.addEventListener('click', () => openEditor(note));
  heading.append(open);
  main.append(heading);

  if (note.body.trim()) {
    // Collapse blank lines so the three-line preview shows text, not gaps.
    const preview = note.body.slice(0, 600).replace(/\n\s*\n+/g, '\n').trim();
    main.append(element('p', 'note-body', preview));
  }

  const meta = element('div', 'note-meta');
  const info = watchInfo(note);
  if (info && info.label) {
    const chip = element('span', 'chip ' + info.tone);
    chip.append(svgIcon(info.icon), document.createTextNode(info.label));
    meta.append(chip);
  }
  const time = element('time', '', relativeTime(note.updated_at));
  time.dateTime = new Date(note.updated_at).toISOString();
  time.title = longDateFormat.format(note.updated_at);
  meta.append(time);
  main.append(meta);

  item.append(check, main);
  return item;
}

function focusKey() {
  const active = document.activeElement;
  const item = active && active.closest ? active.closest('.note') : null;
  return item ? { id: item.dataset.id, role: active.dataset.role } : null;
}

function restoreFocus(key) {
  if (!key) return;
  const target = document.querySelector(`.note[data-id="${CSS.escape(key.id)}"] [data-role="${key.role}"]`);
  if (target) target.focus({ preventScroll: true });
}

function renderNotes() {
  const query = $('search').value.trim().toLocaleLowerCase();
  const matches = (note) =>
    !query || [note.title, note.body, note.watch_title].join('\n').toLocaleLowerCase().includes(query);
  const active = state.notes.filter((note) => !note.done);
  const done = state.notes.filter((note) => note.done);
  const activeShown = active.filter(matches);
  const doneShown = done.filter(matches);
  const keep = focusKey();

  $('notes').replaceChildren(...activeShown.map(noteItem));
  $('done-notes').replaceChildren(...doneShown.map(noteItem));
  $('notes').setAttribute('aria-busy', 'false');

  const none = state.notes.length === 0;
  $('empty').hidden = !none;
  $('notes-section').hidden = none;
  $('count').textContent = query ? `${activeShown.length} of ${active.length}` : String(active.length);
  $('done-count').textContent = query ? `${doneShown.length} of ${done.length}` : String(done.length);
  $('all-done').hidden = Boolean(query) || active.length > 0 || done.length === 0;

  const noResults = Boolean(query) && activeShown.length + doneShown.length === 0;
  $('no-results').hidden = !noResults;
  $('no-results').textContent = noResults ? `No notes match “${$('search').value.trim()}”.` : '';
  if (query) $('notes').hidden = activeShown.length === 0;
  else $('notes').hidden = false;

  const doneSection = $('done-section');
  doneSection.hidden = doneShown.length === 0;
  if (query && doneShown.length) doneSection.open = true;
  else if (!query) doneSection.open = storage.get('localStorage', DONE_OPEN_KEY) === '1';

  restoreFocus(keep);
}

$('search').addEventListener('input', renderNotes);

$('done-section').addEventListener('toggle', () => {
  if (!$('search').value.trim()) {
    storage.set('localStorage', DONE_OPEN_KEY, $('done-section').open ? '1' : '0');
  }
});

async function setDone(id, done) {
  const data = await api('notes/' + id, 'PATCH', { done });
  adoptOwnWrite(data.note);
  await refresh();
  return data.note;
}

async function toggleDone(note, item) {
  // A second tap before the list re-renders would send the same change again.
  if (item.dataset.busy) return;
  item.dataset.busy = '1';
  const done = !note.done;
  // Show the result immediately, and let the check land before the list re-sorts.
  item.classList.toggle('is-done', done);
  const settle = delay(done ? 320 : 120);
  try {
    const data = await api('notes/' + note.id, 'PATCH', { done });
    adoptOwnWrite(data.note);
  } catch (error) {
    item.classList.toggle('is-done', !done);
    delete item.dataset.busy;
    report(error);
    return;
  }
  // The change is saved; a failed refresh must not flip the check back.
  await settle;
  await quietRefresh();
  delete item.dataset.busy;
  if (done) {
    notice('Marked done', { action: 'Undo', onAction: () => attempt(() => setDone(note.id, false)) });
  }
}

/** Keep the editor's revision current when this page itself changed the open note. */
function adoptOwnWrite(note) {
  if (note && note.id === state.selectedId) {
    state.revision = note.updated_at;
    state.editorNote = note;
  }
}

/* ---------- Watch card ---------- */

function renderWatch() {
  const status = state.status;
  if (!status) return;
  const watch = status.watch || {};
  const sync = status.sync || {};
  const supported = Boolean(status.silent_notes_supported);
  const connecting = state.busy.connect || watch.state === 'connecting';
  const connected = watch.state === 'connected' && !state.busy.connect;
  const onWatch = state.notes.filter((n) => n.watch_enabled && !n.done && n.watch_id !== null).length;

  const card = $('watch-card');
  card.dataset.state = connecting ? 'connecting' : watch.state || 'unknown';

  let title;
  let detail;
  if (connecting) {
    title = 'Connecting to watch…';
    detail = 'This can take up to 30 seconds.';
  } else if (connected) {
    title = 'Watch connected';
    const parts = [`${onWatch} on watch`];
    if (sync.state === 'syncing') parts.push('syncing…');
    else if (sync.at && sync.state !== 'error' && sync.state !== 'idle') parts.push('checked ' + relativeTime(sync.at));
    detail = parts.join(' · ');
  } else if (watch.state === 'error') {
    title = 'Couldn’t connect to watch';
    const reason = String(watch.error || '').replace(/[.\s]+$/, '');
    // Setup errors (Bluetooth off, not paired, missing key) already say what to do; Mi Fitness isn't the cause.
    const setupError = /^(Turn on|Pair |Watch key|More than one)/.test(reason);
    if (!setupError && (watch.reconnect_needs_key || reason === 'Watch rejected authentication')) {
      detail = 'The watch rejected its key. Pair it again in Mi Fitness to get a new key, then connect.';
    } else {
      detail = (reason ? reason + '.' : '') + (setupError ? '' : (reason ? ' ' : '') + 'Mi Fitness may be holding the connection.');
    }
  } else {
    title = 'Watch not connected';
    detail = supported ? 'Notes still save here and reach the watch once it connects.' : 'Notes still save here.';
  }
  $('watch-status').textContent = title;
  $('watch-detail').textContent = detail;

  const connectButton = $('connect-watch');
  connectButton.hidden = connected;
  connectButton.disabled = connecting;
  connectButton.replaceChildren();
  if (connecting) connectButton.append(element('span', 'spinner'), document.createTextNode('Connecting…'));
  else connectButton.textContent = watch.state === 'error' ? 'Try again' : 'Connect';

  const syncButton = $('sync-watch');
  syncButton.hidden = !connected;
  syncButton.disabled = !supported || state.busy.sync;
  $('disconnect-watch').hidden = !connected;

  // One warning at a time, most actionable first.
  let warning = '';
  const full = state.notes.some((n) => n.watch_enabled && !n.done && n.watch_state === 'watch_full');
  if (!supported) {
    warning = 'Watch sync is off: no-alert mode isn’t verified on this watch. Notes stay on your phone and PC.';
  } else if (sync.state === 'error' && sync.message) {
    warning = `Sync stopped: ${sync.message}`;
  } else if (full) {
    warning = 'Watch is full (20 reminders). Complete or remove one on the watch to make room.';
  }
  $('watch-warning').hidden = !warning;
  $('watch-warning-text').textContent = warning;
  $('sync-info').textContent = supported
    ? 'The watch shows each note’s short title in Reminders, with no alert. Complete it on the watch and it’s marked done here too.'
    : 'Notes are shared between this phone and your PC. Nothing is sent to the watch until no-alert mode is verified.';

  // Editor switch follows support. A fresh, untouched new note defaults to sending.
  const toggle = $('watch-enabled');
  toggle.disabled = !supported;
  $('watch-mode-note').textContent = supported
    ? 'Short title only, with no alert'
    : 'Unavailable: no-alert mode isn’t verified';
  if (!state.selectedId && !isDirty() && toggle.checked !== supported) {
    toggle.checked = supported;
    updateWatchFields();
    state.baseline = snapshot();
  }
}

$('connect-watch').addEventListener('click', async () => {
  state.busy.connect = true;
  renderWatch();
  try {
    const result = await api('watch/connect', 'POST', {});
    if (result.watch.state !== 'connected') {
      throw new ApiError(result.watch.error || 'Couldn’t connect to the watch. Mi Fitness may be holding the connection.', 503);
    }
    notice('Watch connected');
  } catch (error) {
    report(error);
  } finally {
    state.busy.connect = false;
    await quietRefresh();
    renderWatch();
  }
});

$('disconnect-watch').addEventListener('click', () =>
  attempt(async () => {
    await api('watch/disconnect', 'POST', {});
    await refresh();
    notice('Watch disconnected');
  }),
);

$('sync-watch').addEventListener('click', async () => {
  const button = $('sync-watch');
  state.busy.sync = true;
  button.disabled = true;
  button.classList.add('busy');
  try {
    await api('watch/sync', 'POST', {});
    await refresh();
    // The bridge writes one watch change per cycle, and cycles run every 15 seconds.
    notice('Sync started. If several notes changed, the rest follow every 15 seconds.');
  } catch (error) {
    report(error);
  } finally {
    state.busy.sync = false;
    button.classList.remove('busy');
    renderWatch();
  }
});

$('export-notes').addEventListener('click', () =>
  attempt(async () => {
    const data = await api('export');
    const json = JSON.stringify(data, null, 2);
    if (window.AndroidNotes && typeof window.AndroidNotes.exportNotes === 'function') {
      window.AndroidNotes.exportNotes(json);
      return;
    }
    const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'hypr-watch.json';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notice(`Exported ${data.notes.length} ${data.notes.length === 1 ? 'note' : 'notes'}`);
  }),
);

/* ---------- Editor ---------- */

const editor = $('editor');

function snapshot() {
  return JSON.stringify([
    $('title').value,
    $('body').value,
    $('watch-title').value,
    $('watch-enabled').checked,
  ]);
}

function editorActive() {
  return !isMobile() || editor.classList.contains('open');
}

function isDirty() {
  return editorActive() && snapshot() !== state.baseline;
}

function loadIntoEditor(note) {
  state.selectedId = note ? note.id : null;
  state.revision = note ? note.updated_at : null;
  state.editorNote = note || null;
  $('editor-title').textContent = note ? 'Edit note' : 'New note';
  $('title').value = note ? note.title : '';
  $('body').value = note ? note.body : '';
  $('watch-title').value = note ? note.watch_title : '';
  $('watch-enabled').checked = note
    ? note.watch_enabled
    : Boolean(state.status ? state.status.silent_notes_supported : false);
  setFieldError('title', '');
  setFieldError('watch-title', '');
  setSaveStatus('');
  showConflict(false);
  hideDiscard();
  disarmDelete();
  updatePreview();
  updateWatchFields();
  renderEditorNoteState();
  state.baseline = snapshot();
  $('editor-body').scrollTop = 0;
  markSelectedCard();
}

function markSelectedCard() {
  document.querySelectorAll('.note-open[aria-current]').forEach((node) => node.removeAttribute('aria-current'));
  if (!state.selectedId || isMobile()) return;
  const open = document.querySelector(`.note[data-id="${CSS.escape(state.selectedId)}"] .note-open`);
  if (open) open.setAttribute('aria-current', 'true');
}

function renderEditorNoteState() {
  const note = state.editorNote;
  const linked = Boolean(note && note.watch_id !== null);
  $('delete-note').hidden = !note || linked;
  const info = note ? watchInfo(note) : null;
  const line = $('watch-state');
  line.hidden = !info;
  if (info) {
    line.className = 'watch-state ' + info.tone;
    $('watch-state-icon').setAttribute('href', '#i-' + info.icon);
    $('watch-state-text').textContent = info.detail;
  }
}

function updatePreview() {
  const title = $('title').value.trim();
  const short = $('watch-title').value.trim();
  const derived = shorten(title, WATCH_TITLE_LIMIT);
  const preview = $('watch-preview-title');
  preview.textContent = short || derived || 'Short title';
  preview.classList.toggle('placeholder', !short && !derived);
  $('watch-title').placeholder = derived || 'Up to 20 characters';
  const count = codePoints(short).length;
  const counter = $('watch-count');
  counter.textContent = `${count}/${WATCH_TITLE_LIMIT}`;
  counter.classList.toggle('over', count > WATCH_TITLE_LIMIT);
  if (count <= WATCH_TITLE_LIMIT && !$('watch-title-error').hidden) setFieldError('watch-title', '');
}

function updateWatchFields() {
  $('watch-fields').hidden = !$('watch-enabled').checked;
}

function setFieldError(id, message) {
  const error = $(id + '-error');
  error.textContent = message;
  error.hidden = !message;
  $(id).setAttribute('aria-invalid', message ? 'true' : 'false');
  if (id === 'watch-title') $('watch-title-hint').hidden = Boolean(message);
}

let saveStatusTimer;
function setSaveStatus(text, tone = '') {
  clearTimeout(saveStatusTimer);
  const status = $('save-status');
  status.className = 'save-status' + (tone ? ' ' + tone : '');
  status.replaceChildren();
  if (!text) return;
  if (tone === 'ok') status.append(svgIcon('check'));
  status.append(document.createTextNode(text));
  if (tone === 'ok') saveStatusTimer = setTimeout(() => setSaveStatus(''), 4000);
}

function showConflict(show) {
  $('conflict').hidden = !show;
}

$('title').addEventListener('input', () => {
  updatePreview();
  if (!$('title-error').hidden && $('title').value.trim()) setFieldError('title', '');
});
$('watch-title').addEventListener('input', updatePreview);
$('watch-enabled').addEventListener('change', updateWatchFields);

// Enter in the title moves on to the details instead of submitting.
$('title').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.isComposing && !(event.metaKey || event.ctrlKey)) {
    event.preventDefault();
    $('body').focus();
  }
});

/** Applies changes made elsewhere (another device, the watch) to the open note. */
function syncEditorWithServer() {
  if (!state.selectedId) return;
  const latest = state.notes.find((note) => note.id === state.selectedId);
  if (!latest) {
    if (!isDirty()) {
      notice('This note was deleted on another device');
      if (isMobile()) closeEditor();
      else loadIntoEditor(null);
    }
    return;
  }
  const changed = latest.updated_at !== state.revision;
  state.editorNote = latest;
  renderEditorNoteState();
  if (!changed) return;
  if (isDirty()) showConflict(true);
  else loadIntoEditor(latest);
}

function validate() {
  const title = $('title').value.trim();
  const short = $('watch-title').value.trim();
  let first = null;
  if (!title) {
    setFieldError('title', 'Add a title.');
    first = first || $('title');
  }
  if (codePoints(short).length > WATCH_TITLE_LIMIT) {
    // The watch title is saved even when Send to watch is off, so show the field without turning sending on.
    $('watch-fields').hidden = false;
    setFieldError('watch-title', `Use ${WATCH_TITLE_LIMIT} characters or fewer.`);
    first = first || $('watch-title');
  }
  if (first) first.focus();
  return !first;
}

async function save() {
  if (state.busy.save || !validate()) return;
  const title = $('title').value.trim();
  const short = $('watch-title').value.trim() || shorten(title, WATCH_TITLE_LIMIT);
  const editing = Boolean(state.selectedId);
  const payload = {
    title,
    body: $('body').value,
    watch_title: short,
    watch_enabled: $('watch-enabled').checked,
  };
  if (editing) payload.if_updated_at = state.revision;

  const button = $('save-note');
  state.busy.save = true;
  button.disabled = true;
  button.replaceChildren(element('span', 'spinner'), document.createTextNode('Saving…'));
  setSaveStatus('');
  try {
    const data = await api(editing ? 'notes/' + state.selectedId : 'notes', editing ? 'PATCH' : 'POST', payload);
    const note = data.note;
    state.selectedId = note.id;
    state.revision = note.updated_at;
    state.editorNote = note;
    $('watch-title').value = note.watch_title;
    $('editor-title').textContent = 'Edit note';
    showConflict(false);
    updatePreview();
    renderEditorNoteState();
    state.baseline = snapshot();
    if (isMobile()) {
      closeEditor();
      notice(editing ? 'Changes saved' : 'Note saved');
    } else {
      setSaveStatus('Saved', 'ok');
    }
    await quietRefresh();
  } catch (error) {
    if (error.status === 400 && CONFLICT_PATTERN.test(error.message)) {
      showConflict(true);
      $('conflict').scrollIntoView({ block: 'nearest' });
    } else if (error.status !== 401) {
      setSaveStatus('Not saved', 'error');
      report(error);
    }
  } finally {
    state.busy.save = false;
    button.disabled = false;
    button.textContent = 'Save';
  }
}

$('note-form').addEventListener('submit', (event) => {
  event.preventDefault();
  save();
});

$('note-form').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
    event.preventDefault();
    save();
  }
});

$('load-latest').addEventListener('click', () =>
  attempt(async () => {
    const data = await api('notes/' + state.selectedId);
    loadIntoEditor(data.note);
    notice('Loaded the latest version');
  }),
);

$('save-mine').addEventListener('click', () =>
  attempt(async () => {
    const data = await api('notes/' + state.selectedId);
    state.revision = data.note.updated_at;
    state.editorNote = data.note;
    await save();
  }),
);

/* Delete asks twice: the first tap arms the button for a few seconds. */
let deleteTimer;
function disarmDelete() {
  clearTimeout(deleteTimer);
  const button = $('delete-note');
  button.classList.remove('armed');
  button.textContent = 'Delete';
}

$('delete-note').addEventListener('click', async () => {
  const button = $('delete-note');
  if (!state.selectedId) return;
  if (!button.classList.contains('armed')) {
    button.classList.add('armed');
    button.textContent = 'Tap again to delete';
    deleteTimer = setTimeout(disarmDelete, 4000);
    return;
  }
  disarmDelete();
  button.disabled = true;
  try {
    await api('notes/' + state.selectedId, 'DELETE');
    state.baseline = snapshot();
    if (isMobile()) closeEditor();
    else loadIntoEditor(null);
    notice('Note deleted');
    await quietRefresh();
  } catch (error) {
    report(error);
  } finally {
    button.disabled = false;
  }
});

/* ---------- Sheet open/close (phones) and unsaved-change guard ---------- */

function askDiscard(then) {
  state.pendingDiscard = then;
  $('discard-bar').hidden = false;
  $('keep-editing').focus();
}

function hideDiscard() {
  state.pendingDiscard = null;
  $('discard-bar').hidden = true;
}

$('keep-editing').addEventListener('click', () => {
  hideDiscard();
  $('title').focus({ preventScroll: true });
});

$('discard').addEventListener('click', () => {
  const then = state.pendingDiscard;
  hideDiscard();
  state.baseline = snapshot();
  if (then) then();
});

function openEditor(note) {
  const proceed = () => {
    state.returnFocusId = note ? note.id : null;
    loadIntoEditor(note);
    if (isMobile()) {
      showSheet();
      // A new note goes straight to typing; an existing one opens to read, without the keyboard.
      if (note) $('editor-title').focus({ preventScroll: true });
      else $('title').focus({ preventScroll: true });
    } else {
      $('title').focus({ preventScroll: true });
    }
  };
  const sameNote = note && note.id === state.selectedId;
  if (!sameNote && isDirty()) askDiscard(proceed);
  else if (!sameNote || isMobile()) proceed();
  else $('title').focus({ preventScroll: true });
}

function showSheet() {
  editor.classList.add('open');
  editor.setAttribute('role', 'dialog');
  editor.setAttribute('aria-modal', 'true');
  document.documentElement.classList.add('sheet-open');
  $('list-pane').inert = true;
  $('tabs').inert = true;
  if (!(history.state && history.state.sheet)) history.pushState({ sheet: true }, '');
  fitEditor();
}

function hideSheet() {
  if (!editor.classList.contains('open')) return;
  editor.classList.remove('open');
  editor.removeAttribute('role');
  editor.removeAttribute('aria-modal');
  document.documentElement.classList.remove('sheet-open');
  $('list-pane').inert = false;
  $('tabs').inert = !$('access').hidden;
  hideDiscard();
  state.selectedId = null;
  state.revision = null;
  state.editorNote = null;
  const id = state.returnFocusId;
  const card = id ? document.querySelector(`.note[data-id="${CSS.escape(id)}"] .note-open`) : null;
  (card || $('new-note')).focus({ preventScroll: true });
  state.returnFocusId = null;
}

function closeEditor() {
  hideSheet();
  if (history.state && history.state.sheet) history.back();
}

function requestClose() {
  if (isDirty()) askDiscard(closeEditor);
  else closeEditor();
}

// Android back (when routed to the WebView) and browser back close the sheet.
window.addEventListener('popstate', () => {
  if (!editor.classList.contains('open')) return;
  if (isDirty()) {
    history.pushState({ sheet: true }, '');
    askDiscard(closeEditor);
    return;
  }
  hideSheet();
});

$('new-note').addEventListener('click', () => openEditor(null));
$('close-editor').addEventListener('click', requestClose);
$('scrim').addEventListener('click', requestClose);

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    if (!$('discard-bar').hidden) {
      event.preventDefault();
      $('keep-editing').click();
    } else if (isMobile() && editor.classList.contains('open')) {
      event.preventDefault();
      requestClose();
    }
    return;
  }
  // "/" jumps to search on a PC keyboard.
  const typing = event.target.closest && event.target.closest('input, textarea, [contenteditable]');
  if (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey && $('access').hidden && view.current === 'notes') {
    event.preventDefault();
    $('search').focus();
  }
});

window.addEventListener('beforeunload', (event) => {
  if (isDirty() && !hasNativeBridge()) event.preventDefault();
});

mobileQuery.addEventListener('change', () => {
  if (!isMobile()) {
    // Leaving phone layout (a phone turned to landscape): the sheet becomes the sticky column.
    editor.classList.remove('open');
    editor.removeAttribute('role');
    editor.removeAttribute('aria-modal');
    document.documentElement.classList.remove('sheet-open');
    $('list-pane').inert = !$('access').hidden;
    $('tabs').inert = !$('access').hidden;
    // Drop the sheet's history entry, or the next Back would do nothing visible.
    if (history.state && history.state.sheet) history.back();
  } else if (state.selectedId || snapshot() !== state.baseline) {
    // Back to phone layout with a note open or typed into: keep it on screen as the sheet, not hidden and unguarded.
    showSheet();
  }
  markSelectedCard();
});

/* ---------- Toast ---------- */

const toast = { timer: null, remaining: 0, startedAt: 0, onAction: null };

function notice(message, options = {}) {
  const box = $('notice');
  const action = $('notice-action');
  box.classList.add('show');
  $('notice-text').textContent = message;
  toast.onAction = options.onAction || null;
  action.hidden = !options.action;
  action.textContent = options.action || '';
  toast.remaining = options.action ? 6000 : 4000;
  resumeNotice();
}

function hideNotice() {
  clearTimeout(toast.timer);
  toast.timer = null;
  toast.remaining = 0;
  $('notice').classList.remove('show');
}

function pauseNotice() {
  if (!toast.timer) return;
  clearTimeout(toast.timer);
  toast.timer = null;
  toast.remaining -= Date.now() - toast.startedAt;
}

function resumeNotice() {
  if (toast.timer || toast.remaining <= 0 || !$('notice').classList.contains('show')) return;
  toast.startedAt = Date.now();
  toast.timer = setTimeout(hideNotice, toast.remaining);
}

$('notice-action').addEventListener('click', () => {
  const run = toast.onAction;
  hideNotice();
  if (run) run();
});
$('notice').addEventListener('pointerenter', pauseNotice);
$('notice').addEventListener('pointerleave', resumeNotice);
$('notice').addEventListener('focusin', pauseNotice);
$('notice').addEventListener('focusout', resumeNotice);

/* ---------- Keyboard-aware sheet height ---------- */

let viewportBaseline = 0;
let viewportWidth = 0;

// Android keyboards can shrink the visual viewport while 100dvh stays unchanged.
function fitEditor() {
  const root = document.documentElement;
  const height = window.visualViewport ? window.visualViewport.height : window.innerHeight;
  const safeTop = parseFloat(getComputedStyle(root).getPropertyValue('--safe-top')) || 0;
  root.style.setProperty('--editor-height', Math.max(0, height - safeTop) + 'px');

  // Track the tallest viewport at this width; a much shorter one means the keyboard is up.
  if (window.innerWidth !== viewportWidth) {
    viewportWidth = window.innerWidth;
    viewportBaseline = height;
  }
  viewportBaseline = Math.max(viewportBaseline, height);
  root.classList.toggle('keyboard-open', viewportBaseline - height > 120);
}

window.addEventListener('resize', fitEditor);
if (window.visualViewport) window.visualViewport.addEventListener('resize', fitEditor);
fitEditor();

/* ---------- Sections: Notes, Health, Settings ---------- */

/*
 * Each section other than Notes is one history entry above the Notes entry, so Back returns to Notes and then
 * leaves the app. Switching between Health and Settings replaces that entry. The URL never changes: native code
 * only injects the token for the page's root URL.
 */
const VIEWS = ['notes', 'health', 'settings'];
const VIEW_PANES = { notes: 'list-pane', health: 'health-view', settings: 'settings-view' };
const view = { current: 'notes', scroll: {} };

function viewFromHistory() {
  const name = history.state && history.state.view;
  return VIEWS.includes(name) ? name : 'notes';
}

function showView(name, options = {}) {
  if (!options.initial) view.scroll[view.current] = window.scrollY;
  view.current = name;
  document.documentElement.dataset.view = name;
  for (const [key, id] of Object.entries(VIEW_PANES)) $(id).hidden = key !== name;
  document.querySelectorAll('.tab').forEach((tab) => {
    if (tab.dataset.view === name) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  });
  window.scrollTo(0, view.scroll[name] || 0);
  hideChartTips();
  scheduleNotifPoll();
  syncLiveHeartRate();
  if (name === 'health') {
    renderHealthSync();
    loadHealth();
  } else if (name === 'settings') {
    loadSettings();
  } else if (isMobile() && !editor.classList.contains('open') && (state.selectedId || snapshot() !== state.baseline)) {
    // A note left open while the layout changed comes back as the sheet rather than hidden and unguarded.
    showSheet();
  }
}

function goToView(name) {
  if (name === view.current) {
    window.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }
  if (name === 'notes') {
    if (history.state && history.state.view) history.back();
    else showView('notes');
    return;
  }
  if (view.current === 'notes') history.pushState({ view: name }, '');
  else history.replaceState({ view: name }, '');
  showView(name);
}

$('tabs').addEventListener('click', (event) => {
  const tab = event.target.closest('.tab');
  if (tab) goToView(tab.dataset.view);
});

// Runs after the sheet's own popstate handler, which closes the sheet first.
window.addEventListener('popstate', () => {
  if (editor.classList.contains('open')) return;
  const name = viewFromHistory();
  if (name !== view.current) showView(name);
});

/* ---------- Formatting ---------- */

const numberFormat = new Intl.NumberFormat(navigator.language);
const decimalFormat = new Intl.NumberFormat(navigator.language, { maximumFractionDigits: 1 });
const compactFormat = new Intl.NumberFormat(navigator.language, { notation: 'compact', maximumFractionDigits: 1 });
const weekdayFormat = new Intl.DateTimeFormat(navigator.language, { weekday: 'short' });
const fullDayFormat = new Intl.DateTimeFormat(navigator.language, { weekday: 'short', month: 'short', day: 'numeric' });
const dayTimeFormat = new Intl.DateTimeFormat(navigator.language, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
// Watch-local clock times: shift by the record's tz_offset, then format as UTC.
const watchTimeFormat = new Intl.DateTimeFormat(navigator.language, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' });
const watchDayTimeFormat = new Intl.DateTimeFormat(navigator.language, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC' });

const isNum = (value) => typeof value === 'number' && Number.isFinite(value);
const pad2 = (n) => String(n).padStart(2, '0');

function isoDate(date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

function parseIsoDate(text) {
  const [y, m, d] = text.split('-').map(Number);
  return new Date(y, m - 1, d, 12);
}

function rangeDates(days) {
  const today = new Date();
  today.setHours(12, 0, 0, 0);
  return Array.from({ length: days }, (_, i) => {
    const day = new Date(today);
    day.setDate(today.getDate() - (days - 1 - i));
    return isoDate(day);
  });
}

function watchLocal(seconds, offset) {
  return new Date((seconds + (offset || 0)) * 1000);
}

function formatMinutes(minutes) {
  if (!isNum(minutes)) return null;
  const total = Math.round(minutes);
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (!h) return `${m}m`;
  return m ? `${h}h ${m}m` : `${h}h`;
}

function shortDay(iso, count) {
  const date = parseIsoDate(iso);
  return count <= 7 ? weekdayFormat.format(date) : dateFormat.format(date);
}

/* ---------- Charts (hand-built SVG) ---------- */

const SVG_NS = 'http://www.w3.org/2000/svg';
const CHART_HEIGHT = 176;
const PAD = { top: 12, right: 6, bottom: 24, left: 40 };
const STACK_GAP = 2;

function svgNode(tag, attrs = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  if (text !== undefined) node.textContent = text;
  return node;
}

/** Ticks from lo to a rounded max, at 1/2/2.5/5 steps. */
function niceTicks(lo, hi, count = 3) {
  const span = Math.max(hi - lo, 1e-9);
  const raw = span / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const norm = raw / mag;
  const step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 2.5 ? 2.5 : norm <= 5 ? 5 : 10) * mag;
  const start = Math.floor(lo / step) * step;
  const ticks = [];
  for (let t = start; t < hi + step - 1e-9; t += step) ticks.push(Math.round(t * 1000) / 1000);
  return ticks;
}

/** Up to five label positions, anchored on the most recent day. */
function labelIndexes(count) {
  if (count <= 7) return Array.from({ length: count }, (_, i) => i);
  const step = Math.ceil(count / 5);
  const out = [];
  for (let i = count - 1; i >= 0; i -= step) out.unshift(i);
  return out;
}

/** A bar with a 4px rounded data end and a square baseline. */
function columnPath(x, y, w, h, rounded) {
  const r = rounded ? Math.min(1.5, w / 2, h) : 0;
  if (!r) return `M${x},${y + h}V${y}H${x + w}V${y + h}Z`;
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

function axisLabel(svg, x, y, text, width) {
  // Keep the first and last labels inside the plot instead of clipping them.
  const approx = text.length * 6;
  let anchor = 'middle';
  if (x - approx / 2 < PAD.left - 4) anchor = 'start';
  if (x + approx / 2 > width) anchor = 'end';
  const ax = anchor === 'start' ? Math.max(x - 4, PAD.left - 4) : anchor === 'end' ? width : x;
  svg.append(svgNode('text', { x: ax, y, 'text-anchor': anchor }, text));
}

function yAxis(svg, ticks, scale, width, format) {
  for (const t of ticks) {
    const y = Math.round(scale(t)) + 0.5;
    svg.append(svgNode('line', { class: t === ticks[0] ? 'baseline' : 'grid', x1: PAD.left, x2: width - PAD.right, y1: y, y2: y }));
    svg.append(svgNode('text', { x: PAD.left - 8, y: y + 4, 'text-anchor': 'end' }, format(t)));
  }
}

// One tooltip at a time across all charts. Keyed by chart container, so a redraw replaces its entry.
const chartTips = new Map();
// A touch that opened a tooltip: where it started and the page scroll at that moment.
const tipTouch = { active: false, x: 0, y: 0, scrollY: 0 };
const TOUCH_SLOP = 10;

function hideChartTips(except) {
  chartTips.forEach((hide, container) => {
    if (container !== except) hide();
  });
  if (!except) tipTouch.active = false;
}

document.addEventListener('pointerdown', (event) => {
  if (!event.target.closest || !event.target.closest('.chart')) hideChartTips();
});

// A touch that started on a chart and turned into a page scroll leaves no tooltip or crosshair behind.
window.addEventListener('scroll', () => {
  if (tipTouch.active && Math.abs(window.scrollY - tipTouch.scrollY) > TOUCH_SLOP) hideChartTips();
}, { passive: true });

/** Shared hover/touch layer: maps the pointer to an index and shows a tooltip above the plot. */
function attachTip(container, svg, width, { indexAt, xOf, content, mark, clear }) {
  const tip = element('div', 'chart-tip');
  tip.setAttribute('aria-hidden', 'true');
  container.append(tip);
  const hide = () => {
    tip.classList.remove('show');
    clear();
  };
  chartTips.set(container, hide);
  const move = (event) => {
    const box = svg.getBoundingClientRect();
    if (!box.width) return;
    const px = ((event.clientX - box.left) / box.width) * width;
    const i = indexAt(px);
    if (i === null) {
      hide();
      return;
    }
    hideChartTips(container);
    tip.replaceChildren(...content(i));
    mark(i);
    tip.classList.add('show');
    const half = tip.offsetWidth / 2;
    const x = Math.min(Math.max(xOf(i), half), width - half);
    tip.style.left = x + 'px';
  };
  svg.addEventListener('pointermove', move);
  svg.addEventListener('pointerdown', (event) => {
    if (event.pointerType === 'touch') Object.assign(tipTouch, { active: true, x: event.clientX, y: event.clientY, scrollY: window.scrollY });
    move(event);
  });
  svg.addEventListener('pointerleave', (event) => {
    if (event.pointerType !== 'touch') hide();
  });
  // The browser took the touch for scrolling.
  svg.addEventListener('pointercancel', () => hideChartTips());
  // A tap keeps its tooltip until the next tap elsewhere; a drag that moved mostly vertically was a scroll attempt.
  svg.addEventListener('pointerup', (event) => {
    if (event.pointerType !== 'touch') return;
    const dy = Math.abs(event.clientY - tipTouch.y);
    if (dy > TOUCH_SLOP && dy > Math.abs(event.clientX - tipTouch.x)) hideChartTips();
  });
}

function tipNodes(title, rows) {
  const nodes = [element('span', '', title)];
  for (const row of rows) {
    if (row.strong) {
      nodes.push(element('strong', '', row.text));
      continue;
    }
    const line = element('span', 'tip-row');
    if (row.swatch) line.append(element('span', 'swatch ' + row.swatch));
    line.append(document.createTextNode(row.text));
    nodes.push(line);
  }
  return nodes;
}

function chartMessage(container, text) {
  container.replaceChildren(element('p', 'chart-empty', text));
}

/**
 * Columns per day. series: [{ cls, values }] stacked bottom to top; a 2px surface gap separates segments.
 * Missing days draw nothing; the tooltip and table say "No data".
 */
function columnChart(container, { dates, series, goal, tickFormat, label, tip }) {
  const width = Math.max(240, Math.round(container.clientWidth));
  const totals = dates.map((_, i) => series.reduce((sum, s) => sum + (isNum(s.values[i]) ? s.values[i] : 0), 0));
  const ticks = niceTicks(0, Math.max(...totals, goal || 0, 1));
  const top = ticks[ticks.length - 1];
  const plotW = width - PAD.left - PAD.right;
  const plotH = CHART_HEIGHT - PAD.top - PAD.bottom;
  const y = (v) => PAD.top + plotH - (v / top) * plotH;
  const band = plotW / dates.length;
  const barW = Math.min(24, Math.max(1, band - STACK_GAP));

  const svg = svgNode('svg', { viewBox: `0 0 ${width} ${CHART_HEIGHT}`, height: CHART_HEIGHT, role: 'img', 'aria-label': label });
  const hover = svgNode('rect', { class: 'hover-band', y: PAD.top, height: plotH, width: band });
  svg.append(hover);
  yAxis(svg, ticks, y, width, tickFormat);

  dates.forEach((_, i) => {
    const x = PAD.left + i * band + (band - barW) / 2;
    const lastSeries = series.reduce((last, s, k) => (isNum(s.values[i]) && s.values[i] > 0 ? k : last), -1);
    let acc = 0;
    series.forEach((s, k) => {
      const v = s.values[i];
      if (!isNum(v) || v <= 0) return;
      const bottom = y(acc) - (acc > 0 ? STACK_GAP : 0);
      acc += v;
      const h = bottom - y(acc);
      if (h < 0.5) return;
      // The newest column is today's (or last night's): drawn in full ink, the rest in a lighter tone.
      svg.append(svgNode('path', { class: s.cls + (i === dates.length - 1 ? ' is-today' : ''), d: columnPath(x, bottom - h, barW, h, k === lastSeries) }));
    });
  });

  if (goal) {
    const gy = Math.round(y(goal)) + 0.5;
    svg.append(svgNode('line', { class: 'goal', x1: PAD.left, x2: width - PAD.right, y1: gy, y2: gy }));
    // The goal takes its place in the y-axis lane, so it never sits on a bar; ticks too close to it give way.
    for (const tick of svg.querySelectorAll('text')) {
      if (Math.abs(Number(tick.getAttribute('y')) - 4 - gy) < 12) tick.remove();
    }
    svg.append(svgNode('text', { class: 'goal-label', x: PAD.left - 8, y: gy + 4, 'text-anchor': 'end' }, compactFormat.format(goal)));
  }

  for (const i of labelIndexes(dates.length)) {
    axisLabel(svg, PAD.left + i * band + band / 2, CHART_HEIGHT - 6, shortDay(dates[i], dates.length), width);
  }

  container.replaceChildren(svg);
  attachTip(container, svg, width, {
    indexAt: (px) => {
      const i = Math.floor((px - PAD.left) / band);
      return i >= 0 && i < dates.length ? i : null;
    },
    xOf: (i) => PAD.left + i * band + band / 2,
    content: tip,
    mark: (i) => {
      hover.setAttribute('x', PAD.left + i * band);
      hover.classList.add('on');
    },
    clear: () => hover.classList.remove('on'),
  });
}

/**
 * One line over time; breaks where the watch recorded nothing for longer than `gap` seconds.
 * Points may carry min/max, drawn as a light band behind the line.
 */
function lineChart(container, { points, from, to, gap, dates, label, tip }) {
  const width = Math.max(240, Math.round(container.clientWidth));
  const values = points.flatMap((p) => [isNum(p.min) ? p.min : p.value, isNum(p.max) ? p.max : p.value]);
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  // Heart rate never sits near zero; start the axis just under the lowest reading.
  const ticks = niceTicks(Math.max(0, Math.floor((lo - 5) / 10) * 10), Math.ceil((hi + 5) / 10) * 10, 5);
  const yMin = ticks[0];
  const yMax = ticks[ticks.length - 1];
  const plotW = width - PAD.left - PAD.right;
  const plotH = CHART_HEIGHT - PAD.top - PAD.bottom;
  const x = (t) => PAD.left + ((t - from) / (to - from)) * plotW;
  const y = (v) => PAD.top + plotH - ((v - yMin) / (yMax - yMin)) * plotH;

  const svg = svgNode('svg', { viewBox: `0 0 ${width} ${CHART_HEIGHT}`, height: CHART_HEIGHT, role: 'img', 'aria-label': label });
  yAxis(svg, ticks, y, width, (t) => numberFormat.format(t));

  const runs = [];
  points.forEach((p, i) => {
    if (i && p.t - points[i - 1].t <= gap) runs[runs.length - 1].push(p);
    else runs.push([p]);
  });
  const xy = (t, v) => `${x(t).toFixed(1)},${y(v).toFixed(1)}`;
  let d = '';
  let band = '';
  for (const run of runs) {
    d += 'M' + run.map((p) => xy(p.t, p.value)).join('L');
    if (run.length > 1 && isNum(run[0].min)) {
      band += 'M' + run.map((p) => xy(p.t, p.max)).join('L') + 'L' + [...run].reverse().map((p) => xy(p.t, p.min)).join('L') + 'Z';
    }
    // A lone reading between gaps has no segment to draw, so it gets a small dot.
    if (run.length === 1) svg.append(svgNode('circle', { class: 'dot', cx: x(run[0].t), cy: y(run[0].value), r: 2.5, 'stroke-width': 0 }));
  }
  if (band) svg.append(svgNode('path', { class: 'band', d: band }));
  svg.append(svgNode('path', { class: 'line', d }));

  const last = points[points.length - 1];
  svg.append(svgNode('circle', { class: 'dot', cx: x(last.t), cy: y(last.value), r: 4 }));

  for (const i of labelIndexes(dates.length)) {
    const day = parseIsoDate(dates[i]);
    const mid = day.getTime() / 1000;
    axisLabel(svg, x(Math.min(Math.max(mid, from), to)), CHART_HEIGHT - 6, shortDay(dates[i], dates.length), width);
  }

  const cross = svgNode('line', { class: 'crosshair', y1: PAD.top, y2: PAD.top + plotH, visibility: 'hidden' });
  const focus = svgNode('circle', { class: 'dot', r: 4, visibility: 'hidden' });
  svg.append(cross, focus);

  container.replaceChildren(svg);
  attachTip(container, svg, width, {
    indexAt: (px) => {
      if (px < PAD.left - 8 || px > width) return null;
      const t = from + ((px - PAD.left) / plotW) * (to - from);
      let loIdx = 0;
      let hiIdx = points.length - 1;
      while (hiIdx - loIdx > 1) {
        const mid = (loIdx + hiIdx) >> 1;
        if (points[mid].t < t) loIdx = mid;
        else hiIdx = mid;
      }
      return Math.abs(points[loIdx].t - t) <= Math.abs(points[hiIdx].t - t) ? loIdx : hiIdx;
    },
    xOf: (i) => x(points[i].t),
    content: tip,
    mark: (i) => {
      const px = Math.round(x(points[i].t)) + 0.5;
      cross.setAttribute('x1', px);
      cross.setAttribute('x2', px);
      focus.setAttribute('cx', x(points[i].t));
      focus.setAttribute('cy', y(points[i].value));
      cross.setAttribute('visibility', 'visible');
      focus.setAttribute('visibility', 'visible');
    },
    clear: () => {
      cross.setAttribute('visibility', 'hidden');
      focus.setAttribute('visibility', 'hidden');
    },
  });
}

function renderTable(container, headers, rows) {
  const table = element('table');
  const head = element('thead');
  const headRow = element('tr');
  for (const h of headers) {
    const th = element('th', '', h);
    th.scope = 'col';
    headRow.append(th);
  }
  head.append(headRow);
  const body = element('tbody');
  for (const row of rows) {
    const tr = element('tr');
    row.forEach((cell, i) => {
      const td = element(i === 0 ? 'th' : 'td', cell === null ? 'none' : '', cell === null ? 'No data' : cell);
      if (i === 0) td.scope = 'row';
      tr.append(td);
    });
    body.append(tr);
  }
  table.append(head, body);
  container.replaceChildren(table);
}

/* ---------- Health ---------- */

const RANGE_KEY = 'hypr-watch-health-range';
const RANGES = [7, 30, 90];
const SERIES_POINT_BUDGET = 1500;
const SERIES_POINT_CAP = 40000;
const STAGES = [
  { key: 'deep', label: 'Deep' },
  { key: 'light', label: 'Light' },
  { key: 'rem', label: 'REM' },
  { key: 'awake', label: 'Awake' },
];

const health = {
  range: RANGES.includes(Number(storage.get('localStorage', RANGE_KEY))) ? Number(storage.get('localStorage', RANGE_KEY)) : 7,
  seq: 0,
  loading: false,
  data: null,
  busy: false,
  syncError: '',
  allWorkouts: false,
  syncErrorAt: 0,
  lastRunState: null,
  pollTimer: null,
  resetTables: false,
  lastProcessing: false,
};

/** Smallest bucket that keeps the chart readable and the request under the server's point cap. */
function seriesBucket(days) {
  const minutes = days * 1440;
  for (const bucket of [5, 15, 60]) {
    if (minutes / bucket <= SERIES_POINT_BUDGET) return bucket;
  }
  return minutes / 60 <= SERIES_POINT_CAP ? 60 : null;
}

async function loadHealth() {
  if (!state.token) return;
  const seq = ++health.seq;
  const days = health.range;
  const dates = rangeDates(days);
  const query = `from=${dates[0]}&to=${dates[dates.length - 1]}`;
  const bucket = seriesBucket(days);
  health.loading = true;
  $('health-body').setAttribute('aria-busy', 'true');
  const results = await Promise.allSettled([
    api('health/days?' + query),
    api(`health/series?metric=heart_rate&bucket=${bucket}&` + query),
    api('health/sleep?' + query),
    api('health/workouts?' + query),
    api('health/profile'),
  ]);
  if (seq !== health.seq) return;
  health.loading = false;
  const [days_, series, sleep, workouts, profile] = results.map((r) => (r.status === 'fulfilled' ? { ok: true, value: r.value } : { ok: false, error: r.reason }));
  health.data = { dates, bucket, days: days_, series, sleep, workouts, profile };
  $('health-body').setAttribute('aria-busy', 'false');
  renderHealth();
}

function renderHealth() {
  const data = health.data;
  if (!data) return;
  const failures = [data.days, data.series, data.sleep, data.workouts].filter((r) => !r.ok);
  if (failures.some((r) => r.error && r.error.status === 401)) return;

  const allFailed = failures.length === 4;
  $('health-error').hidden = !allFailed;
  if (allFailed) {
    const error = failures[0].error;
    $('health-error-text').textContent = error && error.status === 0 ? offlineMessage() : `Couldn’t load health data. ${error && error.message ? error.message : ''}`.trim();
  }

  const profile = data.profile.ok ? data.profile.value : null;
  $('profile-callout').hidden = !(profile && profile.configured === false);
  const goal = profile && profile.profile && isNum(profile.profile.goal_steps) ? profile.profile.goal_steps : null;

  const byDate = new Map();
  if (data.days.ok) for (const day of data.days.value.days || []) byDate.set(day.date, day);
  const days = data.dates.map((date) => byDate.get(date) || { date });
  const nights = data.sleep.ok ? sleepNights(data.sleep.value.sessions || []) : new Map();
  const points = data.series.ok ? (data.series.value.points || []).filter((p) => isNum(p.value)) : [];
  const workouts = data.workouts.ok ? data.workouts.value.workouts || [] : [];

  const dayFields = ['steps', 'resting_hr', 'avg_hr', 'sleep_minutes', 'avg_spo2', 'avg_stress', 'calories'];
  const hasAny = days.some((day) => dayFields.some((f) => isNum(day[f]))) || nights.size > 0 || points.length > 0 || workouts.length > 0;
  const empty = !allFailed && !hasAny && failures.length === 0;
  $('health-empty').hidden = !empty;
  $('health-body').hidden = empty || allFailed;
  renderHealthEmpty();
  if (empty || allFailed) return;

  renderToday(days, nights, goal);
  renderSteps(data, days, goal);
  renderHeartRate(data, points);
  renderSleep(data, nights);
  renderWorkouts(data, workouts);
  if (health.resetTables && data.dates.length === health.range) {
    health.resetTables = false;
    // Newest row is first: a new range starts at the top-left of every open table.
    document.querySelectorAll('#health-view .table-wrap').forEach((wrap) => wrap.scrollTo(0, 0));
  }
}

function sleepNights(sessions) {
  const nights = new Map();
  for (const s of sessions) {
    if (!isNum(s.wakeup_time)) continue;
    const date = watchLocal(s.wakeup_time, s.tz_offset).toISOString().slice(0, 10);
    const night = nights.get(date) || { date, deep: null, light: null, rem: null, awake: null, asleep: null, bed: s.bed_time, wake: s.wakeup_time, tz: s.tz_offset || 0 };
    let staged = 0;
    for (const { key } of STAGES) {
      const v = s[key + '_minutes'];
      if (isNum(v)) night[key] = (night[key] || 0) + v;
      if (isNum(v) && key !== 'awake') staged += v;
    }
    const asleep = isNum(s.total_minutes) ? s.total_minutes : staged || null;
    if (isNum(asleep)) night.asleep = (night.asleep || 0) + asleep;
    if (isNum(s.bed_time)) night.bed = Math.min(night.bed, s.bed_time);
    night.wake = Math.max(night.wake, s.wakeup_time);
    nights.set(date, night);
  }
  return nights;
}

function tile({ label, value, unit, note, noteTone, className, meter }) {
  const box = element('div', 'tile' + (className ? ' ' + className : ''));
  box.append(element('span', 'tile-label', label));
  const hasValue = value !== null && value !== undefined;
  const valueNode = element('span', 'tile-value' + (hasValue ? '' : ' none'));
  if (hasValue) {
    valueNode.append(document.createTextNode(value));
    if (unit) valueNode.append(element('span', 'tile-unit', unit));
  } else {
    valueNode.textContent = 'No data';
  }
  box.append(valueNode);
  if (meter !== undefined && hasValue) {
    const track = element('span', 'meter');
    track.setAttribute('aria-hidden', 'true');
    const fill = element('span');
    fill.style.transform = `scaleX(${Math.min(1, Math.max(0, meter))})`;
    track.append(fill);
    box.append(track);
  }
  if (note) {
    const noteNode = element('span', 'tile-note' + (noteTone ? ' ' + noteTone : ''));
    if (noteTone === 'ok') noteNode.append(svgIcon('check'));
    noteNode.append(document.createTextNode(note));
    box.append(noteNode);
  }
  return box;
}

/** Today's value, or the most recent day in range that has one. */
function latest(days, field) {
  for (let i = days.length - 1; i >= 0; i--) {
    if (isNum(days[i][field])) return { value: days[i][field], today: i === days.length - 1, date: days[i].date };
  }
  return null;
}

function renderToday(days, nights, goal) {
  const today = days[days.length - 1];
  const tiles = [];

  const steps = isNum(today.steps) ? today.steps : null;
  let stepsNote = goal ? `Goal ${numberFormat.format(goal)}` : 'Set a step goal in Settings';
  let stepsTone = '';
  if (steps !== null && goal) {
    if (steps >= goal) {
      stepsNote = `Goal of ${numberFormat.format(goal)} reached`;
      stepsTone = 'ok';
    } else {
      stepsNote = `${numberFormat.format(goal - steps)} to go · goal ${numberFormat.format(goal)}`;
    }
  }
  tiles.push(tile({ label: 'Steps', value: steps === null ? null : numberFormat.format(steps), note: stepsNote, noteTone: stepsTone, className: 'tile-steps', meter: goal ? (steps || 0) / goal : undefined }));

  const resting = isNum(today.resting_hr) ? today.resting_hr : null;
  const avg = isNum(today.avg_hr) ? today.avg_hr : null;
  tiles.push(
    tile({
      label: resting !== null ? 'Resting heart rate' : 'Heart rate',
      value: resting !== null ? numberFormat.format(resting) : avg !== null ? numberFormat.format(Math.round(avg)) : null,
      unit: 'bpm',
      note: resting !== null && avg !== null ? `Average ${numberFormat.format(Math.round(avg))} bpm` : resting === null && avg !== null ? 'Average today' : '',
    }),
  );

  const night = nights.get(today.date);
  const sleepMinutes = isNum(today.sleep_minutes) ? today.sleep_minutes : night && isNum(night.asleep) ? night.asleep : null;
  tiles.push(tile({ label: 'Sleep', value: formatMinutes(sleepMinutes), note: sleepMinutes === null ? '' : 'Last night' }));

  const spo2 = latest(days, 'avg_spo2');
  tiles.push(tile({ label: 'Blood oxygen', value: spo2 ? decimalFormat.format(spo2.value) : null, unit: '%', note: spo2 ? (spo2.today ? 'Average today' : `Latest, ${dateFormat.format(parseIsoDate(spo2.date))}`) : '' }));

  const stress = latest(days, 'avg_stress');
  tiles.push(tile({ label: 'Stress', value: stress ? numberFormat.format(Math.round(stress.value)) : null, unit: 'of 100', note: stress ? (stress.today ? 'Average today' : `Latest, ${dateFormat.format(parseIsoDate(stress.date))}`) : '' }));

  $('today-tiles').replaceChildren(...tiles);
}

function rangeWords(days) {
  return `the last ${days} days`;
}

function renderSteps(data, days, goal) {
  const chart = $('steps-chart');
  if (!data.days.ok) {
    $('steps-sub').textContent = '';
    chartMessage(chart, `Couldn’t load steps. ${data.days.error.message || ''}`.trim());
    $('steps-table').replaceChildren();
    return;
  }
  const values = days.map((d) => (isNum(d.steps) ? d.steps : null));
  const known = values.filter(isNum);
  if (!known.length) {
    $('steps-sub').textContent = '';
    chartMessage(chart, `No steps recorded in ${rangeWords(days.length)}.`);
    $('steps-table').replaceChildren();
    return;
  }
  const average = Math.round(known.reduce((a, b) => a + b, 0) / known.length);
  const met = goal ? known.filter((v) => v >= goal).length : 0;
  $('steps-sub').textContent = `Average ${numberFormat.format(average)} a day` + (goal ? ` · goal met on ${met} of ${known.length} days` : '');
  const best = Math.max(...known);
  columnChart(chart, {
    dates: data.dates,
    series: [{ cls: 'seg-steps', values }],
    goal,
    tickFormat: (t) => compactFormat.format(t),
    label: `Steps per day, ${rangeWords(days.length)}. Average ${numberFormat.format(average)}, highest ${numberFormat.format(best)}.` + (goal ? ` Goal ${numberFormat.format(goal)}, met on ${met} days.` : '') + ` ${days.length - known.length} days without data.`,
    tip: (i) => tipNodes(fullDayFormat.format(parseIsoDate(data.dates[i])), [{ strong: true, text: values[i] === null ? 'No data' : `${numberFormat.format(values[i])} steps` }]),
  });
  renderTable(
    $('steps-table'),
    ['Day', 'Steps'],
    data.dates.map((date, i) => [fullDayFormat.format(parseIsoDate(date)), values[i] === null ? null : numberFormat.format(values[i])]).reverse(),
  );
}

/** Hourly points folded into one point per local day: average, with the day's lowest and highest bucket. */
function dailyPoints(points) {
  const days = new Map();
  for (const p of points) {
    const key = isoDate(new Date(p.t * 1000));
    const day = days.get(key) || { sum: 0, n: 0, min: Infinity, max: -Infinity };
    day.sum += p.value;
    day.n++;
    day.min = Math.min(day.min, p.value);
    day.max = Math.max(day.max, p.value);
    days.set(key, day);
  }
  return [...days.entries()]
    .sort(([a], [b]) => (a < b ? -1 : 1))
    .map(([date, day]) => ({ date, t: parseIsoDate(date).getTime() / 1000, value: day.sum / day.n, min: day.min, max: day.max }));
}

function renderHeartRate(data, points) {
  const chart = $('hr-chart');
  if (!data.series.ok) {
    $('hr-sub').textContent = '';
    chartMessage(chart, `Couldn’t load heart rate. ${data.series.error.message || ''}`.trim());
    return;
  }
  if (!points.length) {
    $('hr-sub').textContent = '';
    chartMessage(chart, `No heart rate recorded in ${rangeWords(data.dates.length)}.`);
    return;
  }
  const values = points.map((p) => p.value);
  const lo = Math.round(Math.min(...values));
  const hi = Math.round(Math.max(...values));
  const avg = Math.round(values.reduce((a, b) => a + b, 0) / values.length);
  const first = parseIsoDate(data.dates[0]);
  first.setHours(0, 0, 0, 0);
  const end = parseIsoDate(data.dates[data.dates.length - 1]);
  end.setHours(24, 0, 0, 0);
  const from = first.getTime() / 1000;
  const to = Math.min(end.getTime(), Date.now() + data.bucket * 60000) / 1000;
  const range = `${numberFormat.format(lo)} to ${numberFormat.format(hi)} bpm`;

  // Past a week, intraday buckets are too dense to read: show each day's average and range instead.
  if (data.dates.length > 7) {
    const daily = dailyPoints(points);
    $('hr-sub').textContent = `Daily average and range · ${range}`;
    lineChart(chart, {
      points: daily,
      from,
      to: end.getTime() / 1000,
      gap: 86400 * 1.5,
      dates: data.dates,
      label: `Heart rate, ${rangeWords(data.dates.length)}: daily average with the day's range. Lowest ${lo}, highest ${hi}, overall average ${avg} beats per minute.`,
      tip: (i) =>
        tipNodes(fullDayFormat.format(daily[i].t * 1000), [
          { strong: true, text: `${numberFormat.format(Math.round(daily[i].value))} bpm average` },
          { text: `Range ${numberFormat.format(Math.round(daily[i].min))} to ${numberFormat.format(Math.round(daily[i].max))}` },
        ]),
    });
    return;
  }

  const per = data.bucket === 60 ? 'Hourly' : `${data.bucket}-minute`;
  $('hr-sub').textContent = `${per} averages · ${range}`;
  lineChart(chart, {
    points,
    from,
    to,
    gap: data.bucket * 60 * 3,
    dates: data.dates,
    label: `Heart rate, ${rangeWords(data.dates.length)}, ${per.toLowerCase()} averages. Lowest ${lo}, highest ${hi}, average ${avg} beats per minute.`,
    tip: (i) => tipNodes(dayTimeFormat.format(points[i].t * 1000), [{ strong: true, text: `${decimalFormat.format(points[i].value)} bpm` }]),
  });
}

function renderSleep(data, nights) {
  const chart = $('sleep-chart');
  const legend = $('sleep-legend');
  if (!data.sleep.ok) {
    $('sleep-sub').textContent = '';
    legend.replaceChildren();
    chartMessage(chart, `Couldn’t load sleep. ${data.sleep.error.message || ''}`.trim());
    $('sleep-table').replaceChildren();
    return;
  }
  const rows = data.dates.map((date) => nights.get(date) || null);
  const withData = rows.filter(Boolean);
  if (!withData.length) {
    $('sleep-sub').textContent = '';
    legend.replaceChildren();
    chartMessage(chart, `No sleep recorded in ${rangeWords(data.dates.length)}.`);
    $('sleep-table').replaceChildren();
    return;
  }

  const lastDate = data.dates[data.dates.length - 1];
  const prevDate = data.dates[data.dates.length - 2];
  const last = nights.get(lastDate) || nights.get(prevDate) || null;
  if (last) {
    const span = `${watchTimeFormat.format(watchLocal(last.bed, last.tz))} to ${watchTimeFormat.format(watchLocal(last.wake, last.tz))}`;
    const which = last.date === lastDate ? 'Last night' : 'The night before';
    $('sleep-sub').textContent = `${which}: ${formatMinutes(last.asleep) ? formatMinutes(last.asleep) + ' asleep, ' : ''}${span}`;
  } else {
    const asleep = withData.filter((n) => isNum(n.asleep));
    const avg = asleep.length ? asleep.reduce((a, n) => a + n.asleep, 0) / asleep.length : null;
    $('sleep-sub').textContent = avg ? `Average ${formatMinutes(avg)} asleep a night` : '';
  }

  // Legend doubles as the direct label for last night's stages.
  legend.replaceChildren(
    ...STAGES.map((stage) => {
      const item = element('li');
      item.append(element('span', 'swatch ' + stage.key), document.createTextNode(stage.label));
      const v = last ? formatMinutes(last[stage.key]) : null;
      if (v) item.append(element('span', 'legend-value', v));
      return item;
    }),
  );

  const toHours = (v) => (isNum(v) ? v / 60 : null);
  const asleepNights = withData.filter((n) => isNum(n.asleep));
  const avg = asleepNights.length ? asleepNights.reduce((a, n) => a + n.asleep, 0) / asleepNights.length : null;
  columnChart(chart, {
    dates: data.dates,
    series: STAGES.map((stage) => ({ cls: 'seg-' + stage.key, values: rows.map((n) => (n ? toHours(n[stage.key]) : null)) })),
    tickFormat: (t) => `${decimalFormat.format(t)}h`,
    label: `Sleep per night by stage: deep, light, REM and awake, ${rangeWords(data.dates.length)}. ${withData.length} nights recorded` + (avg ? `, average ${formatMinutes(avg)} asleep.` : '.'),
    tip: (i) => {
      const night = rows[i];
      const title = fullDayFormat.format(parseIsoDate(data.dates[i]));
      if (!night) return tipNodes(title, [{ strong: true, text: 'No data' }]);
      const lines = [{ strong: true, text: night.asleep !== null ? `${formatMinutes(night.asleep)} asleep` : 'Sleep recorded' }];
      for (const stage of [...STAGES].reverse()) {
        if (isNum(night[stage.key])) lines.push({ swatch: stage.key, text: `${stage.label} ${formatMinutes(night[stage.key])}` });
      }
      return tipNodes(title, lines);
    },
  });
  renderTable(
    $('sleep-table'),
    ['Night ending', 'Asleep', ...STAGES.map((s) => s.label)],
    data.dates
      .map((date, i) => {
        const n = rows[i];
        return [fullDayFormat.format(parseIsoDate(date)), n ? formatMinutes(n.asleep) : null, ...STAGES.map((s) => (n ? formatMinutes(n[s.key]) : null))];
      })
      .reverse(),
  );
}

const SPORT_ACRONYMS = new Set(['HIIT', 'BMX', 'ATV']);

function sportName(sport) {
  if (!sport || sport === 'UNKNOWN') return 'Workout';
  return String(sport)
    .split(/[_\s]+/)
    .map((word, i) => {
      const upper = word.toUpperCase();
      if (SPORT_ACRONYMS.has(upper)) return upper;
      const lower = word.toLowerCase();
      return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(' ');
}

const WORKOUT_PREVIEW = 8;

function renderWorkouts(data, workouts) {
  const list = $('workouts');
  if (!data.workouts.ok) {
    $('workouts-sub').textContent = '';
    list.replaceChildren(element('li', 'empty-note', `Couldn’t load workouts. ${data.workouts.error.message || ''}`.trim()));
    return;
  }
  const sorted = [...workouts].sort((a, b) => b.start - a.start);
  const n = sorted.length;
  $('workouts-sub').textContent = n ? `${n} in ${rangeWords(data.dates.length)}` : '';
  if (!n) {
    list.replaceChildren(element('li', 'empty-note', `No workouts in ${rangeWords(data.dates.length)}. Workouts you start on the watch show up here.`));
    return;
  }
  const shown = health.allWorkouts ? sorted : sorted.slice(0, WORKOUT_PREVIEW);
  const items = shown.map((w) => {
    const item = element('li', 'workout');
    const icon = element('span', 'workout-icon');
    icon.append(svgIcon('workout'));
    const main = element('div', 'workout-main');
    main.append(element('p', 'workout-title', sportName(w.sport)));
    const seconds = isNum(w.active_seconds) ? w.active_seconds : isNum(w.end) && isNum(w.start) ? w.end - w.start : null;
    const when = [watchDayTimeFormat.format(watchLocal(w.start, w.tz_offset))];
    if (seconds !== null) when.push(formatMinutes(seconds / 60));
    main.append(element('p', 'workout-when', when.join(' · ')));
    const stats = element('p', 'workout-stats');
    const stat = (value, unit) => {
      const span = element('span');
      span.append(element('b', '', value), document.createTextNode(' ' + unit));
      stats.append(span);
    };
    if (isNum(w.distance_m) && w.distance_m > 0) stat(decimalFormat.format(w.distance_m / 1000), 'km');
    if (isNum(w.calories)) stat(numberFormat.format(Math.round(w.calories)), 'kcal');
    if (isNum(w.avg_hr)) stat(numberFormat.format(Math.round(w.avg_hr)), 'bpm avg');
    if (isNum(w.steps) && w.steps > 0) stat(numberFormat.format(w.steps), 'steps');
    if (stats.childElementCount) main.append(stats);
    item.append(icon, main);
    return item;
  });
  if (n > shown.length) {
    const more = element('li', 'workouts-more');
    const button = element('button', 'ghost', `Show all ${n}`);
    button.type = 'button';
    button.addEventListener('click', () => {
      health.allWorkouts = true;
      renderWorkouts(data, workouts);
      // The button is gone; keep keyboard focus at the first newly shown workout.
      const title = $('workouts').children[shown.length].querySelector('.workout-title');
      title.tabIndex = -1;
      title.focus({ preventScroll: true });
    });
    more.append(button);
    items.push(more);
  }
  list.replaceChildren(...items);
}

function healthStatusInfo() {
  const status = state.status;
  if (!status) return null;
  const h = status.health && typeof status.health === 'object' ? status.health : {};
  const ingest = h.ingest && typeof h.ingest === 'object' ? h.ingest : {};
  const watchState = status.watch && status.watch.state;
  return {
    h,
    connected: watchState === 'connected',
    // The watch re-authenticates on its own; that is a wait, not a failure.
    connecting: watchState === 'connecting',
    running: Boolean(h.state && h.state !== 'idle'),
    pending: isNum(ingest.pending) && ingest.pending > 0 ? Math.round(ingest.pending) : 0,
    ingestError: typeof ingest.last_error === 'string' && ingest.last_error.trim() ? ingest.last_error.trim() : '',
  };
}

function renderHealthEmpty() {
  const info = healthStatusInfo();
  const connected = info && info.connected;
  let text = 'Connect the watch on the Notes tab, then tap Sync now. Steps, heart rate and sleep show up here after the first sync.';
  if (info && (info.running || info.pending)) text = 'Syncing with the watch. Your data shows up here when it finishes.';
  else if (info && info.connecting) text = 'Connecting to the watch. Tap Sync now once it’s connected.';
  else if (connected) text = 'Tap Sync now to copy steps, heart rate and sleep from the watch.';
  $('health-empty-text').textContent = text;
  $('health-empty-go').hidden = Boolean(connected || (info && info.connecting));
}

function renderHealthSync() {
  const info = healthStatusInfo();
  if (!info) return;
  const { h, connected, connecting, running, pending, ingestError } = info;
  // The inline sync error lasts until a run starts or a minute passes.
  if (running || Date.now() - health.syncErrorAt > 60000) health.syncError = '';
  const card = $('health-sync');
  const lastOk = isNum(h.last_success_at) && h.last_success_at > 0 ? h.last_success_at : 0;
  const lastError = isNum(h.last_error_at) && h.last_error_at > lastOk && h.last_error ? h.last_error_at : 0;

  let tone = 'idle';
  let title = 'Not synced yet';
  let detail = '';
  if (running || health.busy) {
    tone = 'busy';
    title = 'Syncing health data…';
    detail = h.message && h.message !== 'Idle' ? h.message : '';
    if (pending) detail = (detail ? detail.replace(/[.\s…]+$/, '') + ' · ' : '') + `Processing ${numberFormat.format(pending)} ${pending === 1 ? 'file' : 'files'}`;
  } else if (lastError) {
    tone = 'error';
    title = `Last sync failed ${relativeTime(lastError)}`;
    detail = String(h.last_error).replace(/[.\s]+$/, '') + '.';
  } else if (pending) {
    // Downloaded files are still being read into the database.
    tone = 'busy';
    title = `Processing ${numberFormat.format(pending)} ${pending === 1 ? 'file' : 'files'}…`;
    detail = lastOk ? `Last synced ${relativeTime(lastOk)}.` : '';
  } else if (ingestError) {
    tone = 'error';
    title = 'Couldn’t process some synced data';
    detail = ingestError.replace(/[.\s]+$/, '') + '.' + (lastOk ? ` Last synced ${relativeTime(lastOk)}.` : '');
  } else if (lastOk) {
    tone = 'ok';
    title = `Last synced ${relativeTime(lastOk)}`;
  }
  if (health.syncError && !running && !health.busy) {
    tone = 'error';
    title = 'Couldn’t start a sync';
    detail = health.syncError;
  } else if (!running && connecting) {
    detail = (detail ? detail + ' ' : '') + 'Connecting to the watch…';
  } else if (!running && !connected) {
    detail = (detail ? detail + ' ' : '') + 'Watch not connected. Connect it on the Notes tab to sync.';
  } else if (!running && isNum(h.deferred) && h.deferred > 0) {
    detail = (detail ? detail + ' ' : '') + 'Some files were left for the next sync.';
  }
  card.dataset.state = tone;
  $('health-sync-title').textContent = title;
  $('health-sync-detail').textContent = detail;

  const button = $('health-sync-now');
  // Stays enabled without a watch: status can be stale, and the server's answer is shown inline.
  button.disabled = running || health.busy;
  button.classList.toggle('busy', running || health.busy);
  button.querySelector('span').textContent = running || health.busy ? 'Syncing…' : 'Sync now';

  // A finished run or finished processing brings new data: reload what the charts show.
  const finishedRun = health.lastRunState && health.lastRunState !== 'idle' && !running;
  const finishedProcessing = health.lastProcessing && !pending;
  if ((finishedRun || finishedProcessing) && view.current === 'health') loadHealth();
  health.lastRunState = h.state || 'idle';
  health.lastProcessing = pending > 0;

  clearTimeout(health.pollTimer);
  if ((running || pending) && view.current === 'health') {
    health.pollTimer = setTimeout(() => {
      if (!document.hidden && canPoll()) quietRefresh();
    }, 2500);
  }

  if (view.current === 'health' && !health.data && !health.loading) loadHealth();
  if (view.current === 'settings' && !settings.loaded && !settings.loading) loadSettings();
  if (!$('health-empty').hidden) renderHealthEmpty();
}

$('health-sync-now').addEventListener('click', async () => {
  health.busy = true;
  health.syncError = '';
  renderHealthSync();
  try {
    const result = await api('health/sync', 'POST', {});
    if (result.started === false) notice('A health sync is already running');
    if (result.health && state.status) state.status.health = result.health;
    await quietRefresh();
  } catch (error) {
    if (error.status === 400) {
      const text = String(error.message || '').replace(/[.\s]+$/, '');
      health.syncError = /connect the watch/i.test(text) ? 'Connect the watch on the Notes tab first.' : text + '.';
      health.syncErrorAt = Date.now();
    } else report(error);
  } finally {
    health.busy = false;
    renderHealthSync();
  }
});

$('range').addEventListener('click', (event) => {
  const button = event.target.closest('button[data-range]');
  if (!button) return;
  const days = Number(button.dataset.range);
  if (days === health.range) return;
  health.range = days;
  health.allWorkouts = false;
  health.resetTables = true;
  storage.set('localStorage', RANGE_KEY, String(days));
  renderRange();
  loadHealth();
});

function renderRange() {
  document.querySelectorAll('#range button').forEach((b) => b.setAttribute('aria-pressed', String(Number(b.dataset.range) === health.range)));
}
renderRange();

$('health-retry').addEventListener('click', () => loadHealth());
$('health-empty-go').addEventListener('click', () => goToView('notes'));
$('profile-callout-go').addEventListener('click', () => goToView('settings'));

// Charts are drawn at the container's width; redraw when it changes.
let chartWidth = 0;
let resizeFrame = 0;
window.addEventListener('resize', () => {
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(() => {
    const width = $('steps-chart').clientWidth;
    if (view.current === 'health' && health.data && width && width !== chartWidth) {
      chartWidth = width;
      renderHealth();
    }
  });
});

/* ---------- Settings: health profile ---------- */

const PROFILE_FIELDS = {
  height_cm: { label: 'Height', min: 50, max: 250, whole: true, empty: 'Enter your height.' },
  weight_kg: { label: 'Weight', min: 20, max: 300, whole: false, empty: 'Enter your weight.' },
  goal_steps: { label: 'Step goal', min: 100, max: 100000, whole: true, empty: 'Enter a step goal.' },
  goal_calories: { label: 'Calorie goal', min: 10, max: 5000, whole: true, empty: 'Enter a calorie goal.' },
  goal_standing_hours: { label: 'Standing goal', min: 1, max: 24, whole: true, empty: 'Enter a standing goal.' },
  goal_moving_minutes: { label: 'Moving goal', min: 1, max: 1440, whole: true, empty: 'Enter a moving goal.' },
};
const PROFILE_LABELS = { ...Object.fromEntries(Object.entries(PROFILE_FIELDS).map(([k, v]) => [k, v.label])), birthday: 'Birthday', gender: 'Gender' };
const SUGGESTED_GOALS = { goal_steps: 8000, goal_calories: 300, goal_standing_hours: 12, goal_moving_minutes: 30 };

const settings = { loaded: false, loading: false, baseline: '', saving: false };

function profileSnapshot() {
  const form = $('profile-form');
  return JSON.stringify([...new FormData(form).entries()]);
}

function fillProfile(profile) {
  const values = profile || SUGGESTED_GOALS;
  for (const key of Object.keys(PROFILE_FIELDS)) $('pf-' + key).value = isNum(values[key]) ? String(values[key]) : '';
  $('pf-birthday').value = profile && profile.birthday ? profile.birthday : '';
  document.querySelectorAll('#pf-gender input').forEach((input) => {
    input.checked = Boolean(profile && profile.gender === input.value);
  });
  clearProfileErrors();
  settings.baseline = profileSnapshot();
  setSummary('profile-sum', profile ? 'Set up' : 'Not set up yet');
}

function setProfileError(key, message) {
  const error = $(`pf-${key}-error`);
  if (!error) return false;
  error.textContent = message;
  error.hidden = !message;
  $('pf-' + key).setAttribute('aria-invalid', message ? 'true' : 'false');
  return true;
}

function clearProfileErrors() {
  for (const key of Object.keys(PROFILE_LABELS)) setProfileError(key, '');
  $('profile-error').hidden = true;
}

function readProfile() {
  const payload = {};
  const errors = {};
  for (const [key, spec] of Object.entries(PROFILE_FIELDS)) {
    const raw = $('pf-' + key).value.trim();
    const value = Number(raw.replace(',', '.'));
    const range = `${numberFormat.format(spec.min)} to ${numberFormat.format(spec.max)}`;
    if (!raw) errors[key] = spec.empty;
    else if (!Number.isFinite(value) || value < spec.min || value > spec.max) errors[key] = `Use a number from ${range}.`;
    else if (spec.whole && !Number.isInteger(value)) errors[key] = `Use a whole number from ${range}.`;
    else payload[key] = value;
  }
  dropBirthdayPreset();
  const birthday = $('pf-birthday').value;
  if (!birthday) errors.birthday = 'Enter your birthday.';
  else if (!/^\d{4}-\d{2}-\d{2}$/.test(birthday) || birthday < '1900-01-01') errors.birthday = 'Use a real date.';
  else if (birthday > isoDate(new Date())) errors.birthday = 'Use a date that isn’t in the future.';
  else payload.birthday = birthday;
  const gender = document.querySelector('#pf-gender input:checked');
  if (!gender) errors.gender = 'Choose one.';
  else payload.gender = gender.value;
  return { payload, errors };
}

function setStatusLine(id, text, tone = '') {
  const status = $(id);
  clearTimeout(status.timer);
  status.className = 'save-status' + (tone ? ' ' + tone : '');
  status.replaceChildren();
  if (!text) return;
  if (tone === 'ok') status.append(svgIcon('check'));
  status.append(document.createTextNode(text));
  if (tone === 'ok') status.timer = setTimeout(() => setStatusLine(id, ''), 5000);
}

$('profile-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (settings.saving) return;
  clearProfileErrors();
  const { payload, errors } = readProfile();
  const keys = Object.keys(errors);
  if (keys.length) {
    for (const key of keys) setProfileError(key, errors[key]);
    const first = document.querySelector('#profile-form [aria-invalid="true"]');
    if (first) (first.matches('fieldset') ? first.querySelector('input') : first).focus();
    setStatusLine('profile-status', '');
    return;
  }
  const button = $('profile-save');
  settings.saving = true;
  button.disabled = true;
  button.replaceChildren(element('span', 'spinner'), document.createTextNode('Saving…'));
  setStatusLine('profile-status', '');
  try {
    const result = await api('health/profile', 'PUT', payload);
    fillProfile(result.profile);
    setStatusLine('profile-status', result.sent_to_watch ? 'Saved and sent to the watch' : 'Saved. It goes to the watch when it connects.', 'ok');
    if (health.data) health.data.profile = { ok: true, value: result };
  } catch (error) {
    if (error.status === 400) showProfileServerError(error.message);
    else if (error.status !== 401) {
      setStatusLine('profile-status', 'Not saved', 'error');
      report(error);
    }
  } finally {
    settings.saving = false;
    button.disabled = false;
    button.textContent = 'Save profile';
  }
});

/** Server messages start with the field key ("height_cm must be…"); show them on that field in plain words. */
function showProfileServerError(message) {
  const match = /^([a-z_]+)\s+(must .*)$/i.exec(message || '');
  if (match && PROFILE_LABELS[match[1]]) {
    const text = match[2].charAt(0).toUpperCase() + match[2].slice(1);
    setProfileError(match[1], text.endsWith('.') ? text : text + '.');
    const field = $('pf-' + match[1]);
    (field.matches('fieldset') ? field.querySelector('input') : field).focus();
    return;
  }
  $('profile-error').textContent = message || 'Not saved. Check the fields and try again.';
  $('profile-error').hidden = false;
}

$('profile-form').addEventListener('input', (event) => {
  const key = event.target.name;
  if (key && $(`pf-${key}-error`) && !$(`pf-${key}-error`).hidden) setProfileError(key, '');
});

$('pf-birthday').max = isoDate(new Date());

/*
 * Android's date picker opens on the field's value, or on today when it's empty, which puts a birthday decades of
 * scrolling away. On a touch tap of an empty field, preset a value just before the picker reads it. It's kept only
 * if the user confirms a date; cancelling the picker (no input event) clears it again on blur.
 */
const birthdayPreset = { value: '' };
$('pf-birthday').addEventListener('click', (event) => {
  const input = event.currentTarget;
  const touch = event.pointerType === 'touch' || (!event.pointerType && matchMedia('(pointer: coarse)').matches);
  if (!touch || input.value || input.readOnly || input.disabled) return;
  birthdayPreset.value = `${new Date().getFullYear() - 30}-01-01`;
  input.value = birthdayPreset.value;
});
$('pf-birthday').addEventListener('input', () => {
  birthdayPreset.value = '';
});
function dropBirthdayPreset() {
  const input = $('pf-birthday');
  if (birthdayPreset.value && input.value === birthdayPreset.value) input.value = '';
  birthdayPreset.value = '';
}
$('pf-birthday').addEventListener('blur', dropBirthdayPreset);
// In case the field never took focus (so never blurs): the next touch anywhere else drops an unconfirmed preset.
document.addEventListener('pointerdown', (event) => {
  if (birthdayPreset.value && event.target !== $('pf-birthday')) dropBirthdayPreset();
}, true);

/* ---------- Settings: notifications ---------- */

const notif = { data: null, saving: false, again: false, writes: 0, key: '', timer: 0, lastLoad: 0 };
const NOTIF_POLL_MS = 15000;

function renderNotifAccess() {
  const data = notif.data;
  const row = $('notif-access');
  if (!data) return;
  const onPhone = hasNativeBridge();
  const count = (data.allowed || []).length;
  setSummary('notif-sum', data.access ? `${count} ${count === 1 ? 'app' : 'apps'}${data.calls ? ' and calls' : ''}` : 'Access is off');
  row.dataset.state = data.access ? 'ok' : 'warn';
  $('notif-access-title').textContent = data.access ? 'Notification access is on' : 'Notification access is off';
  $('notif-access-detail').textContent = data.access
    ? 'Hypr Watch can forward the apps you pick below.'
    : 'Turn it on so notifications can reach the watch.' + (onPhone ? '' : ' The button opens Android settings on the phone.');
  const button = $('notif-access-open');
  button.textContent = data.access ? 'Manage' : onPhone ? 'Turn on' : 'Open on phone';
  button.className = data.access ? 'ghost' : 'secondary';
}

function renderNotifications() {
  const data = notif.data;
  if (!data) return;
  renderNotifAccess();
  $('notif-calls').checked = Boolean(data.calls);
  const allowed = new Set(data.allowed || []);
  const seenAt = (app) => (isNum(app.last_seen_ms) ? app.last_seen_ms : isNum(app.last_seen) ? app.last_seen : 0);
  const recent = [...(data.recent || [])].sort((a, b) => seenAt(b) - seenAt(a));
  const seen = new Set(recent.map((r) => r.package));
  // Allowed apps that haven't posted lately: the server may know their names; otherwise the package stands in.
  const knownNames = new Map();
  for (const app of Array.isArray(data.allowed_apps) ? data.allowed_apps : []) {
    if (app && typeof app.package === 'string' && typeof app.app_name === 'string' && app.app_name.trim()) knownNames.set(app.package, app.app_name.trim());
  }
  const extra = [...allowed]
    .filter((pkg) => !seen.has(pkg))
    .map((pkg) => ({ package: pkg, app_name: knownNames.get(pkg) || null }))
    .sort((a, b) => (a.app_name || a.package).localeCompare(b.app_name || b.package));
  const apps = [...recent, ...extra];
  $('notif-empty').hidden = apps.length > 0;

  // A background refresh rebuilds the rows; keep focus on the same app's switch.
  const focused = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.package : undefined;
  $('notif-apps').replaceChildren(
    ...apps.map((app, i) => {
      const item = element('li');
      const id = 'app-' + i;
      const label = element('label', 'app-row');
      label.htmlFor = id;
      const text = element('span', 'app-text');
      const name = app.app_name && app.app_name !== app.package ? app.app_name : app.package;
      text.append(element('span', 'app-name', name));
      const meta = element('span', 'app-meta');
      meta.id = id + '-meta';
      if (name !== app.package) {
        const code = element('code', '', app.package);
        code.translate = false;
        meta.append(code, document.createTextNode(' · '));
      }
      meta.append(document.createTextNode(seenAt(app) ? 'Last notification ' + relativeTime(seenAt(app)) : 'No recent notifications'));
      text.append(meta);
      const input = element('input');
      input.type = 'checkbox';
      input.id = id;
      input.setAttribute('role', 'switch');
      input.dataset.package = app.package;
      input.checked = allowed.has(app.package);
      input.setAttribute('aria-describedby', meta.id);
      label.append(text, input);
      item.append(label);
      return item;
    }),
  );
  if (focused !== undefined) {
    const again = [...document.querySelectorAll('#notif-apps input[data-package]')].find((input) => input.dataset.package === focused);
    if (again) again.focus({ preventScroll: true });
  }
}

function notifPayload() {
  const allowed = [...document.querySelectorAll('#notif-apps input[data-package]')].filter((i) => i.checked).map((i) => i.dataset.package);
  return { calls: $('notif-calls').checked, allowed };
}

/** Writes the whole setting; toggles made while a save is in flight go out in one follow-up save. */
async function saveNotifications() {
  if (notif.saving) {
    notif.again = true;
    return;
  }
  notif.saving = true;
  setStatusLine('notif-status', 'Saving…');
  try {
    do {
      notif.again = false;
      notif.writes += 1;
      notif.data = await api('notifications', 'PUT', notifPayload());
    } while (notif.again);
    notif.key = JSON.stringify(notif.data);
    renderNotifAccess();
    setStatusLine('notif-status', 'Saved', 'ok');
  } catch (error) {
    setStatusLine('notif-status', 'Not saved', 'error');
    report(error);
    // Show what the phone actually has.
    renderNotifications();
  } finally {
    notif.saving = false;
  }
}

$('notif-calls').addEventListener('change', saveNotifications);
$('notif-apps').addEventListener('change', (event) => {
  if (event.target.dataset.package) saveNotifications();
});

$('notif-access-open').addEventListener('click', () =>
  attempt(async () => {
    const result = await api('notifications/access', 'POST', {});
    const opened = result.requested !== undefined ? result.requested : result.opened;
    if (!opened) notice('Couldn’t open Android settings. On the phone, open Settings and search for Notification access.');
    else if (!hasNativeBridge()) notice('Notification access settings are open on the phone');
  }),
);

async function loadNotifications({ quiet = false } = {}) {
  const writes = notif.writes;
  notif.lastLoad = Date.now();
  try {
    const data = await api('notifications');
    // A save started meanwhile has the newer answer; a toggle in flight must not flip back.
    if (writes !== notif.writes || notif.saving) return;
    const key = JSON.stringify(data);
    notif.data = data;
    if (key === notif.key && quiet) return;
    notif.key = key;
    renderNotifications();
  } catch (error) {
    if (error.status === 401 || quiet) return;
    $('notif-access').dataset.state = 'error';
    $('notif-access-title').textContent = 'Couldn’t load notification settings';
    $('notif-access-detail').textContent = error.status === 0 ? offlineMessage() : error.message;
  }
}

async function loadProfile() {
  try {
    const result = await api('health/profile');
    // Don't overwrite what the user is typing.
    if (!settings.loaded || profileSnapshot() === settings.baseline) fillProfile(result.profile);
    return true;
  } catch (error) {
    if (error.status === 401) return false;
    $('profile-error').textContent = `Couldn’t load the profile. ${error.status === 0 ? offlineMessage() : error.message}`;
    $('profile-error').hidden = false;
    return false;
  }
}

async function loadSettings() {
  if (!state.token || settings.loading) return;
  settings.loading = true;
  const [profileOk] = await Promise.all([loadProfile(), loadNotifications(), loadFeatures(), loadAgents()]);
  settings.loading = false;
  if (profileOk) settings.loaded = true;
}

/*
 * New apps show up in the list as they post notifications, so the list refreshes while Settings is on screen:
 * on return to the app (also from Android's notification access screen), on focus, and every 15 seconds.
 */
function notifPollActive() {
  return view.current === 'settings' && !document.hidden && canPoll();
}

function scheduleNotifPoll() {
  clearTimeout(notif.timer);
  notif.timer = 0;
  if (!notifPollActive()) return;
  notif.timer = setTimeout(async () => {
    if (notifPollActive()) await loadNotifications({ quiet: true });
    scheduleNotifPoll();
  }, NOTIF_POLL_MS);
}

function refreshNotifsNow() {
  if (!notifPollActive()) {
    scheduleNotifPoll();
    return;
  }
  // visibilitychange and focus usually arrive together on return; one request covers both.
  if (Date.now() - notif.lastLoad > 1000) loadNotifications({ quiet: true });
  scheduleNotifPoll();
}

document.addEventListener('visibilitychange', refreshNotifsNow);
window.addEventListener('focus', refreshNotifsNow);

/* ---------- Watch features: shared helpers ---------- */

// Routes that wait for the watch answer within about 25 seconds; this only stops a request that hangs.
const WATCH_WAIT_MS = 45000;

/** A feature's object in /api/status; null when missing or when the server reports {error} for it. */
const featureStatus = (key) => {
  const value = state.status && state.status[key];
  return value && typeof value === 'object' && !('error' in value && Object.keys(value).length === 1) ? value : null;
};
const linkState = () => (state.status && state.status.watch && state.status.watch.state) || 'unknown';

/** "connect the watch first" -> "Connect the watch first." */
function sentence(text) {
  const t = String(text || '').trim().replace(/[.\s]+$/, '');
  return t ? t.charAt(0).toUpperCase() + t.slice(1) + '.' : '';
}

function errorText(error) {
  if (!error) return 'Something went wrong. Try again.';
  if (error.status === 0) return offlineMessage();
  // 409: the watch is busy with another change; the same action works a moment later.
  if (error.status === 409) {
    const text = sentence(error.message) || 'The watch is busy.';
    return /try again/i.test(text) ? text : text + ' Try again in a moment.';
  }
  return sentence(error.message) || 'Something went wrong. Try again.';
}

function setNote(id, text, tone = '') {
  const note = $(id);
  note.hidden = !text;
  note.textContent = text || '';
  note.className = 'section-note' + (tone ? ' ' + tone : '');
}

function setSummary(id, text) {
  $(id).textContent = text || '';
}

/** Puts a button into its working state; the returned function puts its content back. */
function busyButton(button, label) {
  const saved = [...button.childNodes].map((node) => node.cloneNode(true));
  button.disabled = true;
  button.classList.add('is-busy');
  button.replaceChildren(element('span', 'spinner'), document.createTextNode(label));
  return () => {
    button.classList.remove('is-busy');
    button.replaceChildren(...saved);
    button.disabled = false;
  };
}

/** A clock text "07:30" in the user's locale. */
const clockFormat = new Intl.DateTimeFormat(navigator.language, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' });
function clockText(hhmm) {
  const match = /^(\d{2}):(\d{2})$/.exec(hhmm || '');
  if (!match) return hhmm || '';
  return clockFormat.format(Date.UTC(1970, 0, 1, Number(match[1]), Number(match[2])));
}

function switchRow(id, label, hint) {
  const row = element('label', 'switch-row');
  row.htmlFor = id;
  const text = element('span', 'switch-text');
  text.append(element('span', 'switch-label', label));
  const input = element('input');
  input.type = 'checkbox';
  input.id = id;
  input.setAttribute('role', 'switch');
  if (hint) {
    const hintNode = element('span', 'switch-hint', hint);
    hintNode.id = id + '-hint';
    text.append(hintNode);
    input.setAttribute('aria-describedby', hintNode.id);
  }
  row.append(text, input);
  return { row, input };
}

/** A native select with the app's chevron; options are [value, label] pairs. */
function selectField(id, label, options, value) {
  const field = element('div', 'field');
  const labelNode = element('label', '', label);
  labelNode.htmlFor = id;
  const wrap = element('div', 'select-wrap');
  const select = element('select');
  select.id = id;
  for (const [optionValue, optionLabel] of options) {
    const option = element('option', '', optionLabel);
    option.value = String(optionValue);
    select.append(option);
  }
  if (value === null || value === undefined) {
    const blank = element('option', '', '—');
    blank.value = '';
    select.prepend(blank);
    select.value = '';
  } else {
    select.value = String(value);
  }
  wrap.append(select, svgIcon('down', 'icon select-chevron'));
  field.append(labelNode, wrap);
  return { field, select };
}

/* ---------- Settings sections fold on phones ---------- */

const FOLD_KEY = 'hypr-watch-folds';

function foldMemory() {
  try {
    return JSON.parse(storage.get('localStorage', FOLD_KEY) || '{}') || {};
  } catch {
    return {};
  }
}

function applyFolds() {
  const saved = foldMemory();
  document.querySelectorAll('details.fold').forEach((fold) => {
    fold.open = !isMobile() || Boolean(saved[fold.dataset.fold]);
    // On wide screens every section stays open, so the heading is not a control.
    fold.querySelector('summary').tabIndex = isMobile() ? 0 : -1;
  });
}

document.querySelectorAll('details.fold').forEach((fold) => {
  fold.querySelector('summary').addEventListener('click', (event) => {
    if (!isMobile()) event.preventDefault();
  });
  fold.addEventListener('toggle', () => {
    if (!isMobile()) return;
    const saved = foldMemory();
    if (Boolean(saved[fold.dataset.fold]) === fold.open) return;
    saved[fold.dataset.fold] = fold.open;
    storage.set('localStorage', FOLD_KEY, JSON.stringify(saved));
  });
});
mobileQuery.addEventListener('change', applyFolds);
applyFolds();

/* ---------- Watch card: battery, device, find watch, time ---------- */

const FIND_MS = 30000;
const deviceUi = { findUntil: 0, findTimer: 0, fastTimer: 0 };

function findingWatch() {
  if (deviceUi.findUntil > Date.now()) return true;
  const d = featureStatus('device');
  // The server's flag covers a find started elsewhere; this page's own start uses its timer.
  return Boolean(!deviceUi.findUntil && d && d.find_watch && d.find_watch.vibrating);
}

function renderDevice() {
  const d = featureStatus('device');
  const connected = watchConnected();
  const battery = d && d.battery && isNum(d.battery.level) ? d.battery : null;
  const info = d && d.device ? d.device : null;

  $('battery').hidden = !battery;
  if (battery) {
    const parts = [`${Math.round(battery.level)}%`];
    if (battery.charging === true) parts.push('charging');
    if (!connected && isNum(battery.at)) parts.push(relativeTime(battery.at));
    $('battery-text').textContent = parts.join(' · ');
    $('battery-icon').setAttribute('href', battery.charging === true ? '#i-bolt' : '#i-battery');
    $('battery').dataset.level = battery.charging !== true && battery.level <= 15 ? 'low' : '';
    $('battery').setAttribute('aria-label', `Watch battery ${Math.round(battery.level)}%${battery.charging === true ? ', charging' : ''}`);
  }
  const failed = Boolean(state.status && state.status.device && state.status.device.error && !d);
  const name = info ? [info.model, info.firmware ? 'firmware ' + info.firmware : ''].filter(Boolean).join(' · ') : failed ? 'Battery and model unavailable' : '';
  $('device-name').textContent = name;
  $('device-row').hidden = !battery && !name;

  $('device-actions').hidden = !connected;
  const finding = connected && findingWatch();
  const findButton = $('find-watch');
  if (!findButton.classList.contains('is-busy')) {
    findButton.querySelector('span').textContent = finding ? 'Stop' : 'Find watch';
    findButton.setAttribute('aria-pressed', String(finding));
  }
  findButton.classList.toggle('active', finding);

  const ringing = Boolean(d && d.find_phone && d.find_phone.ringing);
  $('ringing').hidden = !ringing;
  if (!ringing) $('ring-stop-error').hidden = true;

  // Ringing and vibrating end on their own; check back sooner than the 15-second poll.
  clearTimeout(deviceUi.fastTimer);
  if (ringing || finding) {
    deviceUi.fastTimer = setTimeout(() => {
      if (!document.hidden && canPoll()) quietRefresh();
    }, 3000);
  }
}

$('find-watch').addEventListener('click', async () => {
  const button = $('find-watch');
  const on = !findingWatch();
  const restore = busyButton(button, on ? 'Starting…' : 'Stopping…');
  setStatusLine('device-status', '');
  try {
    await api('device/find-watch', 'POST', { on }, { timeout: 15000 });
    clearTimeout(deviceUi.findTimer);
    if (on) {
      deviceUi.findUntil = Date.now() + FIND_MS;
      deviceUi.findTimer = setTimeout(() => {
        deviceUi.findUntil = 0;
        renderDevice();
      }, FIND_MS);
      setStatusLine('device-status', 'The watch is vibrating. It stops by itself after 30 seconds.');
    } else {
      deviceUi.findUntil = 0;
      setStatusLine('device-status', '');
    }
  } catch (error) {
    if (error.status !== 401) setStatusLine('device-status', errorText(error), 'error');
  } finally {
    restore();
    renderDevice();
  }
});

$('ring-stop').addEventListener('click', async () => {
  const restore = busyButton($('ring-stop'), 'Stopping…');
  $('ring-stop-error').hidden = true;
  try {
    await api('device/find-phone/stop', 'POST', {}, { timeout: 15000 });
    const d = featureStatus('device');
    if (d && d.find_phone) d.find_phone.ringing = false;
  } catch (error) {
    if (error.status !== 401) {
      $('ring-stop-error').textContent = errorText(error);
      $('ring-stop-error').hidden = false;
    }
  } finally {
    restore();
    renderDevice();
    quietRefresh();
  }
});

$('time-sync').addEventListener('click', async () => {
  const restore = busyButton($('time-sync'), 'Syncing…');
  setStatusLine('device-status', '');
  try {
    await api('device/time-sync', 'POST', {}, { timeout: 15000 });
    setStatusLine('device-status', 'Watch time set from this phone', 'ok');
  } catch (error) {
    if (error.status !== 401) setStatusLine('device-status', errorText(error), 'error');
  } finally {
    restore();
  }
});

/* ---------- Health: live heart rate ---------- */

const LIVE_RENEW_MS = 75000;
const LIVE_POLL_MS = 2000;
const LIVE_STALE_MS = 60000;

const live = { active: false, seq: 0, renewTimer: 0, pollTimer: 0, starting: false, bpm: null, lastBpm: null, lastAt: 0, startedAt: 0, error: '' };

function liveWanted() {
  return view.current === 'health' && !document.hidden && canPoll() && watchConnected();
}

function syncLiveHeartRate() {
  if (liveWanted()) {
    startLive();
  } else {
    // Leaving the tab or losing the watch clears an error, so the next visit tries again.
    live.error = '';
    stopLive();
  }
  renderLive();
}

async function startLive() {
  if (live.active || live.error) return;
  live.active = true;
  live.starting = true;
  live.startedAt = Date.now();
  const seq = ++live.seq;
  try {
    applyLive(await api('heart-rate/live', 'POST', { on: true }, { timeout: 15000 }));
  } catch (error) {
    if (seq !== live.seq) return;
    live.active = false;
    live.starting = false;
    live.error = error.status === 401 ? '' : errorText(error);
    renderLive();
    return;
  }
  if (seq !== live.seq) {
    // Left the Health tab while the start was on its way.
    api('heart-rate/live', 'POST', { on: false }).catch(() => {});
    return;
  }
  live.starting = false;
  renderLive();
  clearInterval(live.renewTimer);
  live.renewTimer = setInterval(() => {
    api('heart-rate/live', 'POST', { on: true }, { timeout: 15000 }).then(applyLive, () => {});
  }, LIVE_RENEW_MS);
  pollLive(seq);
}

async function pollLive(seq) {
  clearTimeout(live.pollTimer);
  if (seq !== live.seq || !live.active) return;
  try {
    applyLive(await api('heart-rate/live'));
  } catch {
    /* The tile shows the reading going stale; the next poll may work. */
  }
  if (seq !== live.seq || !live.active) return;
  renderLive();
  live.pollTimer = setTimeout(() => pollLive(seq), LIVE_POLL_MS);
}

function applyLive(result) {
  live.bpm = result && isNum(result.bpm) ? result.bpm : null;
  if (live.bpm !== null) {
    live.lastBpm = live.bpm;
    live.lastAt = Date.now();
  }
}

function stopLive(options = {}) {
  if (!live.active) return;
  live.active = false;
  live.starting = false;
  live.seq++;
  clearInterval(live.renewTimer);
  clearTimeout(live.pollTimer);
  live.bpm = null;
  if (options.unloading) {
    try {
      fetch('/api/heart-rate/live', { method: 'POST', keepalive: true, headers: { Authorization: 'Bearer ' + state.token, 'Content-Type': 'application/json' }, body: '{"on":false}' });
    } catch {
      /* The server ends the stream after two minutes without a renewal anyway. */
    }
    return;
  }
  api('heart-rate/live', 'POST', { on: false }).catch(() => {});
}

window.addEventListener('pagehide', () => stopLive({ unloading: true }));

function renderLive() {
  const card = $('live-hr');
  const value = $('live-value');
  const note = $('live-note');
  const link = linkState();
  $('live-retry').hidden = !live.error;
  value.replaceChildren();
  let tone = 'off';
  let text = '';
  if (live.error) {
    tone = 'error';
    note.textContent = live.error;
  } else if (!watchConnected()) {
    note.textContent = link === 'connecting' ? 'Connecting to the watch…' : 'Connect the watch to see your heart rate as it happens.';
  } else if (live.bpm !== null) {
    tone = 'live';
    text = numberFormat.format(Math.round(live.bpm));
    note.textContent = 'Live from the watch';
  } else if (live.lastBpm !== null && Date.now() - live.lastAt < LIVE_STALE_MS) {
    tone = 'stale';
    text = numberFormat.format(Math.round(live.lastBpm));
    note.textContent = `Last reading ${Math.max(1, Math.round((Date.now() - live.lastAt) / 1000))} s ago. Waiting for the watch…`;
  } else if (live.active && Date.now() - live.startedAt < LIVE_STALE_MS) {
    tone = 'measuring';
    note.textContent = 'Measuring…';
  } else if (live.active) {
    tone = 'stale';
    note.textContent = 'No reading yet. Check that the watch is snug on your wrist.';
  } else {
    note.textContent = 'Starts when this tab is open.';
  }
  card.dataset.state = tone;
  if (text) value.append(document.createTextNode(text), element('span', 'tile-unit', 'bpm'));
  value.hidden = !text;
}

$('live-retry').addEventListener('click', () => {
  live.error = '';
  syncLiveHeartRate();
});

/* ---------- Settings: loading every watch feature ---------- */

const featureSigs = {};

async function loadFeatures() {
  if (!state.token) return;
  await Promise.all([loadWatchSettings(), loadAlarms(), loadFaces(), loadQuickReplies(), loadContacts(), loadMusic(), loadWeather()]);
}

/** After each status poll: re-render what depends on the link, and reload a section whose watch data changed. */
function onFeatureStatus() {
  syncLiveHeartRate();
  renderHealthConnect();
  if (!state.status) return;
  // The face rows' buttons depend on whether an install runs; rebuild them only when that changes.
  const installing = faceInstallRunning();
  if (installing !== faceInstall.wasRunning) {
    faceInstall.wasRunning = installing;
    renderFaces();
  } else renderFaceInstall();
  const sig = (key, fields) => {
    const s = featureStatus(key) || {};
    return JSON.stringify([linkState(), ...fields.map((f) => s[f])]);
  };
  const watched = {
    watch_settings: [['read_at', 'supported_count'], loadWatchSettings],
    alarms: [['read_state', 'last_read', 'alarms', 'sleep_mode_read_state'], loadAlarms],
    watch_faces: [['list_state', 'read_at', 'active_id', 'count'], loadFaces],
    quick_replies: [['read_at', 'supported', 'writable', 'write'], loadQuickReplies],
    contacts: [['synced_at', 'supported', 'count'], loadContacts],
    music: [['enabled', 'access', 'supported'], loadMusic],
    weather: [['enabled', 'last_update', 'last_error', 'supported'], loadWeather],
  };
  for (const [key, [fields, load]] of Object.entries(watched)) {
    const next = sig(key, fields);
    const changed = featureSigs[key] !== undefined && featureSigs[key] !== next;
    featureSigs[key] = next;
    if (changed && view.current === 'settings' && settings.loaded) load({ quiet: true });
  }
}

/* ---------- Settings: watch health settings ---------- */

const WS_BLOCKS = {
  hr_interval: 'heart_rate', hr_high_alert: 'heart_rate', hr_low_alert: 'heart_rate', sleep_advanced_monitoring: 'heart_rate',
  sleep_breathing_quality: 'heart_rate', irregular_rhythm_alert: 'heart_rate', sleep_respiratory_rate: 'heart_rate',
  spo2_mode: 'spo2', spo2_low_alert: 'spo2',
  stress_auto: 'stress', relax_reminder: 'stress',
  sedentary_reminder: 'sedentary_reminder', sedentary_start: 'sedentary_reminder', sedentary_end: 'sedentary_reminder',
  sedentary_quiet: 'sedentary_reminder', sedentary_quiet_start: 'sedentary_reminder', sedentary_quiet_end: 'sedentary_reminder',
  goal_notifications: 'goal_notification', secondary_goal: 'goals', vitality_seven_day: 'vitality_score', vitality_daily_progress: 'vitality_score',
};

const bpmOptions = (values, word) => [[0, 'Off'], ...values.map((v) => [v, `${word} ${v} bpm`])];

const WS_GROUPS = [
  {
    key: 'heart', title: 'Heart rate',
    items: [
      { key: 'hr_interval', label: 'Measure', type: 'select', options: [['off', 'Off'], ['smart', 'Automatically'], ['1min', 'Every minute'], ['10min', 'Every 10 minutes'], ['30min', 'Every 30 minutes']] },
      { key: 'hr_high_alert', label: 'High heart rate alert', type: 'select', options: bpmOptions([100, 110, 120, 130, 140, 150], 'Above') },
      { key: 'hr_low_alert', label: 'Low heart rate alert', type: 'select', options: bpmOptions([40, 45, 50], 'Below') },
      { key: 'irregular_rhythm_alert', label: 'Irregular rhythm alert', type: 'switch' },
      { key: 'sleep_advanced_monitoring', label: 'Detailed sleep tracking', hint: 'Uses more battery', type: 'switch' },
      { key: 'sleep_breathing_quality', label: 'Breathing quality in sleep', type: 'switch' },
      { key: 'sleep_respiratory_rate', label: 'Breathing rate in sleep', type: 'switch' },
    ],
  },
  {
    key: 'spo2', title: 'Blood oxygen',
    items: [
      { key: 'spo2_mode', label: 'Measure', type: 'select', from: 'spo2_mode', names: { off: 'Off', sleep: 'During sleep', all_day: 'All day' } },
      { key: 'spo2_low_alert', label: 'Low oxygen alert', type: 'select', options: [[0, 'Off'], [80, 'Below 80%'], [85, 'Below 85%'], [90, 'Below 90%']] },
    ],
  },
  {
    key: 'stress', title: 'Stress',
    items: [
      { key: 'stress_auto', label: 'Measure stress all day', type: 'switch' },
      { key: 'relax_reminder', label: 'Relax reminder', hint: 'When stress stays high', type: 'switch' },
    ],
  },
  {
    key: 'move', title: 'Move reminder',
    items: [
      { key: 'sedentary_reminder', label: 'Remind me to move', hint: 'After an hour sitting still', type: 'switch' },
      { key: 'sedentary_start', label: 'From', type: 'time', pair: 'move-hours', needs: 'sedentary_reminder' },
      { key: 'sedentary_end', label: 'Until', type: 'time', pair: 'move-hours', needs: 'sedentary_reminder' },
      { key: 'sedentary_quiet', label: 'Lunch break', hint: 'No reminders in this window', type: 'switch', needs: 'sedentary_reminder' },
      { key: 'sedentary_quiet_start', label: 'From', type: 'time', pair: 'quiet-hours', needs: 'sedentary_quiet' },
      { key: 'sedentary_quiet_end', label: 'Until', type: 'time', pair: 'quiet-hours', needs: 'sedentary_quiet' },
    ],
  },
  {
    key: 'goals', title: 'Goals',
    items: [
      { key: 'goal_notifications', label: 'Goal reached alerts', type: 'switch' },
      { key: 'secondary_goal', label: 'Second goal ring', type: 'select', from: 'secondary_goal', names: { moving: 'Moving minutes', standing: 'Standing hours' } },
      { key: 'vitality_seven_day', label: 'Vitality score, 7-day summary', type: 'switch' },
      { key: 'vitality_daily_progress', label: 'Vitality score, daily progress', type: 'switch' },
    ],
  },
];

const ws = { data: null, error: null, saving: {}, results: {} };

async function loadWatchSettings() {
  try {
    ws.data = await api('watch-settings');
    ws.error = null;
  } catch (error) {
    if (error.status === 401) return;
    ws.error = error;
  }
  renderWatchSettings();
}

function wsBlockState(key) {
  const supported = ws.data && ws.data.supported ? ws.data.supported[WS_BLOCKS[key]] : 'unknown';
  return supported || 'unknown';
}

function renderWatchSettings() {
  const data = ws.data;
  const connected = watchConnected();
  const container = $('ws-groups');
  if (!data) {
    setNote('ws-note', ws.error ? `Couldn’t load watch settings. ${errorText(ws.error)}` : 'Loading…', ws.error ? 'error' : '');
    setSummary('ws-sum', '');
    container.replaceChildren();
    return;
  }
  const states = Object.values(data.supported || {});
  const known = states.filter((s) => s !== 'unknown').length;
  if (!connected) {
    // The phone keeps no copy of these: they're read from the watch on each connection.
    setNote('ws-note', 'Connect the watch to see and change these settings.');
    setSummary('ws-sum', 'Watch not connected');
    container.replaceChildren();
    return;
  }
  if (!known) {
    setNote('ws-note', 'No reply from the watch yet. These show up a few seconds after it connects.');
    setSummary('ws-sum', 'Waiting for the watch');
    container.replaceChildren();
    return;
  }
  if (states.every((st) => st === 'unsupported')) {
    setNote('ws-note', 'This watch doesn’t offer any of these settings.', 'warn');
    setSummary('ws-sum', 'Not on this watch');
    container.replaceChildren();
    return;
  }
  setNote('ws-note', '');
  const hr = data.settings.hr_interval;
  const names = { off: 'Heart rate off', smart: 'Heart rate automatic', '1min': 'Heart rate every minute', '10min': 'Heart rate every 10 min', '30min': 'Heart rate every 30 min' };
  setSummary('ws-sum', names[hr] || `${known} of ${states.length} groups read`);

  const keep = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.ws : undefined;
  container.replaceChildren(...WS_GROUPS.map(renderWsGroup));
  if (keep) {
    const again = container.querySelector(`[data-ws="${keep}"]`);
    if (again) again.focus({ preventScroll: true });
  }
}

function renderWsGroup(group) {
  const data = ws.data;
  const box = element('section', 'ws-group');
  const head = element('div', 'ws-head');
  const title = element('h3', 'form-section', group.title);
  title.id = 'ws-' + group.key;
  box.setAttribute('aria-labelledby', title.id);
  head.append(title);
  const status = element('p', 'save-status');
  status.setAttribute('role', 'status');
  const result = ws.results[group.key];
  if (ws.saving[group.key]) {
    status.append(element('span', 'spinner'), document.createTextNode('Saving…'));
  } else if (result) {
    status.className = 'save-status ' + result.tone;
    if (result.tone === 'ok') status.append(svgIcon('check'));
    status.append(document.createTextNode(result.text));
  }
  head.append(status);
  if (result && result.reread && !ws.saving[group.key]) {
    const reread = element('button', 'secondary small', 'Read again');
    reread.type = 'button';
    reread.addEventListener('click', async () => {
      const restore = busyButton(reread, 'Reading…');
      await loadWatchSettings();
      restore();
      delete ws.results[group.key];
      renderWatchSettings();
    });
    head.append(reread);
  }
  box.append(head);

  const blocks = [...new Set(group.items.map((item) => WS_BLOCKS[item.key]))];
  const blockStates = blocks.map((b) => (data.supported && data.supported[b]) || 'unknown');
  if (blockStates.every((s) => s === 'unsupported')) {
    box.append(element('p', 'hint', 'Not on this watch.'));
    return box;
  }
  if (blockStates.every((s) => s === 'unknown')) {
    box.append(element('p', 'hint', 'No reply from the watch yet.'));
    return box;
  }

  const rows = element('div', 'ws-rows');
  const pairs = new Map();
  for (const item of group.items) {
    const blockState = wsBlockState(item.key);
    if (blockState === 'unsupported') continue;
    const value = data.settings[item.key];
    const unknown = blockState === 'unknown';
    const offered = !unknown && value !== null && value !== undefined;
    const parentOff = item.needs && data.settings[item.needs] === false;
    const disabled = !offered || ws.saving[group.key] || parentOff;
    const id = 'ws-' + item.key;
    let node;
    if (item.type === 'switch') {
      const { row, input } = switchRow(id, item.label, !offered ? (unknown ? 'No reply yet' : 'Not offered by this watch') : item.hint);
      input.checked = value === true;
      input.disabled = disabled;
      input.dataset.ws = item.key;
      row.classList.add('setting-row');
      node = row;
    } else if (item.type === 'select') {
      let options = item.options;
      if (item.from) {
        const offeredValues = data.options && Array.isArray(data.options[item.from]) ? data.options[item.from] : [];
        options = offeredValues.map((v) => [v, item.names[v] || v]);
      }
      const { field, select } = selectField(id, item.label, options, offered ? value : null);
      select.disabled = disabled || !options.length;
      select.dataset.ws = item.key;
      if (!offered) field.append(element('p', 'hint', unknown ? 'No reply yet' : 'Not offered by this watch'));
      node = field;
    } else {
      const field = element('div', 'field');
      const label = element('label', '', item.label);
      label.htmlFor = id;
      const input = element('input');
      input.type = 'time';
      input.id = id;
      input.value = offered ? value : '';
      input.disabled = disabled;
      input.dataset.ws = item.key;
      field.append(label, input);
      node = field;
    }
    if (item.pair) {
      let grid = pairs.get(item.pair);
      if (!grid) {
        grid = element('div', 'field-grid ws-pair');
        pairs.set(item.pair, grid);
        rows.append(grid);
      }
      grid.append(node);
    } else {
      rows.append(node);
    }
  }
  box.append(rows);
  return box;
}

$('ws-groups').addEventListener('change', async (event) => {
  const control = event.target;
  const key = control.dataset && control.dataset.ws;
  if (!key) return;
  const group = WS_GROUPS.find((g) => g.items.some((item) => item.key === key));
  let value;
  if (control.type === 'checkbox') value = control.checked;
  else if (control.type === 'time') {
    if (!/^\d{2}:\d{2}$/.test(control.value)) return;
    value = control.value;
  } else {
    if (control.value === '') return;
    value = /^\d+$/.test(control.value) ? Number(control.value) : control.value;
  }
  ws.saving[group.key] = true;
  delete ws.results[group.key];
  renderWatchSettings();
  try {
    ws.data = await api('watch-settings', 'PUT', { [key]: value }, { timeout: 20000 });
    const verified = ws.data.verified ? ws.data.verified[WS_BLOCKS[key]] : null;
    ws.results[group.key] =
      verified === true
        ? { tone: 'ok', text: 'Saved on the watch' }
        : verified === false
          ? { tone: 'error', text: 'Not applied. The watch kept its setting.' }
          : { tone: 'warn', text: 'Sent. The watch hasn’t confirmed it yet.' };
  } catch (error) {
    if (error.status === 401) return;
    const stale = error.status === 400 && /re-read/i.test(error.message || '');
    ws.results[group.key] = stale
      ? { tone: 'error', text: 'The watch didn’t confirm the last change here. Read its settings again first.', reread: true }
      : { tone: 'error', text: errorText(error) };
    // Show what the watch holds, not the control's unsaved position.
    try {
      ws.data = await api('watch-settings');
    } catch {
      /* Keep the last copy. */
    }
  } finally {
    ws.saving[group.key] = false;
    renderWatchSettings();
  }
  const result = ws.results[group.key];
  if (result && result.tone === 'ok') {
    setTimeout(() => {
      if (ws.results[group.key] === result) {
        delete ws.results[group.key];
        renderWatchSettings();
      }
    }, 5000);
  }
});

/* ---------- Settings: alarms and sleep mode ---------- */

const DAY_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const dayShort = new Intl.DateTimeFormat(navigator.language, { weekday: 'short' });
const dayLong = new Intl.DateTimeFormat(navigator.language, { weekday: 'long' });
// 1 January 2024 was a Monday.
const DAYS = DAY_KEYS.map((key, i) => ({ key, short: dayShort.format(new Date(2024, 0, 1 + i, 12)), long: dayLong.format(new Date(2024, 0, 1 + i, 12)) }));

const alarms = { data: null, error: null, sleep: null, sleepError: null, rowBusy: new Set(), changed: '', editing: null, sheetDays: new Set(), daysTouched: false, refreshing: null, autoAt: 0 };
const ALARMS_STALE_MS = 60000;

async function loadAlarms() {
  const [list, sleep] = await Promise.allSettled([api('alarms'), api('alarms/sleep-mode')]);
  if (list.status === 'fulfilled') {
    alarms.data = list.value;
    alarms.error = null;
  } else if (list.reason.status !== 401) alarms.error = list.reason;
  if (sleep.status === 'fulfilled') {
    alarms.sleep = sleep.value;
    alarms.sleepError = null;
  } else if (sleep.reason.status !== 401) alarms.sleepError = sleep.reason;
  renderAlarms();
  renderSleepMode();
  refreshStaleAlarms();
}

/**
 * Alarms changed on the wrist (or a one-time alarm that rang) may not reach the phone on their own,
 * so a list older than a minute is read from the watch again, at most once a minute.
 */
function refreshStaleAlarms() {
  const data = alarms.data;
  if (!data || alarms.refreshing || !watchConnected() || data.supported === false || data.read_state === 'waiting') return;
  const now = Date.now();
  if ((isNum(data.last_read) && now - data.last_read < ALARMS_STALE_MS) || now - alarms.autoAt < ALARMS_STALE_MS) return;
  alarms.autoAt = now;
  alarms.refreshing = api('alarms/refresh', 'POST', {}, { timeout: WATCH_WAIT_MS })
    .then((result) => {
      alarms.data = result;
      alarms.error = null;
      renderAlarms();
    })
    .catch(() => {})
    .finally(() => {
      alarms.refreshing = null;
    });
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && view.current === 'settings') refreshStaleAlarms();
});

/** The alarm as shown here, sent with each write: the app answers 409 when the watch's alarm changed since. */
function alarmExpect(alarm) {
  return { time: alarm.time, repeat: alarm.repeat || [], repeat_mode: alarm.repeat_mode, enabled: alarm.enabled, smart: Boolean(alarm.smart) };
}

const ALARM_GONE = /no longer on the watch/;
const ALARM_CHANGED = /changed on the watch/;

/** A write refused because the alarm changed on the wrist: show the list as the watch has it now. */
async function showChangedAlarms(error) {
  closeAlarmSheet();
  await loadAlarms();
  const gone = ALARM_GONE.test(error.message || '');
  setNote('alarms-note', gone ? 'That alarm is no longer on the watch. The list now shows the watch’s alarms.' : 'That alarm was changed on the watch. The list now shows it as the watch has it; nothing was saved.', 'warn');
}

const isAlarmChange = (error) => error.status === 409 && (ALARM_GONE.test(error.message || '') || ALARM_CHANGED.test(error.message || ''));

function repeatWords(alarm) {
  const days = alarm.repeat || [];
  if (alarm.repeat_mode && alarm.repeat_mode.startsWith('mode_')) return 'Custom repeat';
  if (days.length === 7 || alarm.repeat_mode === 'daily') return 'Every day';
  if (!days.length) return 'Once';
  if (days.length === 5 && DAY_KEYS.slice(0, 5).every((d) => days.includes(d))) return 'Weekdays';
  if (days.length === 2 && days.includes('sat') && days.includes('sun')) return 'Weekends';
  return DAYS.filter((d) => days.includes(d.key)).map((d) => d.short).join(', ');
}

function alarmReadNote(data, connected) {
  const s = data.read_state;
  const hasList = isNum(data.last_read);
  if (data.supported === false || s === 'rejected') return { text: 'This watch didn’t share its alarms.', tone: 'warn' };
  if (!connected) {
    return hasList
      ? { text: `Watch not connected. These are the alarms it had ${relativeTime(data.last_read)}.`, tone: '' }
      : { text: 'Connect the watch to see its alarms.', tone: '' };
  }
  if (s === 'waiting') return { text: 'Reading alarms from the watch…', tone: '' };
  if (s === 'no_reply' && !hasList) return { text: 'The watch didn’t send its alarms. Tap Refresh to ask again.', tone: 'warn' };
  if (s === 'not_read' && !hasList) return { text: 'Not read yet. Tap Refresh to read them.', tone: '' };
  return null;
}

function renderAlarms() {
  const data = alarms.data;
  const connected = watchConnected();
  const list = $('alarm-list');
  $('alarms-refresh').disabled = !connected || $('alarms-refresh').classList.contains('is-busy');
  if (!data) {
    setNote('alarms-note', alarms.error ? `Couldn’t load alarms. ${errorText(alarms.error)}` : 'Loading…', alarms.error ? 'error' : '');
    list.replaceChildren();
    $('alarm-add').hidden = true;
    $('alarms-limit').hidden = true;
    $('alarms-count').textContent = '';
    setSummary('alarms-sum', '');
    return;
  }
  const items = data.alarms || [];
  const max = isNum(data.max_alarms) ? data.max_alarms : null;
  const note = alarmReadNote(data, connected);
  setNote('alarms-note', note ? note.text : '', note ? note.tone : '');
  const writable = connected && data.supported !== false && data.read_state === 'read';
  const read = isNum(data.last_read);
  $('alarms-count').textContent = read ? (max ? `${items.length} of ${max} alarms` : `${items.length} ${items.length === 1 ? 'alarm' : 'alarms'}`) : '';
  const on = items.filter((a) => a.enabled).length;
  setSummary('alarms-sum', data.supported === false ? 'Not on this watch' : read ? (items.length ? `${items.length} ${items.length === 1 ? 'alarm' : 'alarms'}, ${on} on` : 'No alarms') : connected ? 'Not read yet' : 'Watch not connected');

  list.replaceChildren(...items.map((alarm) => alarmRow(alarm, writable)));
  if (read && !items.length) list.append(element('li', 'empty-note', 'No alarms on the watch.'));
  const full = max !== null && items.length >= max;
  $('alarm-add').hidden = data.supported === false;
  $('alarm-add').disabled = !writable || full;
  $('alarms-limit').hidden = !(full && writable);
  $('alarms-limit').textContent = full ? `The watch holds ${max} alarms. Delete one to add another.` : '';
  $('alarms-changed').hidden = !alarms.changed;
  $('alarms-changed').textContent = alarms.changed;
}

function alarmRow(alarm, writable) {
  const item = element('li', 'alarm' + (alarm.enabled ? '' : ' is-off'));
  const busy = alarms.rowBusy.has(alarm.id);
  const open = element('button', 'alarm-open');
  open.type = 'button';
  open.disabled = !writable || busy;
  open.addEventListener('click', () => openAlarmSheet(alarm));
  const time = element('span', 'alarm-time', clockText(alarm.time));
  const meta = element('span', 'alarm-meta');
  if (alarm.label) meta.append(element('span', 'alarm-label', alarm.label));
  const chips = element('span', 'day-chips');
  const custom = alarm.repeat_mode && alarm.repeat_mode.startsWith('mode_');
  const days = alarm.repeat || [];
  if (custom || !days.length || days.length === 7) {
    chips.append(element('span', 'day-chip on wide', repeatWords(alarm)));
  } else {
    for (const day of DAYS) {
      const chip = element('span', 'day-chip' + (days.includes(day.key) ? ' on' : ''), day.short.slice(0, 2));
      chip.setAttribute('aria-hidden', 'true');
      chips.append(chip);
    }
    chips.append(element('span', 'sr-only', repeatWords(alarm)));
  }
  if (alarm.smart) chips.append(element('span', 'day-chip wide', 'Smart wake'));
  meta.append(chips);
  open.append(time, meta);
  open.setAttribute('aria-label', `${clockText(alarm.time)}, ${repeatWords(alarm)}${alarm.label ? ', ' + alarm.label : ''}${alarm.smart ? ', smart wake' : ''}. Edit`);
  item.append(open);

  if (busy) {
    item.append(element('span', 'spinner row-spinner'));
  } else {
    const toggle = element('input');
    toggle.type = 'checkbox';
    toggle.setAttribute('role', 'switch');
    toggle.checked = alarm.enabled;
    toggle.disabled = !writable;
    toggle.setAttribute('aria-label', `Alarm at ${clockText(alarm.time)}`);
    toggle.addEventListener('change', () => setAlarmEnabled(alarm, toggle.checked));
    item.append(toggle);
  }
  return item;
}

function applyAlarmResult(result) {
  if (Array.isArray(result.alarms)) {
    alarms.data = { ...alarms.data, alarms: result.alarms, last_read: Date.now(), read_state: 'read' };
  }
  alarms.changed = result.method === 'replace' ? 'The watch couldn’t edit that alarm in place, so it deleted it and added a new one. Its id changed.' : '';
}

async function setAlarmEnabled(alarm, enabled) {
  alarms.rowBusy.add(alarm.id);
  renderAlarms();
  try {
    await alarms.refreshing;
    applyAlarmResult(await api('alarms/' + alarm.id, 'PUT', { enabled, expect: alarmExpect(alarm) }, { timeout: WATCH_WAIT_MS }));
    setNote('alarms-note', '');
  } catch (error) {
    if (error.status !== 401) {
      alarms.rowBusy.delete(alarm.id);
      if (isAlarmChange(error)) {
        await showChangedAlarms(error);
        return;
      }
      renderAlarms();
      setNote('alarms-note', `Couldn’t change the alarm. ${errorText(error)}`, 'error');
      return;
    }
  } finally {
    alarms.rowBusy.delete(alarm.id);
  }
  renderAlarms();
}

$('alarms-refresh').addEventListener('click', async () => {
  const restore = busyButton($('alarms-refresh'), 'Reading…');
  try {
    await alarms.refreshing;
    const result = await api('alarms/refresh', 'POST', {}, { timeout: WATCH_WAIT_MS });
    alarms.data = result;
    alarms.error = null;
    alarms.changed = '';
    const sleep = await api('alarms/sleep-mode').catch(() => null);
    if (sleep) alarms.sleep = sleep;
    restore();
    renderAlarms();
    renderSleepMode();
  } catch (error) {
    restore();
    renderAlarms();
    if (error.status !== 401) setNote('alarms-note', `Couldn’t read the alarms. ${errorText(error)}`, 'error');
  }
});

/* Alarm sheet: a modal dialog, one history entry so Back closes it. */

const sheet = $('alarm-sheet');

function renderDayToggles() {
  $('alarm-days').replaceChildren(
    ...DAYS.map((day) => {
      const button = element('button', 'day-toggle', day.short.slice(0, 2));
      button.type = 'button';
      button.dataset.day = day.key;
      button.setAttribute('aria-label', day.long);
      button.setAttribute('aria-pressed', String(alarms.sheetDays.has(day.key)));
      return button;
    }),
  );
  $('alarm-days-hint').textContent = repeatWords({ repeat: [...alarms.sheetDays], repeat_mode: alarms.sheetCustom && !alarms.daysTouched ? 'mode_x' : '' }) + (alarms.sheetCustom && !alarms.daysTouched ? '. Pick days to replace it.' : '');
}

$('alarm-days').addEventListener('click', (event) => {
  const button = event.target.closest('[data-day]');
  if (!button) return;
  const day = button.dataset.day;
  if (alarms.sheetDays.has(day)) alarms.sheetDays.delete(day);
  else alarms.sheetDays.add(day);
  alarms.daysTouched = true;
  renderDayToggles();
  $('alarm-days').querySelector(`[data-day="${day}"]`).focus();
});

function alarmLabelCount() {
  const n = codePoints($('alarm-label').value).length;
  $('alarm-label-count').textContent = `${n}/40`;
}
$('alarm-label').addEventListener('input', alarmLabelCount);

function openAlarmSheet(alarm) {
  alarms.editing = alarm || null;
  alarms.sheetDays = new Set(alarm ? alarm.repeat || [] : []);
  alarms.sheetCustom = Boolean(alarm && alarm.repeat_mode && alarm.repeat_mode.startsWith('mode_'));
  alarms.daysTouched = false;
  $('alarm-sheet-title').textContent = alarm ? 'Edit alarm' : 'New alarm';
  $('alarm-time').value = alarm ? alarm.time : '07:00';
  $('alarm-label').value = alarm && alarm.label ? alarm.label : '';
  $('alarm-smart').checked = Boolean(alarm && alarm.smart);
  $('alarm-error').hidden = true;
  $('alarm-delete').hidden = !alarm;
  disarmAlarmDelete();
  alarmLabelCount();
  renderDayToggles();
  sheet.showModal();
  if (!(history.state && history.state.sheet === 'alarm')) history.pushState({ view: 'settings', sheet: 'alarm' }, '');
  $('alarm-time').focus({ preventScroll: true });
}

function closeAlarmSheet() {
  if (history.state && history.state.sheet === 'alarm') history.back();
  else if (sheet.open) sheet.close();
}

window.addEventListener('popstate', () => {
  if (sheet.open && !(history.state && history.state.sheet === 'alarm')) sheet.close();
});

sheet.addEventListener('cancel', (event) => {
  event.preventDefault();
  if (!sheet.querySelector('.is-busy')) closeAlarmSheet();
});
sheet.addEventListener('click', (event) => {
  // A tap on the backdrop lands on the dialog itself.
  if (event.target === sheet && !sheet.querySelector('.is-busy')) closeAlarmSheet();
});
$('alarm-close').addEventListener('click', closeAlarmSheet);

function alarmSheetError(text) {
  $('alarm-error').textContent = text;
  $('alarm-error').hidden = !text;
}

$('alarm-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const time = $('alarm-time').value;
  if (!/^\d{2}:\d{2}$/.test(time)) {
    alarmSheetError('Pick a time.');
    $('alarm-time').focus();
    return;
  }
  const editing = alarms.editing;
  const repeat = DAY_KEYS.filter((d) => alarms.sheetDays.has(d));
  const label = $('alarm-label').value.trim();
  const smart = $('alarm-smart').checked;
  let payload;
  if (editing) {
    payload = {};
    if (time !== editing.time) payload.time = time;
    if (alarms.daysTouched || repeat.join() !== (editing.repeat || []).join()) payload.repeat = repeat;
    if (label !== (editing.label || '')) payload.label = label || null;
    if (smart !== Boolean(editing.smart)) payload.smart = smart;
    if (!Object.keys(payload).length) {
      closeAlarmSheet();
      return;
    }
    payload.expect = alarmExpect(editing);
  } else {
    payload = { time, repeat, smart, enabled: true, ...(label ? { label } : {}) };
  }
  alarmSheetError('');
  const restore = busyButton($('alarm-save'), 'Saving to watch…');
  $('alarm-delete').disabled = true;
  try {
    await alarms.refreshing;
    const result = await api(editing ? 'alarms/' + editing.id : 'alarms', editing ? 'PUT' : 'POST', payload, { timeout: WATCH_WAIT_MS });
    applyAlarmResult(result);
    restore();
    closeAlarmSheet();
    renderAlarms();
    notice(editing ? 'Alarm saved on the watch' : 'Alarm added to the watch');
  } catch (error) {
    restore();
    if (isAlarmChange(error)) await showChangedAlarms(error);
    else if (error.status !== 401) alarmSheetError(errorText(error));
  } finally {
    $('alarm-delete').disabled = false;
  }
});

let alarmDeleteTimer;
function disarmAlarmDelete() {
  clearTimeout(alarmDeleteTimer);
  $('alarm-delete').classList.remove('armed');
  $('alarm-delete').textContent = 'Delete';
}

$('alarm-delete').addEventListener('click', async () => {
  const button = $('alarm-delete');
  const editing = alarms.editing;
  if (!editing) return;
  if (!button.classList.contains('armed')) {
    button.classList.add('armed');
    button.textContent = 'Tap again to delete';
    alarmDeleteTimer = setTimeout(disarmAlarmDelete, 4000);
    return;
  }
  disarmAlarmDelete();
  alarmSheetError('');
  const restore = busyButton(button, 'Deleting…');
  $('alarm-save').disabled = true;
  try {
    await alarms.refreshing;
    applyAlarmResult(await api('alarms/' + editing.id, 'DELETE', { expect: alarmExpect(editing) }, { timeout: WATCH_WAIT_MS }));
    restore();
    closeAlarmSheet();
    renderAlarms();
    notice('Alarm deleted from the watch');
  } catch (error) {
    restore();
    if (isAlarmChange(error)) await showChangedAlarms(error);
    else if (error.status !== 401) alarmSheetError(errorText(error));
  } finally {
    $('alarm-save').disabled = false;
  }
});

$('alarm-add').addEventListener('click', () => openAlarmSheet(null));

/* Sleep mode */

function renderSleepMode() {
  const s = alarms.sleep;
  const connected = watchConnected();
  const toggle = $('sleep-enabled');
  const busy = $('sleep-save').classList.contains('is-busy') || toggle.dataset.busy === '1';
  if (!s) {
    setNote('sleep-note', alarms.sleepError ? `Couldn’t load sleep mode. ${errorText(alarms.sleepError)}` : '', 'error');
    toggle.disabled = true;
    $('sleep-times').hidden = true;
    $('sleep-save').hidden = true;
    return;
  }
  const known = s.enabled !== null && s.enabled !== undefined;
  const writable = connected && s.supported === true && known;
  let note = '';
  if (s.supported === false || s.read_state === 'rejected') note = 'This watch didn’t share its sleep schedule.';
  else if (!connected) note = known ? `Watch not connected. Showing the schedule from ${relativeTime(s.last_read)}.` : 'Connect the watch to see its sleep schedule.';
  else if (s.read_state === 'waiting') note = 'Reading the schedule from the watch…';
  else if (!known) note = 'No reply from the watch yet. Tap Refresh above to ask again.';
  setNote('sleep-note', note, s.supported === false ? 'warn' : '');
  toggle.checked = s.enabled === true;
  toggle.disabled = !writable || busy;
  // An unread schedule has no on or off to show.
  toggle.hidden = !known;
  $('sleep-times').hidden = !known;
  for (const [id, key] of [['sleep-start', 'start'], ['sleep-end', 'end']]) {
    const input = $(id);
    if (document.activeElement !== input && !input.dataset.dirty) input.value = s[key] || '';
    input.disabled = !writable || busy;
  }
  const changed = known && ($('sleep-start').value !== s.start || $('sleep-end').value !== s.end);
  $('sleep-save').hidden = !(changed || $('sleep-save').classList.contains('is-busy'));
  $('sleep-save').disabled = !writable || busy;
}

for (const id of ['sleep-start', 'sleep-end']) {
  $(id).addEventListener('input', () => {
    $(id).dataset.dirty = '1';
    setStatusLine('sleep-status', '');
    renderSleepMode();
  });
}

async function saveSleep(payload, button) {
  const restore = button ? busyButton(button, 'Saving…') : null;
  setStatusLine('sleep-status', button ? '' : 'Saving…');
  const toggle = $('sleep-enabled');
  if (!button) {
    toggle.dataset.busy = '1';
    toggle.disabled = true;
  }
  try {
    await alarms.refreshing;
    alarms.sleep = await api('alarms/sleep-mode', 'PUT', payload, { timeout: WATCH_WAIT_MS });
    delete $('sleep-start').dataset.dirty;
    delete $('sleep-end').dataset.dirty;
    setStatusLine('sleep-status', alarms.sleep.verified ? 'Saved and checked on the watch' : 'Saved', 'ok');
  } catch (error) {
    if (error.status !== 401) setStatusLine('sleep-status', errorText(error), 'error');
  } finally {
    if (restore) restore();
    delete toggle.dataset.busy;
    renderSleepMode();
  }
}

$('sleep-enabled').addEventListener('change', () => saveSleep({ enabled: $('sleep-enabled').checked }));
$('sleep-save').addEventListener('click', () => {
  const start = $('sleep-start').value;
  const end = $('sleep-end').value;
  if (!start || !end) {
    setStatusLine('sleep-status', 'Pick a start and an end time.', 'error');
    return;
  }
  if (start === end) {
    setStatusLine('sleep-status', 'Start and end need to differ.', 'error');
    return;
  }
  saveSleep({ start, end }, $('sleep-save'));
});

/* ---------- Settings: watch faces ---------- */

const faces = { data: null, error: null, busyId: null, armed: null, armTimer: 0 };

async function loadFaces({ refresh: fromWatch = false } = {}) {
  try {
    faces.data = await api('watch-faces' + (fromWatch ? '?refresh=true' : ''), 'GET', undefined, { timeout: 20000 });
    faces.error = null;
  } catch (error) {
    if (error.status === 401) return;
    faces.error = error;
  }
  renderFaces();
}

function renderFaces() {
  const data = faces.data;
  const connected = watchConnected();
  const list = $('face-list');
  const refreshing = $('faces-refresh').classList.contains('is-busy');
  $('faces-refresh').disabled = !connected || refreshing || faces.busyId !== null;
  const items = data && Array.isArray(data.faces) ? data.faces : null;
  let note = '';
  let tone = '';
  if (faces.error && !items) {
    note = `Couldn’t load watch faces. ${errorText(faces.error)}`;
    tone = 'error';
  } else if (!data) note = 'Loading…';
  else if (data.supported === false) {
    note = 'This watch didn’t share its faces.';
    tone = 'warn';
  } else if (!connected) note = 'Connect the watch to see its faces.';
  else if (!items) note = 'No reply from the watch yet. Tap Refresh to ask again.';
  setNote('faces-note', note, tone);
  $('faces-count').textContent = items ? `${items.length} ${items.length === 1 ? 'face' : 'faces'}` : '';
  const active = items ? items.find((f) => f.active) : null;
  setSummary('faces-sum', data && data.supported === false ? 'Not on this watch' : items ? (active ? `${active.name || 'Face ' + active.id} is on` : `${items.length} faces`) : connected ? '' : 'Watch not connected');
  renderFaceInstall();
  if (!items || !connected) {
    list.replaceChildren();
    return;
  }
  list.replaceChildren(...items.map((face) => faceRow(face)));
}

function faceRow(face) {
  const item = element('li', 'face-row');
  const name = face.name || `Face ${face.id}`;
  const text = element('span', 'face-name', name);
  item.append(text);
  if (face.active) {
    const badge = element('span', 'chip ok');
    badge.append(svgIcon('check'), document.createTextNode('Active'));
    item.append(badge);
    return item;
  }
  const busy = faces.busyId === face.id;
  // Switching or deleting while a face installs would pull the list out from under it.
  const others = (faces.busyId !== null && !busy) || faceInstallRunning();
  const actions = element('span', 'face-actions');
  if (busy) {
    actions.append(element('span', 'spinner row-spinner'));
  } else {
    const use = element('button', 'secondary small', 'Use');
    use.type = 'button';
    use.disabled = others;
    use.setAttribute('aria-label', `Use ${name}`);
    use.addEventListener('click', () => faceAction(face, 'activate'));
    actions.append(use);
    if (face.can_delete) {
      const armed = faces.armed === face.id;
      const del = element('button', armed ? 'danger small' : 'icon-button small delete');
      del.type = 'button';
      del.disabled = others;
      if (armed) del.textContent = 'Delete';
      else del.append(svgIcon('trash'));
      del.setAttribute('aria-label', armed ? `Confirm: delete ${name}` : `Delete ${name}`);
      del.addEventListener('click', () => {
        if (faces.armed !== face.id) {
          faces.armed = face.id;
          clearTimeout(faces.armTimer);
          faces.armTimer = setTimeout(() => {
            faces.armed = null;
            renderFaces();
          }, 4000);
          renderFaces();
          const again = [...$('face-list').querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === `Confirm: delete ${name}`);
          if (again) again.focus();
          return;
        }
        faces.armed = null;
        faceAction(face, 'delete');
      });
      actions.append(del);
    }
  }
  item.append(actions);
  return item;
}

async function faceAction(face, action) {
  faces.busyId = face.id;
  setStatusLine('faces-status', '');
  renderFaces();
  const name = face.name || `Face ${face.id}`;
  try {
    const result = await api(action === 'activate' ? `watch-faces/${encodeURIComponent(face.id)}/activate` : `watch-faces/${encodeURIComponent(face.id)}`, action === 'activate' ? 'POST' : 'DELETE', action === 'activate' ? {} : undefined, { timeout: 30000 });
    faces.data = result;
    if (result.verified) setStatusLine('faces-status', action === 'activate' ? `${name} is on the watch now` : `${name} deleted`, 'ok');
    else setStatusLine('faces-status', action === 'activate' ? 'Sent, but the watch still shows another face.' : 'Sent, but the face is still on the watch.', 'error');
  } catch (error) {
    if (error.status !== 401) setStatusLine('faces-status', errorText(error), 'error');
  } finally {
    faces.busyId = null;
    renderFaces();
  }
}

$('faces-refresh').addEventListener('click', async () => {
  const restore = busyButton($('faces-refresh'), 'Reading…');
  setStatusLine('faces-status', '');
  await loadFaces({ refresh: true });
  restore();
  renderFaces();
});

/* ---------- Settings: installing a watch face ---------- */

const FACE_MAX_BYTES = 6000000;
/*
 * phase: idle, confirm (waiting for the second tap), sending (reading the file and starting the install).
 * Once the server has the file, its watch_faces.install in /api/status drives the progress.
 * watched: started_at of the server install this page saw running; only that one's result is shown.
 */
const faceInstall = { phase: 'idle', file: null, info: null, error: '', unknownConfirmed: false, watched: null, lastState: '', wasRunning: false, pollTimer: 0 };

function serverFaceInstall() {
  const s = featureStatus('watch_faces');
  return s && s.install && typeof s.install === 'object' ? s.install : null;
}

function faceInstallRunning() {
  const job = serverFaceInstall();
  return faceInstall.phase === 'sending' || Boolean(job && (job.state === 'uploading' || job.state === 'activating'));
}

function watchBattery() {
  const d = featureStatus('device');
  return d && d.battery && isNum(d.battery.level) ? d.battery : null;
}

function fileSizeText(bytes) {
  return bytes >= 1000000 ? `${(bytes / 1000000).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1000))} KB`;
}

/** The parts of a v2 face header the server also checks: magic 5A A5, id, name and preview size. */
async function readFaceHeader(file) {
  const head = new Uint8Array(await file.slice(0, 0xa8).arrayBuffer());
  if (head.length < 0x6c || head[0] !== 0x5a || head[1] !== 0xa5) return null;
  const bytes = new DataView(head.buffer);
  const text = (from, to, encoding) => {
    let end = from;
    while (end < Math.min(to, head.length) && head[end] !== 0) end++;
    return end < Math.min(to, head.length) ? new TextDecoder(encoding).decode(head.subarray(from, end)).trim() : '';
  };
  const localised = head[0x68] === 0xff && head[0x69] === 0xff && head[0x6a] === 0xff && head[0x6b] === 0xff;
  const offset = bytes.getUint32(0x20, true);
  let preview = null;
  if (offset > 0 && offset + 12 <= file.size) {
    const p = new DataView(await file.slice(offset + 4, offset + 8).arrayBuffer());
    preview = [p.getUint16(0, true), p.getUint16(2, true)];
  }
  return { id: text(0x28, 0x68, 'ascii'), name: localised ? '' : text(0x68, 0x68 + 64, 'utf-8'), preview };
}

function faceDisplayName(job) {
  if (job && job.name) return job.name;
  const info = faceInstall.info;
  if (info && info.name && (!job || job.id === info.id)) return info.name;
  if (job && job.id) return `Face ${job.id}`;
  return info && info.fileName ? info.fileName : 'the face';
}

function installErrorText(error) {
  if (error.status === 0) return offlineMessage();
  if (error.status === 503 || error.status === 504) return 'The watch didn’t answer. Keep it close and awake, then try again.';
  if (error.status === 409 && /already installed/i.test(error.message)) {
    return `${faceDisplayName(null)} is already on the watch. Delete it first to install it again.`;
  }
  if (error.status === 409) {
    const text = sentence(error.message) || 'The watch is busy.';
    return /try again/i.test(text) ? text : text + ' Try again when it finishes.';
  }
  return errorText(error);
}

$('face-pick').addEventListener('click', () => {
  const input = $('face-file');
  input.value = '';
  input.click();
});

$('face-file').addEventListener('change', async () => {
  const file = $('face-file').files[0];
  if (!file) return;
  faceInstall.error = '';
  faceInstall.watched = null;
  faceInstall.unknownConfirmed = false;
  faceInstall.info = null;
  if (file.size > FACE_MAX_BYTES) {
    faceInstall.error = 'This file is too big for a watch face (over 6 MB).';
    renderFaceInstall();
    return;
  }
  let header = null;
  try {
    header = await readFaceHeader(file);
  } catch {
    faceInstall.error = 'Couldn’t read that file. Pick it again.';
    renderFaceInstall();
    return;
  }
  if (!header) {
    faceInstall.error = 'This isn’t a watch face file for this watch.';
    renderFaceInstall();
    return;
  }
  const installed = faces.data && Array.isArray(faces.data.faces) ? faces.data.faces.find((f) => f.id === header.id) : null;
  if (installed) {
    faceInstall.error = `${installed.name || header.name || 'This face'} is already on the watch. Delete it first to install it again.`;
    renderFaceInstall();
    return;
  }
  faceInstall.file = file;
  faceInstall.info = { ...header, fileName: file.name.replace(/\.bin$/i, ''), size: file.size };
  if (faceInstallWarnings().length) {
    faceInstall.phase = 'confirm';
    renderFaceInstall();
    $('face-cancel').focus();
    return;
  }
  startFaceInstall();
});

function faceInstallWarnings() {
  const warnings = [];
  const battery = watchBattery();
  if (battery && battery.level < 20 && battery.charging !== true) {
    warnings.push(`The watch battery is at ${Math.round(battery.level)}%. A low battery can stop the upload partway, so charge it first.`);
  }
  if (faceInstall.info && !faceInstall.info.preview) warnings.push('This file has no preview, so it can’t be checked against this watch’s screen.');
  return warnings;
}

$('face-cancel').addEventListener('click', () => {
  faceInstall.phase = 'idle';
  faceInstall.file = null;
  faceInstall.info = null;
  renderFaceInstall();
  $('face-pick').focus();
});

$('face-go').addEventListener('click', () => {
  faceInstall.unknownConfirmed = Boolean(faceInstall.info && !faceInstall.info.preview);
  startFaceInstall();
});

function readDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener('load', () => resolve(reader.result));
    reader.addEventListener('error', () => reject(reader.error));
    reader.readAsDataURL(file);
  });
}

async function startFaceInstall() {
  const file = faceInstall.file;
  if (!file) return;
  faceInstall.phase = 'sending';
  faceInstall.error = '';
  setStatusLine('faces-status', '');
  renderFaceInstall();
  renderFaces();
  try {
    let data;
    try {
      data = await readDataUrl(file);
    } catch {
      throw new ApiError('Couldn’t read that file. Pick it again.', 400);
    }
    const result = await api('watch-faces/upload', 'POST', { data }, { timeout: 60000 });
    if (Array.isArray(result.faces)) faces.data = result;
    if (result.install && typeof result.install === 'object') {
      faceInstall.watched = result.install.started_at;
      const s = featureStatus('watch_faces');
      if (s) s.install = result.install;
    }
  } catch (error) {
    if (error.status !== 401) faceInstall.error = installErrorText(error);
  } finally {
    faceInstall.phase = 'idle';
    faceInstall.file = null;
    renderFaces();
  }
}

function renderFaceInstall() {
  const block = $('face-install');
  const data = faces.data;
  const unsupported = Boolean(data && data.supported === false);
  block.hidden = unsupported;
  if (unsupported) return;

  const connected = watchConnected();
  const job = serverFaceInstall();
  const running = faceInstallRunning();
  if (job && (job.state === 'uploading' || job.state === 'activating')) faceInstall.watched = job.started_at;
  const mine = Boolean(job && faceInstall.watched !== null && job.started_at === faceInstall.watched);

  // When this page's install ends, the face list has changed on the watch.
  const jobState = job ? `${job.started_at}:${job.state}` : '';
  if (faceInstall.lastState && jobState !== faceInstall.lastState && mine && (job.state === 'done' || job.state === 'failed')) loadFaces();
  faceInstall.lastState = jobState;

  const pick = $('face-pick');
  const confirming = faceInstall.phase === 'confirm';
  pick.hidden = confirming || running;
  pick.disabled = !connected || faces.busyId !== null;
  $('face-install-hint').textContent = connected
    ? 'Use a face made for this watch. Keep the watch charged and close by until it finishes.'
    : 'Connect the watch to install a face.';
  $('face-install-hint').hidden = confirming || running;

  // Second tap: low battery, or a file the server can't check against this screen.
  $('face-confirm').hidden = !confirming;
  if (confirming && faceInstall.info) {
    const info = faceInstall.info;
    $('face-confirm-file').textContent = `${info.name || info.fileName} · ${fileSizeText(info.size)}`;
    $('face-warnings').replaceChildren(...faceInstallWarnings().map((text) => {
      const item = element('li', 'face-warning');
      item.append(svgIcon('alert'), element('span', '', text));
      return item;
    }));
    $('face-go').disabled = !connected || faces.busyId !== null;
  }

  const progress = $('face-progress');
  progress.hidden = !running;
  const bar = $('face-bar');
  if (running) {
    const name = faceDisplayName(job && faceInstall.phase !== 'sending' ? job : null);
    let label = `Starting ${name}…`;
    let percent = null;
    if (faceInstall.phase !== 'sending' && job.state === 'uploading') {
      percent = isNum(job.percent) ? Math.max(0, Math.min(100, Math.round(job.percent))) : 0;
      label = `Uploading ${name}… ${percent}%`;
    } else if (faceInstall.phase !== 'sending') {
      label = `Switching the watch to ${name}…`;
    }
    $('face-progress-label').textContent = label;
    bar.classList.toggle('indeterminate', percent === null);
    if (percent === null) bar.removeAttribute('aria-valuenow');
    else bar.setAttribute('aria-valuenow', String(percent));
    $('face-bar-fill').style.transform = percent === null ? '' : `scaleX(${percent / 100})`;
  }

  const result = $('face-result');
  let text = '';
  let tone = '';
  if (faceInstall.error && !running) {
    text = faceInstall.error;
    tone = 'error';
  } else if (mine && job.state === 'done') {
    const name = faceDisplayName(job);
    if (job.verified && job.active) text = `${name} is on the watch now.`;
    else if (job.verified) text = `${name} is installed. Tap Use to switch to it.`;
    else text = `${name} was sent, but the watch didn’t confirm it. Tap Refresh to check.`;
    tone = job.verified ? 'ok' : 'warn';
    if (job.compatibility === 'unknown' && !faceInstall.unknownConfirmed) text += ' It had no preview, so it wasn’t checked against this screen.';
  } else if (mine && job.state === 'failed') {
    const battery = watchBattery();
    const low = battery && battery.level < 20 && battery.charging !== true;
    text = `Couldn’t install ${faceDisplayName(job)}. ${sentence(job.error) || 'The upload stopped.'} ${low ? 'Charge the watch, then try again.' : 'Try again.'}`;
    tone = 'error';
  }
  result.className = 'install-result' + (tone ? ' ' + tone : '');
  result.replaceChildren();
  if (text) {
    result.append(svgIcon(tone === 'ok' ? 'check' : 'alert'), element('span', '', text));
  }

  clearTimeout(faceInstall.pollTimer);
  if (running && faceInstall.phase !== 'sending' && view.current === 'settings') {
    faceInstall.pollTimer = setTimeout(() => {
      if (!document.hidden && canPoll()) quietRefresh();
    }, 1000);
  }
}

/* ---------- Settings: editable lists (quick replies, contacts) ---------- */

function moveButton(icon, label, disabled, onClick) {
  const button = element('button', 'icon-button small');
  button.type = 'button';
  button.append(svgIcon(icon));
  button.setAttribute('aria-label', label);
  button.disabled = disabled;
  button.addEventListener('click', onClick);
  return button;
}

/** Re-renders a list, then puts focus back on the same control of the row that moved. */
function focusRowControl(listId, index, selector) {
  const row = $(listId).children[index];
  const target = row && (row.querySelector(selector + ':not(:disabled)') || row.querySelector('input'));
  if (target) target.focus();
}

/* ---------- Settings: quick replies ---------- */

const QR_FALLBACK_MAX = 30;
const qr = { data: null, error: null, rows: [], dirty: false, saving: false, pollUntil: 0, pollTimer: 0, rowErrors: {} };

async function loadQuickReplies() {
  try {
    qr.data = await api('quick-replies');
    qr.error = null;
    if (!qr.dirty) qr.rows = [...(qr.data.replies || [])];
  } catch (error) {
    if (error.status === 401) return;
    qr.error = error;
  }
  renderQuickReplies();
}

function qrLimits() {
  const data = qr.data || {};
  const max = Math.min(isNum(data.max) && data.max > 0 ? data.max : 0, 16);
  return { max, chars: isNum(data.reply_max_chars) ? data.reply_max_chars : QR_FALLBACK_MAX };
}

const QR_STATES = {
  pending: ['busy', 'Checking…'],
  verified: ['ok', 'Verified on the watch'],
  mismatch: ['error', 'Mismatch: the watch has different replies'],
  no_reply: ['warn', 'No reply from the watch'],
  rejected: ['error', 'The watch rejected them'],
  disconnected: ['warn', 'Watch disconnected before it confirmed'],
};

function renderQuickReplies() {
  const data = qr.data;
  const connected = watchConnected();
  if (!data) {
    setNote('qr-note', qr.error ? `Couldn’t load quick replies. ${errorText(qr.error)}` : 'Loading…', qr.error ? 'error' : '');
    $('qr-list').replaceChildren();
    $('qr-add').hidden = true;
    $('qr-save').disabled = true;
    return;
  }
  const { max, chars } = qrLimits();
  const writable = Boolean(data.writable) && connected;
  let note = '';
  let tone = '';
  if (data.supported === false) {
    note = 'This watch doesn’t accept quick replies.';
    tone = 'warn';
  } else if (!connected) note = data.read_at ? `Watch not connected. These are the replies it had ${relativeTime(data.read_at)}.` : 'Connect the watch to see its quick replies.';
  else if (!data.writable) note = 'No reply from the watch yet. Editing opens once it reports its replies.';
  setNote('qr-note', note, tone);

  $('qr-count').textContent = max ? `${qr.rows.length} of ${max}` : '';
  const write = data.write && QR_STATES[data.write.state];
  const stateNode = $('qr-state');
  stateNode.replaceChildren();
  stateNode.className = 'check-state' + (write ? ' ' + write[0] : '');
  if (write) {
    if (write[0] === 'busy') stateNode.append(element('span', 'spinner'));
    if (write[0] === 'ok') stateNode.append(svgIcon('check'));
    stateNode.append(document.createTextNode(write[1]));
  }
  setSummary('qr-sum', data.supported === false ? 'Not on this watch' : data.read_at || qr.rows.length ? `${(data.replies || []).length} ${(data.replies || []).length === 1 ? 'reply' : 'replies'}${write ? ' · ' + write[1].split(':')[0] : ''}` : connected ? 'Waiting for the watch' : 'Watch not connected');

  const keep = document.activeElement && document.activeElement.closest ? document.activeElement.closest('#qr-list') : null;
  if (!keep) {
    $('qr-list').replaceChildren(
      ...qr.rows.map((text, i) => {
        const row = element('li', 'edit-row');
        const field = element('div', 'field');
        const labelRow = element('div', 'label-row');
        const label = element('label', '', `Reply ${i + 1}`);
        label.htmlFor = 'qr-' + i;
        const count = element('span', 'counter', `${codePoints(text).length}/${chars}`);
        count.id = `qr-${i}-count`;
        if (codePoints(text).length > chars) count.classList.add('over');
        labelRow.append(label, count);
        const input = element('input');
        input.id = 'qr-' + i;
        input.value = text;
        input.dataset.index = i;
        input.autocomplete = 'off';
        input.enterKeyHint = 'done';
        input.disabled = !writable || qr.saving;
        input.setAttribute('aria-describedby', count.id + (qr.rowErrors[i] ? ` qr-${i}-error` : ''));
        if (qr.rowErrors[i]) input.setAttribute('aria-invalid', 'true');
        field.append(labelRow, input);
        if (qr.rowErrors[i]) {
          const error = element('p', 'field-error', qr.rowErrors[i]);
          error.id = `qr-${i}-error`;
          field.append(error);
        }
        const tools = element('div', 'row-tools');
        const locked = !writable || qr.saving;
        tools.append(
          moveButton('up', `Move reply ${i + 1} up`, locked || i === 0, () => qrMove(i, -1)),
          moveButton('down', `Move reply ${i + 1} down`, locked || i === qr.rows.length - 1, () => qrMove(i, 1)),
          moveButton('trash', `Remove reply ${i + 1}`, locked, () => qrRemove(i)),
        );
        // Read-only while the watch can't take a write: no controls that would only be disabled.
        if (writable) row.append(field, tools);
        else row.append(field);
        return row;
      }),
    );
  }
  if (!qr.rows.length && data.read_at && data.supported !== false) $('qr-list').replaceChildren(element('li', 'empty-note', 'No quick replies on the watch.'));
  $('qr-add').hidden = !writable;
  $('qr-add').disabled = qr.saving || (max > 0 && qr.rows.length >= max);
  $('qr-save').disabled = !writable || qr.saving || !qr.dirty;
  $('qr-save').hidden = !writable && !qr.saving;
}

function qrEdited() {
  qr.dirty = true;
  qr.rowErrors = {};
  setStatusLine('qr-status', '');
  renderQuickReplies();
}

function qrMove(i, by) {
  const j = i + by;
  [qr.rows[i], qr.rows[j]] = [qr.rows[j], qr.rows[i]];
  qr.dirty = true;
  qr.rowErrors = {};
  document.activeElement.blur();
  renderQuickReplies();
  focusRowControl('qr-list', j, `[aria-label^="Move reply ${j + 1} ${by < 0 ? 'up' : 'down'}"]`);
}

function qrRemove(i) {
  qr.rows.splice(i, 1);
  document.activeElement.blur();
  qrEdited();
  focusRowControl('qr-list', Math.min(i, qr.rows.length - 1), 'input');
}

$('qr-list').addEventListener('input', (event) => {
  const i = Number(event.target.dataset.index);
  if (!Number.isInteger(i)) return;
  qr.rows[i] = event.target.value;
  qr.dirty = true;
  const { chars } = qrLimits();
  const count = $(`qr-${i}-count`);
  count.textContent = `${codePoints(event.target.value).length}/${chars}`;
  count.classList.toggle('over', codePoints(event.target.value).length > chars);
  $('qr-save').disabled = !(qr.data && qr.data.writable) || qr.saving;
  setStatusLine('qr-status', '');
});

$('qr-add').addEventListener('click', () => {
  qr.rows.push('');
  document.activeElement.blur();
  qrEdited();
  focusRowControl('qr-list', qr.rows.length - 1, 'input');
});

$('qr-save').addEventListener('click', async () => {
  const { chars } = qrLimits();
  const replies = qr.rows.map((r) => r.trim());
  qr.rowErrors = {};
  replies.forEach((text, i) => {
    const n = codePoints(text).length;
    if (!n) qr.rowErrors[i] = 'Write a reply or remove this row.';
    else if (n > chars) qr.rowErrors[i] = `Use ${chars} characters or fewer.`;
    else if (/[\u0000-\u001f]/.test(text)) qr.rowErrors[i] = 'Keep it on one line.';
  });
  if (Object.keys(qr.rowErrors).length) {
    document.activeElement.blur();
    renderQuickReplies();
    focusRowControl('qr-list', Number(Object.keys(qr.rowErrors)[0]), 'input');
    return;
  }
  qr.saving = true;
  document.activeElement.blur();
  const restore = busyButton($('qr-save'), 'Sending…');
  renderQuickReplies();
  try {
    qr.data = await api('quick-replies', 'PUT', { replies }, { timeout: 20000 });
    qr.rows = replies;
    qr.dirty = false;
    setStatusLine('qr-status', '');
    pollQuickReplies(Date.now() + 15000);
  } catch (error) {
    if (error.status === 400) {
      const match = /^Reply (\d+): (.*)$/.exec(error.message || '');
      if (match) qr.rowErrors[Number(match[1]) - 1] = sentence(match[2]);
      else setStatusLine('qr-status', errorText(error), 'error');
    } else if (error.status !== 401) setStatusLine('qr-status', errorText(error), 'error');
  } finally {
    restore();
    qr.saving = false;
    renderQuickReplies();
  }
});

/** After a save the watch's read-back decides Verified or Mismatch; check until it does. */
function pollQuickReplies(until) {
  clearTimeout(qr.pollTimer);
  if (!qr.data || !qr.data.write || qr.data.write.state !== 'pending' || Date.now() > until) return;
  qr.pollTimer = setTimeout(async () => {
    try {
      const data = await api('quick-replies');
      qr.data = data;
      if (!qr.dirty) qr.rows = [...(data.replies || [])];
      renderQuickReplies();
    } catch {
      /* Try again on the next tick. */
    }
    pollQuickReplies(until);
  }, 1500);
}

/* ---------- Settings: favourite contacts ---------- */

const CONTACT_NAME_MAX = 32;
const contacts = { data: null, error: null, rows: [], dirty: false, saving: false, rowErrors: {} };

async function loadContacts() {
  try {
    contacts.data = await api('contacts');
    contacts.error = null;
    if (!contacts.dirty) contacts.rows = (contacts.data.contacts || []).map((c) => ({ name: c.name, number: c.number }));
  } catch (error) {
    if (error.status === 401) return;
    contacts.error = error;
  }
  renderContacts();
}

function renderContacts() {
  const data = contacts.data;
  const connected = watchConnected();
  if (!data) {
    setNote('contacts-note', contacts.error ? `Couldn’t load contacts. ${errorText(contacts.error)}` : 'Loading…', contacts.error ? 'error' : '');
    $('contact-list').replaceChildren();
    $('contact-add').hidden = true;
    $('contacts-save').disabled = true;
    return;
  }
  const max = isNum(data.max) ? data.max : 10;
  let note = '';
  let tone = '';
  if (data.supported === false) {
    note = 'The watch rejected the last list it was sent.';
    tone = 'warn';
  } else if (!connected) note = 'Watch not connected. Changes save here and sync when it connects.';
  setNote('contacts-note', note, tone);
  const saved = (data.contacts || []).length;
  // Only a count here: names and numbers stay inside this editor.
  setSummary('contacts-sum', data.configured ? `${saved} ${saved === 1 ? 'contact' : 'contacts'}` : 'Not set up');

  const keep = document.activeElement && document.activeElement.closest ? document.activeElement.closest('#contact-list') : null;
  if (!keep) {
    $('contact-list').replaceChildren(
      ...contacts.rows.map((contact, i) => {
        const row = element('li', 'edit-row contact-row');
        const fields = element('div', 'contact-fields');
        for (const [key, label, type] of [['name', 'Name', 'text'], ['number', 'Phone number', 'tel']]) {
          const field = element('div', 'field');
          const labelNode = element('label', '', `${label}`);
          const id = `contact-${i}-${key}`;
          labelNode.htmlFor = id;
          const input = element('input');
          input.id = id;
          input.type = type;
          input.value = contact[key];
          input.dataset.index = i;
          input.dataset.key = key;
          input.autocomplete = 'off';
          input.disabled = contacts.saving;
          if (key === 'name') input.maxLength = CONTACT_NAME_MAX;
          else input.inputMode = 'tel';
          const error = contacts.rowErrors[i] && contacts.rowErrors[i][key];
          if (error) {
            input.setAttribute('aria-invalid', 'true');
            input.setAttribute('aria-describedby', id + '-error');
          }
          field.append(labelNode, input);
          if (error) {
            const node = element('p', 'field-error', error);
            node.id = id + '-error';
            field.append(node);
          }
          fields.append(field);
        }
        const tools = element('div', 'row-tools');
        tools.append(
          element('span', 'row-index', `${i + 1}`),
          moveButton('up', `Move contact ${i + 1} up`, contacts.saving || i === 0, () => contactMove(i, -1)),
          moveButton('down', `Move contact ${i + 1} down`, contacts.saving || i === contacts.rows.length - 1, () => contactMove(i, 1)),
          moveButton('trash', `Remove contact ${i + 1}`, contacts.saving, () => contactRemove(i)),
        );
        row.append(tools, fields);
        return row;
      }),
    );
    if (!contacts.rows.length) $('contact-list').replaceChildren(element('li', 'empty-note', 'No favourite contacts yet.'));
  }
  $('contact-add').hidden = false;
  $('contact-add').disabled = contacts.saving || contacts.rows.length >= max;
  $('contact-add').querySelector('span').textContent = contacts.rows.length >= max ? `The watch holds ${max}` : 'Add contact';
  $('contacts-save').disabled = contacts.saving || !contacts.dirty;
}

function contactsEdited() {
  contacts.dirty = true;
  contacts.rowErrors = {};
  setStatusLine('contacts-status', '');
  renderContacts();
}

function contactMove(i, by) {
  const j = i + by;
  [contacts.rows[i], contacts.rows[j]] = [contacts.rows[j], contacts.rows[i]];
  document.activeElement.blur();
  contactsEdited();
  focusRowControl('contact-list', j, `[aria-label^="Move contact ${j + 1} ${by < 0 ? 'up' : 'down'}"]`);
}

function contactRemove(i) {
  contacts.rows.splice(i, 1);
  document.activeElement.blur();
  contactsEdited();
  if (contacts.rows.length) focusRowControl('contact-list', Math.min(i, contacts.rows.length - 1), 'input');
  else $('contact-add').focus();
}

$('contact-list').addEventListener('input', (event) => {
  const i = Number(event.target.dataset.index);
  const key = event.target.dataset.key;
  if (!Number.isInteger(i) || !key) return;
  contacts.rows[i][key] = event.target.value;
  contacts.dirty = true;
  $('contacts-save').disabled = contacts.saving;
  setStatusLine('contacts-status', '');
});

$('contact-add').addEventListener('click', () => {
  contacts.rows.push({ name: '', number: '' });
  document.activeElement.blur();
  contactsEdited();
  focusRowControl('contact-list', contacts.rows.length - 1, 'input');
});

function validateContacts(list) {
  const errors = {};
  const seen = new Set();
  list.forEach((c, i) => {
    const e = {};
    const n = codePoints(c.name).length;
    const digits = c.number.replace(/\D/g, '');
    if (!n) e.name = 'Enter a name.';
    else if (n > CONTACT_NAME_MAX) e.name = `Use ${CONTACT_NAME_MAX} characters or fewer.`;
    if (!c.number) e.number = 'Enter a number.';
    else if (!/^\+?[0-9][0-9 ]*$/.test(c.number) || c.number.length > 32 || digits.length > 20) e.number = 'Use digits and spaces, with an optional + at the start.';
    else if (seen.has(digits)) e.number = 'This number is already in the list.';
    seen.add(digits);
    if (Object.keys(e).length) errors[i] = e;
  });
  return errors;
}

$('contacts-save').addEventListener('click', async () => {
  const list = contacts.rows.map((c) => ({ name: c.name.trim(), number: c.number.trim() }));
  contacts.rowErrors = validateContacts(list);
  const first = Object.keys(contacts.rowErrors)[0];
  if (first !== undefined) {
    document.activeElement.blur();
    renderContacts();
    const bad = $(`contact-${first}-${contacts.rowErrors[first].name ? 'name' : 'number'}`);
    if (bad) bad.focus();
    return;
  }
  contacts.saving = true;
  document.activeElement.blur();
  const restore = busyButton($('contacts-save'), 'Saving…');
  renderContacts();
  try {
    const result = await api('contacts', 'PUT', { contacts: list }, { timeout: 20000 });
    contacts.data = result;
    contacts.rows = (result.contacts || []).map((c) => ({ name: c.name, number: c.number }));
    contacts.dirty = false;
    setStatusLine('contacts-status', result.sent_to_watch ? 'Saved and sent to the watch' : 'Saved. Will sync when the watch connects.', 'ok');
  } catch (error) {
    if (error.status === 400) {
      const match = /^Contact (\d+): (.*)$/.exec(error.message || '');
      if (match) contacts.rowErrors[Number(match[1]) - 1] = { [/name/.test(match[2]) ? 'name' : 'number']: sentence(match[2]) };
      else setStatusLine('contacts-status', errorText(error), 'error');
    } else if (error.status !== 401) setStatusLine('contacts-status', errorText(error), 'error');
  } finally {
    restore();
    contacts.saving = false;
    renderContacts();
  }
});

/* ---------- Settings: music ---------- */

const music = { data: null, error: null, saving: false };

async function loadMusic() {
  try {
    music.data = await api('music');
    music.error = null;
  } catch (error) {
    if (error.status === 401) return;
    music.error = error;
  }
  renderMusic();
}

/** Never shows what is playing: track titles stay out of this page's status areas. */
function renderMusic() {
  const data = music.data;
  const toggle = $('music-enabled');
  const row = $('music-access');
  if (!data) {
    toggle.disabled = true;
    row.dataset.state = music.error ? 'error' : 'unknown';
    $('music-access-title').textContent = music.error ? 'Couldn’t load music settings' : 'Checking media access…';
    $('music-access-detail').textContent = music.error ? errorText(music.error) : '';
    $('music-access-open').hidden = true;
    return;
  }
  toggle.checked = Boolean(data.enabled);
  toggle.disabled = music.saving;
  const granted = data.access === 'granted';
  const denied = data.access === 'denied';
  row.dataset.state = granted ? 'ok' : 'warn';
  $('music-access-title').textContent = granted ? 'Media access is on' : denied ? 'Media access is off' : 'Media access not confirmed';
  $('music-access-detail').textContent = granted
    ? 'The watch can show and control what plays on the phone.'
    : denied
      ? 'Turn on notification access so the watch can control phone media.'
      : 'Media controls need notification access. If it’s on, play something to confirm.';
  const button = $('music-access-open');
  button.hidden = granted;
  button.textContent = hasNativeBridge() ? 'Open settings' : 'Open on phone';
  const connected = watchConnected();
  $('music-watch').textContent = !data.enabled
    ? 'Off: the watch shows nothing playing.'
    : data.supported === 'yes'
      ? 'The watch is using phone media.'
      : connected
        ? 'The watch hasn’t asked for media yet. Open the music screen on the watch.'
        : 'Watch not connected. Media shows up once it connects.';
  setSummary('music-sum', data.enabled ? (granted ? 'On' : denied ? 'On, access off' : 'On, access not confirmed') : 'Off');
}

$('music-enabled').addEventListener('change', async () => {
  // Read the new value before renderMusic puts the saved one back on the toggle.
  const enabled = $('music-enabled').checked;
  music.saving = true;
  renderMusic();
  // Keep the switch where the user put it while the save runs.
  $('music-enabled').checked = enabled;
  setStatusLine('music-status', 'Saving…');
  try {
    music.data = await api('music/settings', 'PUT', { enabled }, { timeout: 15000 });
    setStatusLine('music-status', 'Saved', 'ok');
  } catch (error) {
    if (error.status !== 401) setStatusLine('music-status', errorText(error), 'error');
  } finally {
    music.saving = false;
    renderMusic();
  }
});

// The same Android screen as notification access: media sessions are visible only with it.
$('music-access-open').addEventListener('click', () => $('notif-access-open').click());

/* ---------- Settings: Health Connect ---------- */

const HC_TYPES = {
  steps: 'steps', distance: 'distance', heart_rate: 'heart rate', resting_heart_rate: 'resting heart rate', spo2: 'SpO2',
  sleep: 'sleep', exercise: 'workouts', active_calories: 'workout calories',
};
const hc = { saving: false, enableAfterPrompt: false };

/** The export's status rides along in /status under health; a route's answer replaces it until the next poll. */
function hcStatus() {
  const health = featureStatus('health');
  return health && health.health_connect ? health.health_connect : null;
}

function applyHealthConnect(status) {
  if (state.status && state.status.health && status) state.status.health.health_connect = status;
  renderHealthConnect();
}

function renderHealthConnect() {
  const s = hcStatus();
  const toggle = $('hc-enabled');
  if (!s) {
    toggle.disabled = true;
    $('hc-access').hidden = true;
    $('hc-all').hidden = true;
    $('hc-now').hidden = true;
    $('hc-last').textContent = '';
    setNote('hc-note', state.status ? 'Health Connect export isn’t available in this version of the app.' : 'Loading…');
    setSummary('hc-sum', '');
    return;
  }
  const enabled = Boolean(s.enabled);
  const allowed = s.allowed || [];
  const missing = s.not_allowed || [];
  toggle.checked = enabled || hc.enableAfterPrompt;
  toggle.disabled = hc.saving || (Boolean(s.unavailable) && !enabled);

  const row = $('hc-access');
  // Access is asked when the export is turned on. While it is off the row shows only what is not
  // allowed: after two refusals Android stops showing the prompt, and Allow opens Health Connect itself.
  row.hidden = Boolean(s.unavailable) || (!enabled && !missing.length);
  row.dataset.state = missing.length ? 'warn' : 'ok';
  $('hc-access-title').textContent = !missing.length ? 'Health Connect access is on' : allowed.length ? 'Some data isn’t allowed' : 'Health Connect access is off';
  $('hc-access-detail').textContent = !missing.length
    ? 'Hypr Watch can write every type it exports. It never reads your Health Connect data.'
    : allowed.length
      ? `Not allowed: ${missing.map((k) => HC_TYPES[k] || k).join(', ')}.`
      : 'Allow Hypr Watch to write to Health Connect.';
  $('hc-allow').hidden = !missing.length;

  let note = '';
  let tone = '';
  if (s.unavailable) {
    note = sentence(s.unavailable);
    tone = 'warn';
  } else if (enabled && s.last_error) {
    note = `Last export failed: ${sentence(s.last_error)}`;
    tone = 'error';
  }
  setNote('hc-note', note, tone);

  let last = s.unavailable ? '' : 'Off: nothing is copied to Health Connect.';
  if (enabled && s.state === 'exporting') last = 'Exporting…';
  else if (enabled && s.last_success_at) {
    const records = s.last_written === 1 ? '1 record' : `${s.last_written} records`;
    last = `Last export ${relativeTime(s.last_success_at * 1000)}: ${records} written.`;
    if (s.pending_days) last += ` ${s.pending_days} ${s.pending_days === 1 ? 'day' : 'days'} still to go.`;
  } else if (enabled) last = 'Not exported yet. It runs after each sync, or tap Export now.';
  $('hc-last').textContent = last;
  $('hc-all').hidden = $('hc-now').hidden = !enabled || Boolean(s.unavailable);
  setSummary('hc-sum', !enabled ? 'Off' : s.last_error ? 'Not exporting' : s.state === 'exporting' ? 'Exporting' : 'On');
}

async function putHealthConnect(on) {
  hc.saving = true;
  renderHealthConnect();
  setStatusLine('hc-status', 'Saving…');
  try {
    applyHealthConnect(await api('health/connect', 'PUT', { enabled: on }, { timeout: 15000 }));
    setStatusLine('hc-status', on ? 'Export on. The first one copies every day.' : 'Export off', 'ok');
  } catch (error) {
    if (error.status !== 401) setStatusLine('hc-status', errorText(error), 'error');
  } finally {
    hc.saving = false;
    renderHealthConnect();
  }
}

/** On the phone the permission prompt comes first, and the export turns on once something is allowed. */
function askHealthConnect() {
  if (window.AndroidNotes && typeof window.AndroidNotes.requestHealthConnect === 'function') {
    window.AndroidNotes.requestHealthConnect();
    setStatusLine('hc-status', 'Choose what Hypr Watch may write in Health Connect.');
    return true;
  }
  return false;
}

$('hc-enabled').addEventListener('change', () => {
  const on = $('hc-enabled').checked;
  const s = hcStatus();
  clearTimeout(hc.promptTimer);
  hc.enableAfterPrompt = false;
  if (on && s && (s.not_allowed || []).length && askHealthConnect()) {
    hc.enableAfterPrompt = true;
    // If the answer never comes back (the page reloaded), the switch shows the real state again.
    hc.promptTimer = setTimeout(() => {
      hc.enableAfterPrompt = false;
      renderHealthConnect();
    }, 120000);
    return;
  }
  if (!on && s && !s.enabled) {
    renderHealthConnect();
    return;
  }
  putHealthConnect(on);
});

$('hc-allow').addEventListener('click', async () => {
  // Health Connect's screen for this app works even after Android stopped showing the prompt.
  if (window.AndroidNotes && typeof window.AndroidNotes.openHealthConnectSettings === 'function') {
    window.AndroidNotes.openHealthConnectSettings();
    const s = hcStatus();
    setStatusLine('hc-status', s && s.enabled ? 'Allow the data types in Health Connect, then come back.' : 'Allow the data types in Health Connect, then turn the export on.');
    return;
  }
  const restore = busyButton($('hc-allow'), 'Asking…');
  try {
    const result = await api('health/connect/permission', 'POST', {}, { timeout: 15000 });
    setStatusLine('hc-status', result.requested ? 'Allow access on the phone.' : 'Nothing more to allow.');
  } catch (error) {
    if (error.status !== 401) setStatusLine('hc-status', errorText(error), 'error');
  }
  restore();
});

/** Called by the app after the Health Connect permission screen closes. */
window.onHealthConnectPermission = (allowed) => {
  const enable = hc.enableAfterPrompt;
  hc.enableAfterPrompt = false;
  clearTimeout(hc.promptTimer);
  if (!allowed) {
    // Android may have skipped the prompt after earlier refusals; Allow opens Health Connect's own screen.
    setStatusLine('hc-status', 'Nothing allowed, so the export stays off. Tap Allow to choose in Health Connect.', 'error');
    renderHealthConnect();
    return;
  }
  const s = hcStatus();
  if (enable && !(s && s.enabled)) putHealthConnect(true);
  else {
    setStatusLine('hc-status', 'Access updated', 'ok');
    quietRefresh();
  }
};

async function exportHealthConnect(all) {
  const restore = busyButton($(all ? 'hc-all' : 'hc-now'), 'Starting…');
  setStatusLine('hc-status', '');
  try {
    const result = await api('health/connect/export', 'POST', { all }, { timeout: 15000 });
    applyHealthConnect(result.health_connect);
    setStatusLine('hc-status', all ? 'Copying every day again' : 'Export started', 'ok');
  } catch (error) {
    if (error.status !== 401) setStatusLine('hc-status', errorText(error), 'error');
  } finally {
    restore();
    renderHealthConnect();
  }
}

$('hc-now').addEventListener('click', () => exportHealthConnect(false));
$('hc-all').addEventListener('click', () => exportHealthConnect(true));

/* ---------- Settings: weather ---------- */

const WMO = {
  0: 'Clear', 1: 'Mostly clear', 2: 'Partly cloudy', 3: 'Overcast', 45: 'Fog', 48: 'Fog',
  51: 'Light drizzle', 53: 'Drizzle', 55: 'Heavy drizzle', 56: 'Freezing drizzle', 57: 'Freezing drizzle',
  61: 'Light rain', 63: 'Rain', 65: 'Heavy rain', 66: 'Freezing rain', 67: 'Freezing rain',
  71: 'Light snow', 73: 'Snow', 75: 'Heavy snow', 77: 'Snow grains',
  80: 'Showers', 81: 'Showers', 82: 'Heavy showers', 85: 'Snow showers', 86: 'Snow showers',
  95: 'Thunderstorm', 96: 'Thunderstorm with hail', 99: 'Thunderstorm with hail',
};
const NO_LOCATION_PATTERN = /no location/i;
const LOCATION_OFF_PATTERN = /location is off/i;

const weather = { data: null, error: null, mode: null, saving: false, formDirty: false };

async function loadWeather() {
  try {
    weather.data = await api('weather');
    weather.error = null;
  } catch (error) {
    if (error.status === 401) return;
    weather.error = error;
  }
  renderWeather();
}

function renderWeather() {
  const body = weather.data;
  const controls = $('weather-controls');
  if (!body) {
    setNote('weather-note', weather.error ? `Couldn’t load weather. ${errorText(weather.error)}` : 'Loading…', weather.error ? 'error' : '');
    $('weather-enabled').disabled = true;
    controls.hidden = true;
    $('weather-now').hidden = true;
    $('weather-refresh').hidden = true;
    return;
  }
  const s = body.status || {};
  const cfg = body.settings || {};
  const location = cfg.location || { mode: 'device' };
  const f = body.data;
  const enabled = Boolean(s.enabled);
  const connected = watchConnected();
  $('weather-enabled').checked = enabled;
  $('weather-enabled').disabled = weather.saving;
  controls.hidden = !enabled;
  $('weather-refresh').disabled = weather.saving || $('weather-refresh').classList.contains('is-busy');

  document.querySelectorAll('#weather-units button').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.units === (cfg.units || 'metric')));
    b.disabled = weather.saving;
  });
  const mode = weather.mode || location.mode;
  document.querySelectorAll('#weather-where button').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.mode === mode));
    b.disabled = weather.saving;
  });
  // A place being typed in isn't saved yet, so Refresh would fetch for the old one.
  $('weather-refresh').hidden = !enabled || (mode === 'manual' && location.mode !== 'manual');
  $('weather-device').hidden = mode !== 'device';
  $('weather-manual').hidden = mode !== 'manual';
  if (mode === 'manual' && !weather.formDirty) {
    $('wm-name').value = location.mode === 'manual' ? location.name || '' : '';
    $('wm-lat').value = location.mode === 'manual' && isNum(location.lat) ? String(location.lat) : '';
    $('wm-lon').value = location.mode === 'manual' && isNum(location.lon) ? String(location.lon) : '';
  }
  const locationOff = LOCATION_OFF_PATTERN.test(s.last_error || '');
  const noLocation = locationOff || NO_LOCATION_PATTERN.test(s.last_error || '');
  // Access is already allowed when Location is only off in the phone's settings.
  $('weather-allow').hidden = locationOff || !(mode === 'device' && (noLocation || !f));

  // Current conditions: only when on and fetched.
  const now = enabled && f && f.current ? f.current : null;
  $('weather-now').hidden = !now;
  if (now) {
    const unit = f.temperature_unit || '°';
    $('weather-temp').textContent = `${Math.round(now.temperature)}${unit}`;
    $('weather-cond').textContent = WMO[now.wmo_code] || 'Weather';
    const today = Array.isArray(f.daily) && f.daily[0];
    const parts = [];
    if (today && isNum(today.max) && isNum(today.min)) parts.push(`High ${Math.round(today.max)}°, low ${Math.round(today.min)}°`);
    parts.push(f.location_name || s.location_name || '');
    const fetched = Date.parse(f.fetched_at);
    if (Number.isFinite(fetched)) parts.push('updated ' + relativeTime(fetched));
    $('weather-meta').textContent = parts.filter(Boolean).join(' · ');
  }

  let note = '';
  let tone = '';
  if (enabled && s.last_error) {
    note = noLocation
      ? (mode !== 'device'
        ? 'Set a place to get a forecast.'
        : locationOff
          ? 'Location is off on the phone. Turn it on in the phone’s settings, or pick a place by hand.'
          : 'No location yet. Allow location, or pick a place by hand.')
      : `Last update failed: ${sentence(s.last_error)}` + (f ? ' Showing the last forecast.' : '');
    tone = noLocation ? 'warn' : 'error';
  } else if (enabled && !f) {
    note = 'No forecast yet. Tap Refresh to fetch one.';
  }
  setNote('weather-note', note, tone);

  $('weather-watch').textContent = !enabled
    ? 'Off: the phone sends no forecast.'
    : s.supported === true
      ? 'The watch accepts weather from this phone.'
      : s.supported === false
        ? 'The watch didn’t accept the forecast. It may not support weather from this app.'
        : connected
          ? 'Waiting for the watch to confirm the forecast.'
          : 'Watch not connected. The forecast goes over when it connects.';
  setSummary('weather-sum', !enabled ? 'Off' : now ? `${Math.round(now.temperature)}${f.temperature_unit || '°'}, ${(WMO[now.wmo_code] || '').toLowerCase()}` : s.last_error ? 'Not updating' : 'On');
}

async function putWeather(payload, statusText = 'Saved') {
  weather.saving = true;
  renderWeather();
  setStatusLine('weather-status', 'Saving…');
  try {
    weather.data = await api('weather/settings', 'PUT', payload, { timeout: 15000 });
    setStatusLine('weather-status', statusText, 'ok');
    return true;
  } catch (error) {
    if (error.status !== 401) setStatusLine('weather-status', errorText(error), 'error');
    return false;
  } finally {
    weather.saving = false;
    renderWeather();
  }
}

$('weather-enabled').addEventListener('change', () => putWeather({ enabled: $('weather-enabled').checked }, $('weather-enabled').checked ? 'Weather on' : 'Weather off'));

$('weather-units').addEventListener('click', (event) => {
  const button = event.target.closest('[data-units]');
  if (!button || button.getAttribute('aria-pressed') === 'true') return;
  putWeather({ units: button.dataset.units });
});

$('weather-where').addEventListener('click', async (event) => {
  const button = event.target.closest('[data-mode]');
  if (!button || button.getAttribute('aria-pressed') === 'true') return;
  if (button.dataset.mode === 'manual') {
    // Nothing changes until the place is saved.
    weather.mode = 'manual';
    weather.formDirty = false;
    renderWeather();
    $('wm-name').focus();
    return;
  }
  weather.mode = null;
  const current = weather.data && weather.data.settings && weather.data.settings.location;
  if (current && current.mode === 'device') {
    renderWeather();
    return;
  }
  await putWeather({ location: { mode: 'device' } }, 'Using this phone’s location');
});

function weatherFieldError(id, text) {
  $(id + '-error').textContent = text;
  $(id + '-error').hidden = !text;
  $(id).setAttribute('aria-invalid', text ? 'true' : 'false');
}

$('weather-manual').addEventListener('input', () => {
  weather.formDirty = true;
});

$('weather-manual').addEventListener('submit', async (event) => {
  event.preventDefault();
  const name = $('wm-name').value.trim();
  const lat = Number($('wm-lat').value.trim().replace(',', '.'));
  const lon = Number($('wm-lon').value.trim().replace(',', '.'));
  const errors = {
    'wm-name': !name ? 'Enter a name for the place.' : codePoints(name).length > 32 ? 'Use 32 characters or fewer.' : '',
    'wm-lat': !$('wm-lat').value.trim() || !Number.isFinite(lat) || lat < -90 || lat > 90 ? 'Use a number from -90 to 90.' : '',
    'wm-lon': !$('wm-lon').value.trim() || !Number.isFinite(lon) || lon < -180 || lon > 180 ? 'Use a number from -180 to 180.' : '',
  };
  for (const [id, text] of Object.entries(errors)) weatherFieldError(id, text);
  const first = Object.keys(errors).find((id) => errors[id]);
  if (first) {
    $(first).focus();
    return;
  }
  const restore = busyButton($('wm-save'), 'Saving…');
  const ok = await putWeather({ location: { mode: 'manual', name, lat, lon } }, 'Place saved');
  restore();
  if (ok) {
    weather.mode = null;
    weather.formDirty = false;
    renderWeather();
  }
});

$('weather-refresh').addEventListener('click', async () => {
  const restore = busyButton($('weather-refresh'), 'Updating…');
  setStatusLine('weather-status', '');
  try {
    weather.data = await api('weather/refresh', 'POST', {}, { timeout: WATCH_WAIT_MS });
    setStatusLine('weather-status', watchConnected() ? 'Updated and sent to the watch' : 'Updated', 'ok');
  } catch (error) {
    if (error.status !== 401) setStatusLine('weather-status', errorText(error), 'error');
    loadWeather();
  } finally {
    restore();
    renderWeather();
  }
});

$('weather-allow').addEventListener('click', async () => {
  if (window.AndroidNotes && typeof window.AndroidNotes.requestLocation === 'function') {
    window.AndroidNotes.requestLocation();
    setStatusLine('weather-status', 'Answer the location prompt.');
    return;
  }
  const restore = busyButton($('weather-allow'), 'Asking…');
  try {
    const result = await api('weather/permission', 'POST', {}, { timeout: 15000 });
    if (result.location_available) {
      setStatusLine('weather-status', 'Location is available', 'ok');
      restore();
      $('weather-refresh').click();
      return;
    }
    setStatusLine('weather-status', result.requested ? 'Allow location on the phone, then tap Refresh.' : 'Allow location for Hypr Watch in Android settings, or pick a place.');
  } catch (error) {
    if (error.status !== 401) setStatusLine('weather-status', errorText(error), 'error');
  }
  restore();
});

/** Called by the app after the Android location prompt is answered. */
window.onLocationPermission = (granted) => {
  if (granted) {
    setStatusLine('weather-status', 'Location allowed. Updating…');
    api('weather/refresh', 'POST', {}, { timeout: WATCH_WAIT_MS }).then(
      (data) => {
        weather.data = data;
        setStatusLine('weather-status', 'Location allowed. Forecast updated.', 'ok');
        renderWeather();
      },
      (error) => {
        // Weather may still be off; the location is kept for when it's turned on.
        if (error.status === 400 && /weather is off/i.test(error.message || '')) setStatusLine('weather-status', 'Location allowed', 'ok');
        else if (error.status !== 401) setStatusLine('weather-status', errorText(error), 'error');
        loadWeather();
      },
    );
  } else {
    setStatusLine('weather-status', 'Location not allowed. You can pick a place instead.', 'error');
  }
};

/* ---------- Settings: backup and restore (phone only) ---------- */

// The backup holds the watch key, so only the phone's own UI can make or restore one; no API route does.
const backupUi = { busy: false, done: null };
const hasBackup = () => Boolean(window.AndroidNotes && typeof window.AndroidNotes.backup === 'function');

$('backup-group').hidden = !hasBackup();
$('sec-backup').hidden = !hasBackup();
setSummary('backup-sum', 'Encrypted with a passphrase');

/* ---------- Settings: appearance (phone only) ---------- */

// A PC browser has no bridge and follows its own OS; theme.js already applied the saved mode before first paint.
if (window.AndroidNotes && typeof window.AndroidNotes.setTheme === 'function') {
  const buttons = [...document.querySelectorAll('#appearance button')];
  const showMode = (mode) => buttons.forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.themeMode === mode)));
  showMode(document.documentElement.dataset.themeMode || 'system');
  for (const button of buttons) {
    button.addEventListener('click', () => {
      const mode = button.dataset.themeMode;
      window.applyTheme(mode);
      showMode(mode);
      window.AndroidNotes.setTheme(mode);
    });
  }
  $('sec-appearance').hidden = false;
}

/* ---------- Settings: agents on Wi-Fi (phone only) ---------- */

// Pairing and revoking belong to the phone's own screen; a PC browser over USB doesn't get the card.
const hasAgents = () => Boolean(window.AndroidNotes && typeof window.AndroidNotes.openBatterySettings === 'function');
const agents = { data: null, error: null, saving: false, armed: null, armTimer: 0, poll: 0 };
const pairSheet = $('pair-sheet');

async function loadAgents() {
  if (!hasAgents()) return;
  try {
    agents.data = await api('agents');
    agents.error = null;
  } catch (error) {
    if (error.status === 401) return;
    agents.error = error;
  }
  renderAgents();
  if (pairSheet.open) renderPairing();
}

function renderAgents() {
  const d = agents.data;
  const toggle = $('agents-enabled');
  const connect = $('agents-connect');
  if (!d) {
    setNote('agents-note', agents.error ? `Couldn’t load agent settings. ${errorText(agents.error)}` : '', 'error');
    toggle.disabled = true;
    connect.disabled = true;
    return;
  }
  const host = d.listening && d.listening.host;
  const count = d.agents.length;
  const paired = `${count} ${count === 1 ? 'computer' : 'computers'}`;
  setSummary('agents-sum', !d.enabled ? (count ? `Off · ${paired}` : 'Off') : host ? `On · ${paired}` : 'On · not on Wi-Fi');
  toggle.checked = d.enabled;
  toggle.disabled = agents.saving;
  $('agents-hint').textContent = !d.enabled ? 'Off' : host ? `Listening on ${host}` : 'Not on Wi-Fi';
  let note = '';
  if (agents.error) note = `Couldn’t refresh. ${errorText(agents.error)}`;
  else if (d.enabled && !d.listening) note = 'Hypr Watch couldn’t start listening. Turn the switch off and on again.';
  setNote('agents-note', note, 'error');
  connect.disabled = !host || agents.saving || connect.classList.contains('is-busy');

  const battery = $('agents-battery');
  battery.hidden = !d.enabled;
  battery.dataset.state = d.battery_exempt ? 'ok' : 'warn';
  $('agents-battery-title').textContent = d.battery_exempt ? 'Battery optimization is off' : 'Battery optimization is on';
  $('agents-battery-detail').textContent = d.battery_exempt
    ? 'Agents can reach the phone while it sleeps.'
    : 'Android may pause Hypr Watch while the phone sleeps, and agents can’t connect. Set Hypr Watch to Not optimized.';
  $('agents-battery-open').textContent = d.battery_exempt ? 'Manage' : 'Open settings';
  $('agents-battery-open').className = d.battery_exempt ? 'ghost' : 'secondary';

  $('agents-list').replaceChildren(...d.agents.map(agentRow));
  $('agents-empty').hidden = count > 0;
}

function agentMeta(agent) {
  const paired = `Paired ${dateFormat.format(agent.created_at)}`;
  if (!agent.last_seen_at) return `${paired} · Not used yet`;
  return `${paired} · Last used ${relativeTime(agent.last_seen_at)}${agent.last_ip ? ' from ' + agent.last_ip : ''}`;
}

function agentRow(agent) {
  const item = element('li', 'agent-row');
  const text = element('div', 'agent-text');
  text.append(element('p', 'face-name', agent.name), element('p', 'agent-meta', agentMeta(agent)));
  const armed = agents.armed === agent.id;
  const revoke = element('button', armed ? 'danger small' : 'ghost small delete', armed ? 'Tap again to revoke' : 'Revoke');
  revoke.type = 'button';
  revoke.setAttribute('aria-label', armed ? `Confirm: revoke ${agent.name}` : `Revoke ${agent.name}`);
  revoke.addEventListener('click', () => {
    if (agents.armed !== agent.id) {
      agents.armed = agent.id;
      clearTimeout(agents.armTimer);
      agents.armTimer = setTimeout(() => {
        agents.armed = null;
        renderAgents();
      }, 4000);
      renderAgents();
      const again = [...$('agents-list').querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === `Confirm: revoke ${agent.name}`);
      if (again) again.focus();
      return;
    }
    agents.armed = null;
    clearTimeout(agents.armTimer);
    revokeAgent(agent, revoke);
  });
  item.append(text, revoke);
  return item;
}

async function revokeAgent(agent, button) {
  const restore = busyButton(button, 'Revoking…');
  try {
    await api('agents/' + encodeURIComponent(agent.id), 'DELETE', {});
    notice(`${agent.name} can no longer connect`);
  } catch (error) {
    restore();
    if (error.status !== 401) setStatusLine('agents-status', `Couldn’t revoke ${agent.name}. ${errorText(error)}`, 'error');
  }
  await loadAgents();
}

$('agents-enabled').addEventListener('change', async () => {
  const on = $('agents-enabled').checked;
  agents.saving = true;
  setStatusLine('agents-status', '');
  renderAgents();
  try {
    agents.data = await api('agents/lan', 'PUT', { enabled: on });
    agents.error = null;
  } catch (error) {
    if (error.status !== 401) setStatusLine('agents-status', errorText(error), 'error');
  }
  agents.saving = false;
  renderAgents();
});

$('agents-battery-open').addEventListener('click', () => window.AndroidNotes.openBatterySettings());

// Back from Android's battery list, or after a while away: the exemption and "last used" may have changed.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && view.current === 'settings') loadAgents();
});

/* Pairing sheet: one code at a time, polled while it is open; closing it closes the code on the phone. */

async function openPairing() {
  const button = pairSheet.open ? $('pair-again') : $('agents-connect');
  const restore = busyButton(button, 'Getting a code…');
  try {
    const pairing = await api('agents/pairing', 'POST', {});
    agents.data = { ...agents.data, pairing, last_pairing: null };
    restore();
  } catch (error) {
    restore();
    if (error.status !== 401) setStatusLine('agents-status', errorText(error), 'error');
    renderAgents();
    return;
  }
  renderAgents();
  renderPairing();
  if (!pairSheet.open) {
    pairSheet.showModal();
    if (!(history.state && history.state.sheet === 'pair')) history.pushState({ view: 'settings', sheet: 'pair' }, '');
    $('pair-done').focus({ preventScroll: true });
  }
  clearInterval(agents.poll);
  agents.poll = setInterval(() => {
    renderPairing();
    loadAgents();
  }, 1000);
}

function renderPairing() {
  const d = agents.data || {};
  const pairing = d.pairing;
  const ended = d.last_pairing;
  let tone = 'busy';
  let title = 'Waiting for the computer';
  let detail = 'Keep this open until the computer says it’s paired.';
  let again = false;
  if (pairing) {
    const left = Math.max(0, Math.ceil((pairing.expires_at - Date.now()) / 1000));
    $('pair-code').textContent = pairing.code;
    $('pair-timer').textContent = `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    if (pairing.attempts_left < 5) {
      tone = 'warn';
      title = 'That code didn’t match';
      detail = `${pairing.attempts_left} ${pairing.attempts_left === 1 ? 'try' : 'tries'} left. Check the code on the computer and try again.`;
    }
  } else {
    $('pair-timer').textContent = '';
    again = Boolean(d.listening);
    if (ended && ended.result === 'paired') {
      tone = 'ok';
      title = `Paired with ${ended.name}`;
      detail = 'Its agents can use Hypr Watch over Wi-Fi now. You can revoke it in Settings at any time.';
      again = false;
    } else if (ended && ended.result === 'no_tries') {
      tone = 'error';
      title = 'Too many wrong tries';
      detail = 'This code no longer works. Get a new one to try again.';
    } else if (ended && ended.result === 'expired') {
      tone = 'error';
      title = 'The code expired';
      detail = 'Get a new code to try again.';
    } else {
      tone = 'error';
      title = 'Pairing stopped';
      detail = d.listening ? 'Get a new code to try again.' : 'Allow agents on Wi-Fi is off.';
    }
  }
  $('pair-code').classList.toggle('is-spent', !pairing);
  $('pair-state').dataset.state = tone;
  $('pair-state-title').textContent = title;
  $('pair-state-detail').textContent = detail;
  $('pair-again').hidden = !again;
  $('pair-done').textContent = pairing ? 'Cancel' : 'Done';
  // Cancelling is the way out, not the goal, so it stays quiet; Done after the outcome is the main action.
  $('pair-done').className = (pairing ? 'secondary' : 'primary') + ' save';
}

function closePairSheet() {
  if (history.state && history.state.sheet === 'pair') history.back();
  else if (pairSheet.open) pairSheet.close();
}

window.addEventListener('popstate', () => {
  if (pairSheet.open && !(history.state && history.state.sheet === 'pair')) pairSheet.close();
});

pairSheet.addEventListener('close', () => {
  clearInterval(agents.poll);
  agents.poll = 0;
  const open = agents.data && agents.data.pairing;
  if (open) agents.data = { ...agents.data, pairing: null };
  attempt(async () => {
    if (open) await api('agents/pairing', 'DELETE', {});
    await loadAgents();
  });
});
pairSheet.addEventListener('cancel', (event) => {
  event.preventDefault();
  closePairSheet();
});
pairSheet.addEventListener('click', (event) => {
  // A tap on the backdrop lands on the dialog itself.
  if (event.target === pairSheet) closePairSheet();
});
$('pair-close').addEventListener('click', closePairSheet);
$('pair-form').addEventListener('submit', (event) => {
  event.preventDefault();
  closePairSheet();
});
$('pair-again').addEventListener('click', openPairing);
$('agents-connect').addEventListener('click', openPairing);

if (hasAgents()) {
  $('agents-group').hidden = false;
  $('sec-agents').hidden = false;
}

function showRestoreConfirm(open) {
  $('restore-confirm').hidden = !open;
  $('restore-start').hidden = open;
  $('restore-hint').hidden = open;
  if (!open) return;
  $('rs-pass').value = '';
  setFieldError('rs-pass', '');
  setStatusLine('restore-status', '');
  $('rs-pass').focus();
}

/** Disables both actions while one runs; the native side answers through window.onBackupResult. */
function startBackupWork(button, label) {
  backupUi.busy = true;
  backupUi.done = busyButton(button, label);
  for (const id of ['backup-save', 'restore-start', 'restore-go', 'restore-cancel']) $(id).disabled = true;
}

$('backup-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (backupUi.busy) return;
  const pass = $('bk-pass').value;
  const errors = {
    'bk-pass': pass.length < 8 ? 'Use at least 8 characters.' : '',
    'bk-pass2': pass.length >= 8 && $('bk-pass2').value !== pass ? 'The passphrases don’t match.' : '',
  };
  for (const [id, text] of Object.entries(errors)) setFieldError(id, text);
  $('bk-pass-hint').hidden = Boolean(errors['bk-pass']);
  const first = Object.keys(errors).find((id) => errors[id]);
  if (first) {
    $(first).focus();
    return;
  }
  setStatusLine('backup-status', 'Choose where to save the backup.');
  startBackupWork($('backup-save'), 'Backing up…');
  window.AndroidNotes.backup(pass);
});

$('restore-start').addEventListener('click', () => showRestoreConfirm(true));

$('restore-cancel').addEventListener('click', () => {
  showRestoreConfirm(false);
  $('restore-start').focus();
});

$('restore-confirm').addEventListener('submit', (event) => {
  event.preventDefault();
  if (backupUi.busy) return;
  const pass = $('rs-pass').value;
  setFieldError('rs-pass', pass ? '' : 'Enter the passphrase the backup was made with.');
  if (!pass) {
    $('rs-pass').focus();
    return;
  }
  setStatusLine('restore-status', 'Choose the backup file.');
  startBackupWork($('restore-go'), 'Restoring…');
  window.AndroidNotes.restore(pass);
});

/** Called by the app when a backup or restore ends or its file picker closes; after a restore the page has reloaded. */
window.onBackupResult = (result) => {
  // No busy button: this page loaded after a restore, and the result may land on another tab.
  const reloaded = !backupUi.done;
  if (backupUi.done) backupUi.done();
  backupUi.done = null;
  backupUi.busy = false;
  for (const id of ['backup-save', 'restore-start', 'restore-go', 'restore-cancel']) $(id).disabled = false;
  if (!result) return;
  const id = result.kind === 'backup' ? 'backup-status' : 'restore-status';
  if (result.cancelled) {
    setStatusLine(id, '');
    return;
  }
  if (result.kind === 'backup') {
    if (result.ok) {
      $('bk-pass').value = '';
      $('bk-pass2').value = '';
    }
    setStatusLine(id, result.message, result.ok ? 'ok' : 'error');
    return;
  }
  if (reloaded) notice(result.message);
  if (result.ok) {
    showRestoreConfirm(false);
    setStatusLine(id, result.message, 'ok');
  } else if (!$('restore-confirm').hidden && /passphrase/i.test(result.message)) {
    setStatusLine(id, '');
    setFieldError('rs-pass', result.message);
    $('rs-pass').focus();
  } else {
    setStatusLine(id, result.message, 'error');
  }
};

renderLive();
renderHealthConnect();

/* ---------- Start ---------- */

loadIntoEditor(null);
showView(viewFromHistory(), { initial: true });
attempt(refresh);
