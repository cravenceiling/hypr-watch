# Writing a watch feature

A watch feature is one Kotlin file, `android/src/local/hyprwatch/<Name>Feature.kt`,
plus one line in `appFeatures` (`WatchFeature.kt`). It may handle watch commands, send and request
commands, run hooks on connect and disconnect, serve HTTP routes, add an object
to `/api/status`, keep small settings and use a few Android services. It sees
all of that through `FeatureHost` (in `WatchFeature.kt`) and never touches
`SppLink`, `WatchAuth` or the auth key.

The working example is `tests/features/ExampleFeature.kt` with its test
`tests/features/ExampleFeatureTest.kt`. It is not shipped.

## Steps

1. Write `android/src/local/hyprwatch/<Name>Feature.kt` with a class that
   implements `WatchFeature`. No `android.*` imports: host tests compile every
   `*Feature.kt`, so an Android import fails `scripts/test-host.sh`. Keep codecs
   and helpers in the same file.
2. Add it to `appFeatures` at the end of `WatchFeature.kt`, the list
   NotesService installs. List position is connect-hook order; the device
   feature stays first so the watch clock is set before anything else.
   `FeaturesTest` installs the same list, so a name, command or route
   collision fails the host tests.
3. Write `tests/features/<Name>FeatureTest.kt` as an `object <Name>FeatureTest`
   with a `main`. The script compiles everything in `tests/features/` and runs
   each `*Test` object there. No script edit is needed.
4. Run `bash scripts/test-host.sh`, then `bash scripts/build-android.sh`
   (warnings fail the build).

## The interface

```kotlin
interface WatchFeature {
    val name: String                      // status key and settings prefix: [a-z][a-z0-9_]{0,31}
    fun install(host: FeatureHost)        // register everything here, once
    fun onConnected() {}                  // after each authentication
    fun onDisconnected() {}               // after every disconnect or session restart; idempotent
    fun status(): JSONObject? = null      // object under `name` in /api/status
    fun close() {}                        // service stopping
}
```

`FeatureHost` gives you `connected`, `settings`, `phone`, `on`, `route`,
`operation`, `onChunk`, `onAcked`, `send`, `sendPaced`, `linkEpoch`,
`sendData`, `dataInFlight`, `traceUpload`, `request`, `submit`, `after` and
`log`. `linkEpoch`, `sendData`, `dataInFlight` and `traceUpload` carry
channel-2 upload data and its tracing for `DataUpload`
(DataUpload.kt); a feature that needs to send a file to the watch calls
`DataUpload.start` instead, after `reserve` if it must ask the watch first,
and hears when the uploader frees up through `onIdle`. Read the KDoc in
`WatchFeature.kt`, which is the contract.

## Threads

The feature runs on threads shared with the Bluetooth link. Most rules
follow from that.

- Command handlers, `onChunk`, `onAcked`, `onConnected` and `onDisconnected`
  run on link threads. `onConnected` runs under the session lock, so a
  disconnect lands before or after all hooks. Timer tasks from `after` run on
  the timer thread. None of these may block.
- `send` is fire-and-forget and never throws. It returns false when the watch
  is disconnected or the send window is full, and logs "Dropped a ...".
  `sendPaced` is for bulk sends (HealthCapture's file requests and acks): it
  also returns false, without a log line, while as many frames await an ack
  as the watch takes at once; go on from `onAcked`.
- `request` sends a command and waits for the reply with the same type and
  subtype. It runs on the commands thread, one at a time with reminder sync.
  A route's call gives up after 20 seconds, including the wait for its turn.
  If the watch does not reply within 15 seconds the link drops. Called from a link thread it throws `IllegalStateException`
  instead of blocking, and the guard logs it.
- To request from a hook or handler, wrap it in `submit { ... }`. The task runs
  later on the commands thread. It is dropped, with a log line, if the session
  ends before it starts. Keep a task to a few requests, since reminder sync
  waits behind it.
- Routes run on HTTP threads and may call `request` directly. There are four;
  wrap anything that waits on the watch or the network in `operation`.
- `Phone.every` tasks run on a background thread of their own and may block,
  including on `request`. The phone stays awake until the task returns (at
  most a minute), so block until the work is done rather than handing it to
  another thread.

A failing handler or hook is logged and the next one still runs.

## Commands

```kotlin
host.on(TYPE, SUB) { cmd -> ... }          // watch-initiated, decoded with Proto
host.send(Proto.command(TYPE, SUB, ...))    // false when not sent
val reply = host.request(Proto.command(TYPE, SUB))  // throws IOException on failure
```

One feature per (type, subtype). Type 1 belongs to the session. A reply to
your own `request` never reaches `on` handlers. The watch's file data on
channel 5 goes to `onChunk`. If you keep a queue of sends the window refused,
retry them from `onAcked`.

If a route sends and waits for the reply in an `on` handler (for commands
`request` cannot use), replies carry no request id. Count sends and replies
per command in the session: the n-th reply answers the n-th send, a reply
beyond the sends is the watch's own push, and a reply that timed out stays
owed, so when it arrives late it answers nothing. Check the session and send
under one lock, the same one your `onConnected` and `onDisconnected` bump the
session under, so no step of a multi-step write reaches a later session.
`WatchFacesFeature` and `AlarmsFeature` do this. `AlarmsFeature` also reads
its list again after a command no route waits for, a push or a late reply,
since either may be news of a change made on the watch.

## Routes

```kotlin
host.route("GET", "/api/alarms") { r -> JSONObject().put("alarms", ...) }
host.route("PUT", "/api/alarms/{id}") { r -> val id = r.params.getValue("id"); r.body ... }
```

Paths start with `/api/`. `{name}` matches one non-empty segment, and a final
`{name*}` matches the rest of the path. When two routes match, the first
segment where they differ decides: a literal beats `{name}`, which beats
`{name*}`, whatever the registration order. Two routes of the same shape
(`/api/x/{a}` and `/api/x/{b}`) are refused at install. `r.query` parses the
query string on first use and rejects repeated or malformed parameters.
`r.body` is the JSON object body, `{}` when empty. A route listed in
`Http.LARGE_BODIES` gets the body's bytes in `r.raw` instead, `r.body` left
`{}`, and only one such request runs at a time. NotesService has already
checked the token, Host and Origin, and tries its own routes first. Pick paths
it does not use.

A route that waits on the watch or the network wraps that work in
`host.operation { ... }`. Each feature runs one operation at a time and all
features two at once, so the HTTP pool keeps threads for everything else. A
competing call fails at once with 409 `Another watch operation is in
progress`. A route that only reads what the feature holds never waits.

Errors follow the existing API. Throw `IllegalArgumentException` (or use
`require`) for bad input, which answers 400 with the message.
`Http.Conflict` answers 409. Any other exception answers 503 with the root
cause's message, so throw `IOException` for "watch unavailable". Add the route
to API.md and, if agents need it, an MCP tool.

## Status and settings

`status()` returns this feature's object under its name in `/api/status`. It
runs on every status poll, so read state you already hold and never request.
If it throws, `/api/status` shows `{"error": "Status unavailable (<class>)"}`
under the name instead, and the other features are unaffected.

`host.settings.get(key)` and `put(key, JSONObject)` store small JSON values in
the `health.db` settings table under `feature.<name>.<key>`. Keys match
`[a-z][a-z0-9_]{0,31}`. A write is durable when `put` returns.

## Android services

`host.phone` is the `Phone` port: coarse location, app labels and icons, notification
access, the active media session (now playing with its position extrapolated
to now, transport, change listener), the music volume, `every`, a task
re-armed after each run as an allow-while-idle alarm, so Doze can stretch the
interval (to at least 9 minutes) but not skip it, and `at`, a task run once at
a wall-clock time from the same kind of alarm, which wakes the phone (unlike
`host.after`, whose clock stops while the phone sleeps) but may run late, by
up to an hour on Android 12 and later. `AndroidPhone` implements it
on the device and `FakePhone` in tests. Location needs the coarse location
permission, which the user grants through `POST /api/weather/permission`, and
Location turned on in the phone's settings; `locationAccess()` says which is
missing.
Android gives that permission's fixes only while the app is open, so the
Activity takes one on each resume and keeps it rounded to 2 decimals;
`onLocation` listeners hear about each new fix. Opening the app often gives
two fixes seconds apart (Android's last-known one, then a fresh one), so a
listener should not start a second update for a place it is already updating;
`WeatherFeature` skips it. The kept fix has no expiry, since the app may stay
closed for days and a city-level fix from then beats no weather; it is deleted
as soon as `coarseLocation` or the Activity finds the permission revoked.
Media needs notification access. Without them these return null, false or
never fire.

If you need another Android service, add a method with plain types to
`Phone`, implement it in `AndroidPhone` and `FakePhone`, and keep Android
types out of the feature file.

## Privacy

Never log, store or return notification titles and bodies, and treat media
titles the same way. The auth key never leaves `WatchConnection`. Logs go to
logcat through `host.log`.

## Testing with FeatureRig

`tests/features/FeatureRig.kt` runs features on the real `Features` registry
over a `FakeWatch`. Every rig action finishes by running submitted tasks, so
their effect shows at once.

| Call | Does |
|---|---|
| `FeatureRig(MyFeature(), ...)` | Installs the features, disconnected; `phone = FakePhone()` to set it up before install |
| `connect()` / `disconnect()` | Runs the hooks as authentication or a drop would, and starts a new link epoch (`watch.epoch`) as a reset does |
| `receive(Proto.command(t, s, ...))` | A watch-initiated command |
| `answer(t, s) { cmd -> Proto.command(t, s, ...) }` | Scripts replies to `request`; field 100 other than 0 is a rejection |
| `sent()`, `take()` | Commands sent and requested, as hex; `take` also clears |
| `call(method, target, body, raw)` | `(status, json)` as NotesService answers it, including 400/503 mapping and "Unknown API operation"; a large-body route gets `raw`, by default `body`'s bytes |
| `status()` | What the features add to `/api/status` |
| `chunk(bytes)`, `ack()`, `fire()` | Channel-5 data, a link ack (of every upload chunk too), due timer tasks |
| `data()`, `takeData()`, `ackData(n)` | Upload chunks sent on channel 2, as hex; a link ack of the oldest `n` |
| `watch.dataWindow`, `watch.dataBroken` | How many chunks may be in flight (3); a message makes `sendData` throw it |
| `watch.refuse = true` | Sends fail as with a full window |
| `watch.onSend`, `watch.onConnectedRead`, `settings.onPut` | Hooks after each send, on each read of `connected`, after each settings write, to force an interleaving such as a reconnect mid-operation |
| `watch.tracing` | Whether `DataUpload` has the link tracing an upload |
| `phone` | `FakePhone`: set `location`, `locationState`, `labels`, `icons`, `access`, `playing`, `volume`; `fixListeners` are the `onLocation` listeners; `tick()` runs `every` tasks, `wakeups` holds the `at` tasks and `wake(nowMs)` runs those due, `mediaChanged()` the listeners |
| `settings.values` | Stored settings by full key |
| `logs` | Log lines, including guarded failures |

A minimal test:

```kotlin
object ExampleFeatureTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val rig = FeatureRig(ExampleFeature())
        rig.answer(99, 2) { Proto.command(99, 2, Proto.num(3, 41)) }
        rig.connect()
        check(rig.status().getJSONObject("example").getLong("counter") == 41L)
        val (code, body) = rig.call("GET", "/api/example")
        check(code == 200 && body.getString("greeting") == "hi")
        println("Example feature: passed")
    }
}
```

The fake runs everything on the test thread, so it cannot catch races with
reminder sync. `tests/SessionTest.kt` covers the real `WatchSession` threads
and is where a test that needs real timing belongs.
