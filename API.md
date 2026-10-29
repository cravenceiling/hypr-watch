# Local API

Base: `http://127.0.0.1:8766/api`. All endpoints require
`Authorization: Bearer <app-generated token>`. Use the client script to avoid
putting that token in shell arguments. Responses are JSON.

The phone app opens this page in its WebView, and hands it the token, only
once its own service has bound 127.0.0.1:8766. If another app holds that port,
the service cannot start its server and the app shows an error instead.

Paired computers can also reach part of this API over Wi-Fi, through a second,
TLS-only listener with per-computer tokens; see
[Agents on Wi-Fi](#agents-on-wi-fi).

| Method | Path | Operation |
|---|---|---|
| GET | `/status` | Watch, sync, health and capability status |
| GET | `/notes` | Full Android note list |
| POST | `/notes` | Create a note |
| GET | `/notes/{uuid}` | Read one note |
| PATCH | `/notes/{uuid}` | Update specified fields only |
| DELETE | `/notes/{uuid}` | Delete an unlinked full note |
| GET | `/export` | Export versioned full note data |
| POST | `/watch/connect` | Connect the Android bridge |
| POST | `/watch/disconnect` | Release the Bluetooth connection |
| GET | `/watch/reminders` | Read existing watch items and capacity |
| POST | `/watch/sync` | Queue a verified silent sync cycle |
| POST | `/health/sync` | Start a health file fetch |
| GET | `/health/files` | List stored raw files and their versions |
| GET | `/health/files/{id}[/{crc32}]` | Read one raw file version |
| GET | `/health/days` | Daily summaries |
| GET | `/health/series` | One metric in time buckets |
| GET | `/health/sleep` | Sleep sessions |
| GET | `/health/samples` | Manual spot measurements |
| GET | `/health/workouts` | Workouts |
| GET | `/health/parse-report` | Ingest result per file id |
| GET | `/health/profile` | Read the health profile |
| PUT | `/health/profile` | Save the profile and send it to the watch |
| GET | `/health/connect` | Health Connect export status |
| PUT | `/health/connect` | Turn the Health Connect export on or off |
| POST | `/health/connect/export` | Run the Health Connect export now |
| POST | `/health/connect/permission` | Ask for Health Connect access on the phone |
| GET | `/notifications` | Notification forwarding settings |
| PUT | `/notifications` | Replace the forwarding settings |
| POST | `/notifications/access` | Open Android's notification access screen |
| GET | `/agents` | Wi-Fi listener, pairing code and paired computers (loopback only) |
| PUT | `/agents/lan` | Turn the Wi-Fi listener on or off (loopback only) |
| POST | `/agents/pairing` | Open a pairing code (loopback only) |
| DELETE | `/agents/pairing` | Close the pairing code (loopback only) |
| DELETE | `/agents/{id}` | Revoke a paired computer (loopback only) |

Create/update fields: `title` (1 to 200 characters), `body` (up to 20,000
characters), `watch_title` (up to 20 Unicode code points), `watch_enabled`
(boolean), `done` (boolean). PATCH may include `if_updated_at` to reject an
outdated edit. Watch ids and fingerprints are managed by the bridge and
cannot be assigned by API callers. These ids are not Bluetooth credentials.

An authenticated diagnostic `/watch/probe-silent` operation accepts an existing
`note_id` and performs one bounded no-alert capability probe, recording its
intent and any cleanup. Normal clients use the verified sync operation.
The diagnostic is not exposed as an MCP tool.

HTTP 401 means invalid access token; 403 means an invalid Host/Origin;
400 means invalid input, including `{"error":"Invalid JSON"}` for a body that
is not a JSON object, a malformed request line, header or Content-Length, an
unknown operation and a missing note or file; 409 means another watch
operation is in progress (`Another watch operation is in progress`: watch
features run one at a time each and two at once in all; retry when it
finishes); 411 means a chunked body (send Content-Length); 413 means a body
over 100,000 bytes; 503 means an unavailable/interrupted operation. Errors are
`{"error": "..."}`.
A queued sync response is not a completion acknowledgement. Inspect `/status`
and each note's `watch_state`; normal successful state is `synced`.
`watch_full`, `needs_watch_title` and `watch_changed` require user attention.
`watch_changed` prevents overwriting a reminder whose recorded identity changed.

## Status and connection

`/status` returns `watch`, `sync`, `health`, `notes_count`,
`watch_title_limit` (20), `silent_notes_supported`, `silent_notes_status` and
`connection`.

`watch.state` is `disconnected`, `connecting`, `connected` or `error`. It reads
`connecting` while the watch restarts its session on an open link, until it
authenticates again. Other `watch` fields: `error`, `stage`, `frames_received`,
`link_acks`, `session_config`, `last_response_ms`, `transport`, and the
reconnect fields below.

`/watch/connect` waits up to 30 seconds and returns `{watch}`. A failed connect
still answers 200, with `state: error` and the reason in `error`.

Reconnect: a `/watch/connect` that authenticates makes the app own the watch,
and `/watch/disconnect` releases it; the app keeps that across restarts. A
failed connect leaves ownership as it was. A restore's restart connects (and
so claims) only when the app owns the watch or never released it. While it
owns the watch it reconnects by itself: when the service starts (also after a
reboot or an app update, or Android restarting a killed service), when phone
Bluetooth turns on, when the watch is paired, when Android links to the paired
watch, and after a drop or failed attempt, 5 s, 15 s, then 60 s later, then
every 5 minutes (Doze can stretch a wait to 9 minutes or more). A link to the
watch within 15 s of an attempt's end is likely that attempt's own: it tries
only if the link is still up when those 15 s end, and the backoff timer is not
due first. A later one tries at once. Neither starts the backoff over.
Bluetooth off or no paired watch waits for Bluetooth to turn on or the watch to
pair or link instead of retrying. A missing, invalid or rejected key stops
reconnecting until the next `/watch/connect` or service start; a watch that
rejects the key reads `error: Watch rejected authentication`, and only a new
key from pairing again helps. A `/watch/connect` while an attempt runs waits
for that attempt. After a release nothing reconnects, so Mi Fitness can take
the watch. `watch` fields:
`auto_reconnect` (the app owns the watch), `next_attempt_at` (epoch ms, 0 when
none is scheduled), `reconnect_waiting_for_phone` (true while waiting for
Bluetooth, the watch or a new key), `reconnect_needs_key` (true while
reconnecting waits for a new key), `last_drop` (why the last connected link went
down, `""` when never) and `last_drop_at` (epoch ms, 0 when never). The
last drop survives restarts. Key problems
read `Watch key not configured: add files/watch.json with auth_key set to the
watch's 32-digit hex key` or `Watch key in files/watch.json is invalid:
auth_key must be 32 hex digits`. Neither message quotes the file. An `0x`
prefix on `auth_key` is accepted.

`health` fields: `state` (`idle`, `listing`, `fetching`, `acking`: stored
files' acks wait for room in the send window, or `waiting`: the next file
request does), `message`, `total`,
`fetched`, `new_versions`, `failed`, `already_stored`, `deferred`,
`last_started_at`, `last_success_at`, `last_error`, `last_error_at`,
`current` (file id, only while fetching), `files_stored` (distinct ids) and
`ingest` `{pending, last_error, last_error_at}`. `pending` counts file ids
with a version not yet ingested, including ids a storage error left for the
next pass. Times are epoch milliseconds, 0 when never; ingest error times are
epoch seconds or `null`. `health_connect` is the export's status, as below.

## Health

Health reads take `from` and `to` as inclusive `YYYY-MM-DD` dates, at most 400
days. A `date` is the watch's local day from the file's timezone. Times are
epoch seconds UTC, except a raw file's `first_seen` and `last_seen`, which are
epoch milliseconds. `tz_offset` is seconds. Missing values are `null`, never 0.
Rows are oldest first. Unknown or repeated query parameters are rejected.

| Method | Path | Params | Response |
|---|---|---|---|
| POST | `/health/sync` | none | `{started, health}`; `health` as in `/status`; `started` is false while a fetch runs. 400 `Connect the watch first` |
| GET | `/health/files` | none | `{files: [{id, versions: [{crc32, size, first_seen, last_seen, complete}]}]}` |
| GET | `/health/files/{id}[/{crc32}]` | 14 hex digits; 8 to 12 | `{id, crc32, size, first_seen, last_seen, complete, raw_base64}`; newest version without `crc32` |
| GET | `/health/days` | `from`, `to` | `{days: [{date, steps, active_calories, calories, distance_m, resting_hr, avg_hr, min_hr, max_hr, avg_spo2, min_spo2, avg_stress, max_stress, standing_hours, sleep_minutes, deep_minutes, light_minutes, rem_minutes, awake_minutes}]}` |
| GET | `/health/series` | `metric` (`steps`, `heart_rate`, `spo2`, `stress`), `from`, `to`, `bucket` (1, 5, 15 or 60 minutes; default 15) | `{metric, bucket, points: [{t, value}]}` |
| GET | `/health/sleep` | `from`, `to` | `{sessions: [{bed_time, wakeup_time, tz_offset, finished, total_minutes, deep_minutes, light_minutes, rem_minutes, awake_minutes, quality, stages: [{start, kind, minutes}]}]}` |
| GET | `/health/samples` | `from`, `to` | `{samples: [{t, kind, value}]}` |
| GET | `/health/workouts` | `from`, `to` | `{workouts: [{start, end, tz_offset, sport, active_seconds, calories, avg_hr, max_hr, min_hr, steps, distance_m}]}` |
| GET | `/health/parse-report` | none | `{parser_version, files: [{id, crc32, type, subtype, detail, version, status, reason, parser_version}]}` |
| GET | `/health/profile` | none | `{configured, profile}`; `profile` is `null` until saved |
| PUT | `/health/profile` | body below | `{configured: true, profile, sent_to_watch}` |

`/health/days` returns one object per day with any data. Daily summary file
values win field by field, except `avg_hr`, `avg_spo2` and `avg_stress`, which
are the minute-sample means when the day has any (the watch's summary averages
are unreliable); minute samples fill gaps, and sleep totals come from nights
that ended that day. `/health/series` sums steps per bucket and averages
other metrics to one decimal. Buckets start on the watch's local clock. Empty
buckets are left out, and a request that could exceed 40,000 points is
rejected. Sleep stage `kind` is `awake`, `light`, `deep` or `rem`. Sample
`kind` is `heart_rate`, `spo2`, `stress` or `temperature`; the temperature
unit is unverified.
Workout `sport` is a lowercase sport name. Result caps: 2,000 sleep sessions,
50,000 samples, 5,000 workouts. A larger range is rejected with 400.
Parse-report `status` is `parsed`, `unsupported` or `invalid`; a parser
error is `invalid` with reason `Parser failed: ...`.
A version's `crc32` is its key: the file's CRC-32 as 8 hex digits, or, for a
version whose bytes differ from an earlier one with the same CRC-32 (damaged
files can share one), that CRC-32 plus n * 2^32, so 9 or more digits.

### Health Connect export

Android 14 and later only. `GET /health/connect` and `health.health_connect`
in `/status` return `{enabled, unavailable, state, allowed, not_allowed,
pending_days, last_run_at, last_success_at, last_error, last_error_at,
last_days, last_written, last_unchanged, last_deleted, last_rejected}`. `unavailable` is
`null` or why Health Connect cannot be used (`Health Connect export needs
Android 14 or later`). `state` is `off`, `idle` or `exporting`. `allowed` and
`not_allowed` list types: `steps`, `distance`, `heart_rate`,
`resting_heart_rate`, `spo2`, `sleep`, `exercise`, `active_calories`.
`pending_days` counts days an interrupted run left; they are tried again 15
minutes later. The `last_*` counts are from the last run; `last_rejected`
counts records Health Connect refused, which are left out. Times are epoch
seconds or `null`. A restore keeps `enabled` but forgets which days were sent,
so the next run sends every day to this phone's Health Connect.

`PUT /health/connect` takes `{enabled: true|false}` and returns the status; it
answers 400 while `unavailable` is set. Turning it on starts a run.
`POST /health/connect/export` takes `{all}` (default `false`; `true` sends
every day again) and returns `{queued: true, health_connect}`; it answers 400
`Turn on the Health Connect export first` while off. `POST
/health/connect/permission` returns `{requested}`: true when the app came up
to show Health Connect's permission screen, false when nothing is missing.

The profile body has exactly these fields: `height_cm` (50 to 250), `weight_kg`
(20 to 300), `birthday` (past `YYYY-MM-DD`, year 1900 or later), `gender` (`male`
or `female`), `goal_calories` (10 to 5000), `goal_steps` (100 to 100000),
`goal_standing_hours` (1 to 24), `goal_moving_minutes` (1 to 1440). All but
`weight_kg` are whole numbers. `sent_to_watch` is false when the watch is not
connected; the profile then goes out on the next connect.

## Notifications

`GET /notifications` returns `{calls, allowed, allowed_apps, access, recent}`.
`allowed` is a sorted list of package names; `allowed_apps` lists the same
packages as `{package, app_name}`, with the app's label, or the package name
when Android cannot resolve it; `access` says whether notification access is
granted; `recent` lists apps that posted lately as `{package, app_name,
last_seen_ms}`, newest first, at most 50. No titles or bodies.

`PUT /notifications` takes exactly `{calls, allowed}`: `calls` a boolean,
`allowed` at most 500 package names. It returns the GET shape. Both default to
off.

`POST /notifications/access` returns `{requested}`, true when Android was asked
to open the access screen. Android may still block it while the app is in the
background.

Notification icons need no call: when the watch lacks an app's icon it asks,
and the app uploads the launcher icon of an app whose notification it
forwarded lately. `/status` `data_upload` shows the last upload.

## Watch faces and uploads

`POST /watch-faces/upload` installs a v2 face file: `{"data": "<base64 of the
.bin>"}` (a `data:...;base64,` URL also works). This route alone accepts a body
up to 8 MiB, one such request at a time: a second answers 409 `Another large
upload is in progress` before its body is read. The file is at most 6,000,000
bytes. The file must start `5A A5`
with a decimal id at 0x28; v3 faces and previews of another screen shape
(other than 180x216, 320x385 or the same aspect ratio) answer 400. A face
already on the watch, another install or another upload in progress answers
409; no watch answer to the install start (4/4), an answer without a prepare
status, or a refusal answers 503 with the watch's reason (busy, low storage,
too many faces and so on). Otherwise it answers once the upload has started,
with the face list and `install`. While an install is `uploading`,
`installing` or `activating`, `POST /watch-faces/{id}/activate`
and `DELETE /watch-faces/{id}` answer 409 `A watch face is being installed;
try again when it finishes`.

`install` (also `/status` `watch_faces.install`, `null` before the first) is
`{state, id, name, size, preview, compatibility, error, verified, active,
percent, started_at, finished_at}`, plus `parts_acked` and `parts_total` while
uploading. `state` is `uploading`, `installing` (the last part was acked; the
app waits up to 120 seconds for the watch's install result, 4/5),
`activating` (the face is being switched to and the list re-read), `done` or
`failed`. An install result of verify failed or install failed ends it as
`failed`. With no result in time the face is still switched to and the list
re-read. The install is `done` only when the re-read list holds the face, or
when the watch reported success and the list read went unanswered (then
`verified` is false). A list without the face, no result and no list, or a
disconnect after the upload end it as `failed`. `verified` is true when the
re-read list holds the face; `compatibility` is `matches`, `aspect_ratio` or
`unknown` (no preview).

`/status` `data_upload` is `{state}` (`idle`) or `{state, kind, size,
chunk_size, resume_position, parts_total, parts_sent, parts_acked, percent,
error, started_at, finished_at}`, `state` one of `requesting`, `sending`,
`done`, `failed` and `kind` `watch_face` or `notification_icon`.

## Alarms

`GET /alarms` returns the list last read from the watch: `{alarms, max_alarms,
last_read, supported, read_state, connected, edit_method}`. Each alarm is
`{id, time, repeat, repeat_mode, enabled, smart, label}`. `time` is `HH:MM`,
24-hour, in the watch's local time. `repeat` lists days `mon` to `sun`, empty
for a one-time alarm. `repeat_mode` is `once`, `daily`, `weekdays`, `weekly`,
or `mode_N` for a mode the app reads but cannot write. Labels stay on the
phone, saved with their alarm's time and repeat, and follow their alarm's id:
a time or repeat changed on the wrist, or while the watch was away, is saved
with the label. The first list read after a restore drops a label whose alarm
now has another time or repeat, since the watch may have given its id to
another alarm. `POST /alarms/refresh` reads the list from the watch again.

The app reads the list on connect, around each write, every five minutes while
connected and the phone is awake, and two minutes after a one-time alarm rings,
then 10 and 20 minutes after while it stays on (a snooze). The Redmi Watch 5
Active does not report a change made on the wrist, so such a change shows within
five minutes, or at once after a refresh. That read wakes the
phone, but Android may run it late, by up to an hour on Android 12 and later.
A change of time zone or clock moves it. A change the watch does not
report shows after a refresh; the app's Settings screen refreshes a list older
than a minute.

`POST /alarms` creates an alarm: `time` (required), `repeat`, `enabled`,
`smart`, `label` (up to 40 characters). `PUT /alarms/{id}` changes the fields
given and leaves the rest as the watch has them. `DELETE /alarms/{id}` deletes
one. Each write reads the list back and answers with the alarm and the list.
PUT and DELETE accept `expect`, some of `time`, `repeat`, `repeat_mode`,
`enabled` and `smart` as GET returned them. If the watch's alarm differs or is
gone, the write answers 409 and changes nothing; read the alarms again.

`GET /alarms/sleep-mode` and `PUT /alarms/sleep-mode` (`enabled`, `start`,
`end`) read and write the sleep schedule.

## Find phone

`POST /device/find-phone/stop` silences a ring the watch started, for when
the app is already open, and closes the watch's find-phone screen. It returns
`{stopped, sent}`: `stopped` is true when the phone was ringing, `sent` when
the watch was told to close its screen.

## Agents on Wi-Fi

Off by default. While **Settings > Agents on Wi-Fi > Allow agents on Wi-Fi**
is on, the service also listens with TLS on port 8767 of every interface (on
another free port when 8767 is taken; mDNS gives the real one). Each
connection whose local address does not belong to the current Wi-Fi network
(as Android's `LinkProperties` for it say), or whose remote address is not on
that network's link (inside one of its prefixes, or IPv6 link-local), is
closed before the handshake, so mobile data, VPN, hotspot and routed peers
never reach it; binding every
interface means a new DHCP address needs no rebind. Turning the switch off
closes the listener and every open agent connection; pairings stay. While it
is on, the service keeps running after the watch is released, and starts
after a reboot or an app update as it does for an owned watch (only with the
Bluetooth permission). Each Wi-Fi request holds a partial wakelock from the
end of its TLS handshake for at most 45 seconds, released when it ends; nothing holds the phone awake
between requests, so Android's battery optimization can still pause the app.
Settings shows whether Hypr Watch is exempt and opens Android's list.

**TLS.** An EC P-256 key in AndroidKeyStore (alias
`hypr-watch-agent-tls-v1`, it never leaves the keystore) with the
self-signed certificate the keystore makes for it (`CN=Hypr Watch`), TLS 1.3
and 1.2. Clients pin the SHA-256 of the certificate's DER
SubjectPublicKeyInfo and check nothing else (no hostname, no validity). A
reinstall makes a new key, so every computer must pair again.

**Requests** use the framing, limits and errors of the loopback API, with
these differences:

- No page or static files. Only the routes the MCP tools call answer: `GET
  /status`; `GET`, `POST /notes`; `PATCH`, `DELETE /notes/{uuid}`; `POST
  /watch/connect`, `/watch/disconnect`, `/watch/sync`; `GET /watch/reminders`;
  `GET /device`; `POST /device/find-watch`, `/device/find-phone/stop`,
  `/device/time-sync`; `GET /weather`; `PUT /weather/settings`; `POST
  /weather/refresh`; `GET /music`; `PUT /music/settings`; `GET`, `POST
  /alarms`; `POST /alarms/refresh`; `PUT`, `DELETE /alarms/{id}`; `GET`, `PUT
  /alarms/sleep-mode`; `GET`, `PUT /watch-settings`; `GET`, `POST
  /heart-rate/live`; `GET /watch-faces`; `POST /watch-faces/{id}/activate`;
  `DELETE /watch-faces/{id}`; `POST /watch-faces/upload`; `GET`, `PUT
  /contacts` and `/quick-replies`; `POST /health/sync`; `GET /health/days`,
  `/health/series`, `/health/sleep`, `/health/samples`, `/health/workouts`;
  `GET`, `PUT /health/profile`; `GET`, `PUT /notifications`. Anything else
  (`/export`, `/health/files`, `/health/parse-report`, `/watch/probe-silent`,
  `/health/connect*`, `/notifications/access`, `/weather/permission`,
  `/agents*`) answers 404 `Not available over Wi-Fi`, so a paired computer
  can't read raw health files, open Android screens, pair others or revoke
  anyone.
- A request with an `Origin` header gets 403: agents never send one, and a web
  page on the same network could make a browser do so.
- Only `Authorization: Bearer hwa1_…` tokens of paired computers; the
  loopback token is refused. A wrong or revoked token gets 401 `This computer
  isn't paired with the phone, or was revoked. Pair it again.` After 20 such
  failures from one address within a minute, its connections are closed
  unanswered until that minute ends.
- `/status` reads `connection: "Wi-Fi (TLS)"`.
- At most 8 connections at once and 2 per remote address (one more is
  closed), 10 seconds for the TLS handshake, 40 seconds between reads. A
  connection that hasn't sent an authenticated, allowed request 15 seconds
  after it was accepted is closed, however slowly it sends.
- A request that fails in any way, a body nested deeply enough to overflow
  the JSON parser included, closes that connection only.

**Pairing.** Tapping **Connect an agent** opens a window of 120 seconds
holding one code of 8 symbols from Crockford base32
(`0123456789ABCDEFGHJKMNPQRSTVWXYZ`, 40 random bits), shown as `K7Q2-M9XA`.
Both sides normalise a typed code first: uppercase, drop spaces and `-`,
read `O` as `0` and `I`, `L` as `1`; it must then be 8 symbols of the
alphabet. With `spki` the 32-byte pin above:

```
K = PBKDF2-HMAC-SHA256(code, "hypr-watch pair v1" || spki, 200000 iterations, 32 bytes)
client proof = HMAC-SHA256(K, "client" || spki || nonce)
phone proof  = HMAC-SHA256(K, "phone" || spki || nonce || token)
```

The client reads the certificate's `spki` during the handshake, before it
trusts it, and sends `POST /pair` (no token) with
`{"v":1,"name":"<computer, up to 64 characters>","nonce":"<16 bytes>","proof":"<client proof>"}`,
binary fields base64url without padding. The phone computes K with its own
`spki`, so a proof made through another certificate (a man in the middle's)
fails like a wrong code. On success it creates the computer's token, closes
the window and answers
`{"v":1,"phone_id","phone_name","agent_id","token":"hwa1_…","proof":"<phone proof>"}`;
the client keeps the token only if the phone proof checks out with the `spki`
it saw. `tests/agent-pairing-vectors.json` pins these values for both sides.

A `/pair` body over 1,024 bytes gets 413 before it is read and uses no
try. Every other request the window processes uses one of its 5 tries, from
any address: 403 `That code didn't match. N tries left.`, 400 for a malformed
body. The last try, success, the 2 minutes running out, closing the sheet and
turning the switch off close the window; `/pair` then answers 404. One
request is checked at a time; another meanwhile gets 409 and uses no try.

**Discovery.** While the listener is on, NsdManager advertises
`_hyprwatch._tcp` as `Hypr Watch <model>` on the listener's port, with TXT
`v=1`, `id=<phone_id>` and `pair=1` while a code is open (else `0`), and
registers again when that changes. mDNS only suggests an address; the pin
decides.

**Storage.** `files/agents.json`, replaced whole on each change:
`{"enabled", "phone_id", "agents": [{"id", "name", "created_at",
"last_seen_at", "last_ip", "token_sha256_hex"}]}`. Tokens are kept only as
SHA-256 and compared in constant time; `phone_id` is 16 random bytes made
once per install. `last_seen_at` and `last_ip` are written at most once a
minute, or when the address changes. Revoking deletes the entry, so the
computer's next request gets 401. A file that can't be read is moved to
`agents.json.bad` and the service starts with Wi-Fi access off and no
paired computers, logging why.

**Management routes**, on the loopback listener only, with its token, for
the phone's Settings:

- `GET /agents` returns `{enabled, listening: {host, port} | null, wifi,
  pairing: {code, expires_at, attempts_left} | null, last_pairing: {result,
  name?, at} | null, agents: [{id, name, created_at, last_seen_at,
  last_ip}], battery_exempt}`. `host` is the phone's IPv4 address on Wi-Fi,
  `null` when not on Wi-Fi. `last_pairing.result` is `paired` (with `name`),
  `no_tries`, `expired` or `closed`. Times are epoch milliseconds;
  `last_seen_at` and `last_ip` are `null` until first used.
- `PUT /agents/lan` `{"enabled": bool}` turns the listener on or off and
  returns the same object; 503 when it can't start.
- `POST /agents/pairing` opens a new window (replacing one already open) and
  returns its `{code, expires_at, attempts_left}`; 400 while the listener is
  off. `DELETE /agents/pairing` closes it.
- `DELETE /agents/{id}` revokes a computer: `{deleted: true}`, or 400 `That
  computer isn't paired`.

## Backup and restore

Backup and restore run only from **Settings > Backup and restore** in the
phone app. No HTTP route or MCP tool makes, reads or restores a backup: the
file holds the watch key, and a route would let anything holding the PC token
copy that key off the phone.

A backup holds `notes.db`, `health.db` (with the health profile and every
feature's settings), `files/watch.json` (the watch key) and
`files/notification-filter.json`. It leaves out the API token
(`hypr-watch usb` reads the new one), the paired agents and the Wi-Fi switch
(`files/agents.json`: the phone's TLS key can't leave the keystore, so a
restored phone pairs again), the saved weather fix (taken again when
the app opens), and Android permissions and the Bluetooth bond, which belong
to the phone. A restore replaces all four files; one the backup lacks is
removed.

Each database is copied after a `wal_checkpoint(TRUNCATE)`, under the lock
every write to it takes, so the copy is the database as of its last commit.
A restore decrypts into app storage and checks both databases (SQLite quick
check, schema version) before anything changes. An older `health.db` schema
is upgraded on that copy, so a failing upgrade also changes nothing. The copy's
Health Connect export forgets which days it sent (it stays on or off), so its
next run sends every day to this phone's Health Connect, and its alarm labels
are checked against the watch's first list. Then the app leaves
its page, so the page stops calling the API while the port is free, and it
stops the service (watch link, sync, ingest, this API), moves the current
files and their `-wal`, `-shm` and `-journal` aside, moves the restored ones
in, and starts the service again, which connects the watch when a key is
present. A service started meanwhile, as when the Activity is recreated,
waits until the files are in place, and a notification filter save in that
window is refused. If Android refuses the restart because the app is in the
background, the app starts the service and shows the result when it is next
opened. The page loads again only once the app's own server holds the port; if
that takes longer than 10 seconds, it loads when the app is next opened.

A marker file records the swap's phase. It is replaced whole, and the files
and folders of each phase are synced to disk before it changes. A failure
puts the old files back; if the app dies or the phone loses power mid-swap,
the next service start does, and deletes the decrypted copies a restore left
behind. A roll back deletes only files it knows are restored ones, and the
old files are deleted only after the marker is gone.

File format, version 1, integers big-endian:

| Offset | Bytes | Field |
|---|---|---|
| 0 | 8 | Magic `HYPRWBAK` |
| 8 | 1 | Version, 1 |
| 9 | 4 | PBKDF2 iterations, 600,000 |
| 13 | 16 | Salt |
| 29 | 7 | Nonce prefix |
| 36 | 16 | Key check |
| 52 | rest | Encrypted archive, in segments |

PBKDF2-HMAC-SHA256 of the UTF-8 bytes of the NFC-normalized passphrase gives
a 32-byte master key. HMAC-SHA256 of the master key over `hypr-watch backup
key` is the AES-256 key; the first 16 bytes over `hypr-watch backup check` are
the key check, which tells a wrong passphrase apart from a damaged file.
The archive is cut into 65,536-byte segments, each sealed with AES-256-GCM
(16-byte tag) with the 52-byte header as associated data and the nonce
`prefix || segment index (4 bytes) || 1 for the last segment, else 0`. So a
changed header, or a segment changed, moved, dropped or added, fails to
decrypt. The archive is a list of files, each a name length (1 byte), the
ASCII name, a size (8 bytes) and the contents, ended by a zero length byte.
`notes.db` and `health.db` are required.

A restore fails and changes nothing on another magic (`This file isn’t a Hypr
Watch backup.`), a newer file version or database schema (`This backup is
from a newer version of Hypr Watch. Update the app, then restore.`), a wrong
passphrase (`Wrong passphrase.`) and a cut, changed or extended file (`The
backup file is damaged or incomplete.`).

## Command line

`hypr-watch` (plugin/server/cli.ts) uses the Wi-Fi pairing when there is one,
else USB. Examples:

```sh
npx hypr-watch setup [--host ip[:port]] [--name name] [--claude] [--codex] [--yes]
hypr-watch mcp
hypr-watch usb --open
hypr-watch pair
hypr-watch status
hypr-watch create --title 'Buy groceries' --body 'Milk and rice' --send-to-watch true
hypr-watch notes
hypr-watch sync
hypr-watch update --id NOTE_UUID --done true
hypr-watch export
```

`setup` pairs the phone and registers the MCP server with Claude Code or Codex
at user scope. `mcp` runs the MCP server over stdio.
`hypr-watch usb [--watch | --release] [--open]` sets up the USB connection
(and the Mi Fitness handoff below); `pair [--host ip[:port]] [--name name]`
and `unpair` manage this computer's Wi-Fi pairing. The note commands cover
notes and watch operations only. Use the MCP tools or HTTP for health and
notification routes.

## Companion handoff

`hypr-watch usb --watch` and MCP `connect_watch` also pause Mi Fitness using ADB
and force-stop Gadgetbridge. While `/status` reads `connecting` they wait up to
35 seconds instead of opening the link again, and touch no package unless that
connect fails. When the connect fails and that call paused Mi Fitness, they
release the app first (it may own the watch from before), as `--release` does,
and restore Mi Fitness. A failed connect after an earlier take-over leaves both
alone. Calling `/watch/connect`
directly only opens Bluetooth; it does not change another Android package. Use
`hypr-watch usb --release` or MCP `disconnect_watch` to restore Mi Fitness after
a USB handoff. Over Wi-Fi, without USB, `connect_watch` and `disconnect_watch`
only call the API and leave Mi Fitness as it is; the result says so. A release calls `/watch/disconnect`; when the app does not
answer, it force-stops the app and writes its prefs
(`shared_prefs/watch-link.xml`) as released over ADB instead, so a later
restore does not take the watch back; the result message says so. Mi Fitness is re-enabled only
once one of those worked; otherwise it stays paused and the error says so.

Private PC files live under `$XDG_STATE_HOME/hypr-watch` or
`~/.local/state/hypr-watch`, with a mode-700 directory. `connection.json`
contains the generated API token. `companion.json` records whether Mi Fitness
should be restored. `pairing.json` (mode 600) holds the Wi-Fi pairing: phone
id and name, certificate pin, agent id and token, last host and port. None of
these files belongs in a source or plugin archive.
