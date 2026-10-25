# Context

Hypr Watch shares one Android SQLite database with the phone WebView and
PC browser. Android listens only on 127.0.0.1:8766. ADB forwards that port over
USB. A generated bearer token, strict Host/Origin checks and a self-only CSP
protect note operations. The watch auth key remains in Android private storage.

The Android app is Kotlin on the platform SDK (no AndroidX), built without
Gradle by scripts/build-android.sh. Its UI is the same HTML/CSS/JS served to the
PC, hosted in a WebView that receives the system bar sizes as `--safe-top` and
`--safe-bottom` and follows the system light/dark theme. It has three tabs:
Notes, Health (today, trends, sleep, workouts) and Settings (health profile,
watch notifications).

The app owns the watch connection and is meant to replace Mi Fitness. Mi
Fitness is needed once, to bind the watch; the auth key comes from that
binding. The USB helper disables Mi Fitness and force-stops Gadgetbridge before
connecting, and re-enables Mi Fitness on release if it was enabled before.

Android holds a Bluetooth Classic SPP connection to the bonded Redmi Watch 5
Active. Protocol v2 uses authenticated Xiaomi nonce/key derivation and AES
session encryption. Command 17/14 reads task slots; 17/15, 17/17 and 17/18 create,
edit and delete reminders. Notes always use repeat mode 15, reminder disabled,
source 1 and open status 1. Firmware 2.2.105 preserves these extra task fields
and reports status 2 plus a changed timestamp on watch completion.

The link layer (Frames.kt) is a go-back-N send window on 8-bit sequence
numbers. Acks are cumulative; a nack resends from the expected frame; a frame
with no ack is resent every 10 seconds and the link drops after the third
timeout. Frames stay until acked, never evicted, and a send is refused while
128 are unacked. A writer thread does the socket writes, so a stalled socket
blocks neither the reader nor the timers. Received data frames are acked, then
checked against a per-sequence CRC-32 so a resend after a lost ack is handled
once. Channel 1 carries commands, channel 5 activity file data, and channel 2
upload data from the phone: plaintext, numbered in the same window, and held
back while as many frames as the watch's session-config tx window (at most 4)
await an ack, so commands never wait for it. Paced commands (health file
requests and acks) are held back the same way. An upload frame larger than the
session config's max packet, its 8-byte header included, is refused. Each link
reset starts a new epoch; upload data names its epoch, so frames a reset
dropped are never counted as acked. During an upload, from its reservation or
22/0 request until it ends, every received frame is traced (kind, channel,
opcode, command; no payload), and a reset logs one line on where the upload
stood, also before its first chunk. Replies go to
the one pending request; other commands go to handlers registered by type and
subtype. Health and notification sends are fire-and-forget and wait only for
link acks.

WatchReconnect keeps the link up while the app owns the watch: an explicit
connect (API, restore) claims it once it authenticates, and an explicit
disconnect releases it; a failed explicit connect changes nothing. Both are
kept in private prefs with the last drop, and a restore's restart connects
only when the app owns the watch or never released it. Service start (a boot
or update receiver starts the service only when owned and Bluetooth is
allowed; it is always sticky, a restart after a kill stops again unless
owned, and a refused foreground start stops it without a restart), Bluetooth
on and pairing the watch try at once and start the backoff over. An ACL link to
the paired watch tries at once but keeps the backoff; within 15 s of an
attempt's end it is likely that attempt's own, so it tries once those 15 s
end only if the link is still up and the backoff timer is not due first. A
drop or failed attempt retries after 5 s, 15 s, 60 s, then every 5 minutes, on
an in-process timer backed by a `Phone.at` wakeup, which hands the attempt to
the reconnect thread under its own bounded wakelock. A phone-side failure (Bluetooth off, no paired watch)
waits for Bluetooth or the watch instead; a key problem (missing, invalid, or
the watch rejecting authentication) waits for an explicit connect. A trigger
while an attempt runs or the link is up does nothing, and an explicit connect
then joins that attempt. Attempts are numbered, and while one is under way the
end of an older one changes nothing. A connect checks it is still wanted once
its socket exists and again when it authenticates, closing a link released
meanwhile (only while that socket is still the current one).

WatchSession wires one link: reminder commands, feature requests and
submitted feature tasks run one at a time on a command thread; watch commands,
chunks and connect hooks on the reader thread; notifications on the listener's
thread. WatchAuth authenticates after each session config.
If the watch sends its session config again on an open link, the session is
treated as disconnected and reconnects after the next 1/27; `/status` shows
`connecting` meanwhile. Each session config and each disconnect starts a new
session generation under WatchAuth's lock: a 1/27 counts only while its
authentication is under way, the authentication deadline only in the
generation that armed it, and the connect hooks run under that lock, so a
disconnect lands before or after all of them. The link refuses encrypted
sends from a session config until its 1/27; a reminder request registers and
enqueues under the link lock, so a restart either fails it at once or refuses
it.

HealthCapture asks for today's (8/1) and past (8/2) file lists, then requests
one file at a time (8/3). HealthStore keeps raw files in `health.db`, one row
per file id and version; its SQL lives in HealthTables, which host tests run on
real SQLite. A version is its exact bytes, keyed by its CRC-32, plus n * 2^32
for the n-th other bytes with the same CRC-32. A version fetched from the past
list is `complete`.
The watch sends the next file only after the previous one is acked (8/5), so
each file is acked once its raw bytes are stored, never before. health.db and
notes.db run with `synchronous=FULL`, so a commit that was acked to the watch
or written to it survives a power cut. A past id
already stored complete is acked again when listed. Requests and acks are
paced sends; one the link holds back holds the run until a link ack. Owed acks
go out only between files: before the next request, after a file that arrived
late, or while the requested file has not started. A file too short or with a
bad CRC is fetched once more, then stored as an invalid version (keyed by the
CRC-32 of all its bytes; ingest reports it invalid) and acked. A file over
4 MB is not stored or acked; its remaining chunks are drained and the run ends
there, the files still queued deferred to the next sync, since the watch sends
nothing more until it is acked. HealthIngest runs on a
background thread after each new version. For each id it keeps the rows of the
newest version that parses, in minute, daily, sleep, manual-sample and workout
tables. Any new version of an id makes it pending again, even one whose
phone-clock `first_seen` sorts below the newest. A storage error leaves the id
pending for the next pass; only parse outcomes are recorded. Every file keeps its own rows; reads pick a
winner per key by file rank, and per night by finished, then SLEEP over
SLEEP_STAGES, then rank. Raising the parser version re-ingests all files.
HealthApi serves the read routes. The health profile goes to the watch (8/0)
only after the user saves one, and again on each connect.

HealthConnectExport copies those reads to Android Health Connect (Android 14+,
off by default) on its own thread, 20 seconds after an ingest pass that
ingested a file, and on demand. HealthConnectMap turns one day's rows into
plain records whose clientRecordId is the type and the record's time; the
version is the run's start time, or one more than the last run's when the
clock stepped back, so a repeat run updates instead of adding. Each ingest of
a file takes the next number from the `ingest_seq` setting (health_ingest.seq),
which never goes back. A run exports the days with rows from files ingested
after the previous run read that number (every day on the first run, after
"export all", or once another type is allowed), newest first, and keeps the
days still to go in the settings table, so an interrupted run resumes, by
itself 15 minutes after a failure. Records still running (this minute) wait
for a later run and keep their day pending, and a batch Health Connect rejects
is halved until the bad records stand alone and are left out. For the last 3
days it keeps a CRC-32 per sent record: unchanged records are skipped, and ids
the day no longer has (a night whose winning record moved) are deleted.
HealthConnectWriter builds the framework records and waits for each call; its
errors never quote values. A call that throws at once counts as a rejected
record only for IllegalArgumentException; anything else fails the run, which
keeps its days.

DataUpload sends one file at a time: 22/0 with type, MD5 and size; the watch's
22/0 ack gives the chunk size; the payload goes out on channel 2 in parts as
link acks free room, and the upload is done when the last part is acked. A
watch refusal, no answer in 10 seconds, no ack progress in 35 seconds or the
end of the session fails it. Notification icons (7/16 query, 7/15 reply and
request, type 50) and watch face installs (4/4, type 16, then the watch's
install result 4/5, 4/1 and a list re-read) use it. A face install is done
only when the re-read list holds the face, or the watch reported success and
the list read went unanswered; otherwise it fails. A face install reserves the uploader before its 4/4 and keeps
it into the upload, so no icon slips in; refusal, timeout or disconnect gives
it back, and switching or deleting faces answers 409 while an install runs.
Each time the uploader frees up, waiting icons start; an icon leaves its queue
only under a reservation, so a start that loses a race never strands it. Icon queries are answered
only for apps forwarded lately, without asking Android on the reader thread;
the icon is drawn on the commands thread. The face upload route alone accepts
an 8 MiB body, one such body at a time (a second answers 409 before its body
is read). Its base64 is decoded straight from the body bytes when the body is
just `{"data": "..."}`.

WristNotificationListener forwards posted notifications from allowed packages,
and incoming calls when enabled; both default to off. WatchNotifications
encodes 7/0 and 7/1, maps Android keys to watch ids, and turns watch dismiss,
open, reject and ignore into phone actions. Owed removals and a ringing call
the link refused are sent, in that order, once the send window has room. Ignore is a no-op. Titles and
bodies go only to the watch: they are never logged, stored or returned by the
API.

Watch features (WatchFeature.kt) are how the app grows. A feature is one
Android-free `*Feature.kt` file plus one line in `appFeatures` (WatchFeature.kt),
the list NotesService installs and the host tests install too. Its order is
the connect-hook order: device first, so the watch clock is set before
anything else, then health (profile, then file sync), health settings, live
heart rate, notifications, then the rest. Through FeatureHost a feature
handles watch commands, sends fire-and-forget, requests on the command thread
(a request from a handler, hook or timer throws instead of blocking the link),
adds routes that NotesService tries after its own and after the token and
Host/Origin checks, adds an object to `/status` under its name, and keeps
JSON settings in `health.db` under `feature.<name>.`. Of two routes matching a
path, a literal segment beats a parameter; routes of the same shape are
refused at install. A route that waits on the watch or the network runs as an
operation: one per feature and two across features, so the other two HTTP
threads stay free; a competing one answers 409. A status that throws becomes
`{error}` for that feature only. Android services come through the Phone port
(AndroidPhone on the device). A task submitted in one session never runs in
the next. Reminder sync stays outside the seam. Guide: docs/watch-features.md.

Feature replies carry no request id. The watch answers in order, so per
command the n-th reply of a session answers the n-th send. A reply that timed
out stays owed: when it comes it updates the cache and answers nothing, so a
later route never verifies against a stale list. Multi-step watch writes
(alarms, watch faces, health settings) check their session under the
feature's lock at every send, so no step reaches a later session. A health
settings block written but not yet read back cannot be edited again; refusing
such an edit reads the block again, as does a read-back still missing after
10 seconds, which then counts as lost. Settings reads are counted per block,
so a reply to a read sent before the latest write neither confirms it nor
replaces the cached block.

NotesSync serializes cycles. It records an intent in SQLite before a write
and verifies the result with a fresh list before binding an id. An interrupted
write or initial capability probe is reconciled before any further writes.
A full note cannot be deleted while either intent refers to it, and deletion
checks run under the same store lock used by sync. One mutation per cycle limits
long Bluetooth stalls. Id, title, source, no-alert mode and write timestamp
identify bridge-owned tasks. Changes to those identity fields pause mutation.
Completion is the supported exception: unchanged identity text/source/mode,
completed status and a monotonic timestamp must survive a second read.
Two authoritative reads confirm disappearance, including an empty final list.

UI saves use an optional revision precondition to avoid overwriting a note
changed on another device. All full note data remains in the SQLite notes
record when a watch task is completed or removed.

The local Python MCP server exposes only supported note, connection, health
and notification-setting operations. Its USB handoff pauses Mi Fitness and
saves its prior enabled state, then restores it on release, only once the app
released the watch (over the API, or by a force-stop and deleting its
ownership over ADB). It never exposes
firmware upload, arbitrary Bluetooth commands, raw health files or the watch
authentication key.

Hardware-unverified: whether the firmware handles an alarm command (17/0-17/9)
while a reminder command (17/14-17/18) awaits its reply; routing keeps the
replies apart. Feature reply counting assumes each reply arrives once and in
order; an unsolicited alarm list (also unverified) would take a late reply's
place. Whether the watch sends 17/0-17/5 or 17/9 on its own when alarms change
on the wrist, or a one-time alarm turns itself off after ringing, is
unverified too: the app reads the list again on such a command and two
minutes after a one-time alarm's time (again at 10 and 20 minutes while it
stays on), on a wakeup alarm that Android may run late. Feature settings reads (SQLite) also
run on the reader thread in a few handlers; they are small and have not been
moved.

Backup and restore (PhoneBackup, file format in Backup.kt) run only from the
phone's Settings, never over the API or MCP, since the file holds the watch
key. Each database is copied after a WAL checkpoint under the lock its writes
take. A restore decrypts and checks everything first, stops the service
(one created meanwhile waits for the restore's start command), swaps the files with a marker that lets the next start roll a half-done swap
back, then restarts the service and connects the watch. When Android refuses
that restart from the background, the Activity starts the service once it is
in front.
