'use strict';

/*
 * Loaded blocking in <head>, before the stylesheet paints anything, so the page never flashes the
 * wrong theme. Sets html[data-theme] to "light" or "dark" from the app's Appearance setting
 * (AndroidNotes.getTheme(): "system", "light" or "dark"); a PC browser has no bridge and follows its OS.
 */
(() => {
  const root = document.documentElement;
  const systemDark = window.matchMedia('(prefers-color-scheme: dark)');
  let mode = 'system';
  try {
    if (window.AndroidNotes && typeof window.AndroidNotes.getTheme === 'function') mode = window.AndroidNotes.getTheme() || 'system';
  } catch {
    /* The bridge is optional; without it the page follows the system. */
  }
  // In the app, a forced Light or Dark also overrides the WebView's prefers-color-scheme until the
  // Activity relaunches, so System asks native for the real setting. An OS flip relaunches the Activity.
  const isSystemDark = () => {
    try {
      if (window.AndroidNotes && typeof window.AndroidNotes.systemDark === 'function') return window.AndroidNotes.systemDark();
    } catch {
      /* Fall back to the media query. */
    }
    return systemDark.matches;
  };
  window.applyTheme = (next) => {
    mode = next;
    root.dataset.themeMode = next;
    root.dataset.theme = next === 'system' ? (isSystemDark() ? 'dark' : 'light') : next;
  };
  window.applyTheme(mode);
  systemDark.addEventListener('change', () => {
    if (mode === 'system') window.applyTheme('system');
  });
})();
