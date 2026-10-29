# Hypr Watch

An open source Android companion for the Xiaomi Redmi Watch 5 Active. It
replaces Mi Fitness: the phone talks to the watch directly over Bluetooth, and
your data stays on your phone.

> [!WARNING]
> I built Hypr Watch for one device: my **Xiaomi Redmi Watch 5 Active**
> (model M2351W1, firmware 2.2.105), paired with an Android device. I don't
> know if it works with any other watch, model or firmware. Other Xiaomi
> wearables use a similar protocol, but none were tested. Never install a
> watch face made for another model: that has bricked other Xiaomi devices.
>
> This is a personal project, not affiliated with or endorsed by Xiaomi.

## What it does

- **Notes.** Full notes shared between your phone and PC, with a short silent
  title on the watch's Reminders app.
- **Health.** Fetches steps, heart rate, SpO2, stress, sleep stages and
  workouts from the watch into a local database, with a dashboard on the phone
  and PC. Optional export to Health Connect.
- **Notifications and calls.** Forwards the apps you pick, and incoming calls.
- **Alarms.** Reads and edits the watch's alarms and sleep schedule.
- **Weather, music, watch faces.** Pushes forecasts, controls phone media, and
  installs `.bin` watch faces.
- **Backup.** One passphrase-encrypted file with your notes, health data,
  settings and watch key, to move to a new phone without pairing again.
- **AI access.** A local MCP server, so an agent such as Claude Code or Codex
  can read your health data, manage notes and alarms, and more.

No cloud account, no telemetry. Agents reach the phone over paired Wi-Fi with
TLS or authorized USB. The PC dashboard uses USB.

## Build from source

Everyone currently builds and installs the phone app from source. Follow these
steps before connecting an agent. The PC helper supports macOS and Linux.

You need:

- An Android phone (Android 8 or later; tested on Android 15) with USB
  debugging on.
- The watch's 32-digit authentication key. It comes from pairing the watch
  once with Mi Fitness; Gadgetbridge's
  [guide](https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/)
  explains how to get it. Hypr Watch ships no tool for this.
- JDK 17 or later and the Android SDK (platform 36, build-tools 36.0.0).
  Gradle is not used, and the Kotlin compiler is downloaded and checked on the
  first build.
- Node 22.18 or later and npm for the PC helper and MCP server.
- Python 3 for the Android build scripts.

Then:

```sh
# Build and install. The first build needs --new-key to create a signing key.
bash scripts/build-android.sh --new-key
adb install -r build/android/hypr-watch.apk

# Give the app your watch key. It stays in the app's private storage.
echo '{"auth_key": "<your 32 hex digits>"}' |
  adb shell "run-as local.hyprwatch sh -c 'mkdir -p files && cat > files/watch.json'"
```

Pair the watch in Android's Bluetooth settings, then open Hypr Watch.
Keep the signing key in `~/.local/state/hypr-watch/`. An APK signed with a
different key cannot update the app, and reinstalling wipes your data.

The build is debuggable so the PC helper can read the app's API token over
USB. That also means anyone with USB debugging access to your unlocked phone
can read the app's data, watch key included.

## Connect an agent

With Hypr Watch installed on the phone and Node 22 or later on a macOS or Linux
computer:

1. On the phone, open **Settings > Agents**, enable **Allow agents on Wi-Fi**,
   and select **Connect an agent**.
2. On the computer, on the same Wi-Fi, run `npx hypr-watch setup`.
3. Enter the code and accept registration with Claude Code or Codex.
   Restart your agent, then ask "how did I sleep this week?"

The MCP server stays on your computer over stdio. Setup checks existing
pairings and replaces the agents' user-level MCP entries. Use
`npx hypr-watch setup --host ip[:port]` if discovery is blocked.
See [plugin/README.md](plugin/README.md) for options and transport settings.

For the USB dashboard or checkout development, run `bash plugin/setup.sh`
to build and link the local CLI, then `hypr-watch usb --watch --open`.
This pauses Mi Fitness, connects the watch, and opens the PC dashboard.
`hypr-watch usb --release` hands the watch back to Mi Fitness. For agent access
from the checkout, run `node plugin/server/cli.ts setup` on Node 22.18 or later.
Setup registers the local source with the current Node executable.

## Documentation

- [docs/guide.md](docs/guide.md): how each feature works.
- [plugin/README.md](plugin/README.md): the MCP server and its tools.
- [API.md](API.md): the phone's local HTTP API.
- [CONTEXT.md](CONTEXT.md): architecture and protocol notes.
- [docs/watch-features.md](docs/watch-features.md): which watch features are
  supported.

## Contributing

Issues are welcome, especially reports from other watches or phones: say which
model and firmware you tried and what happened. Small, focused pull requests
are easier to review. Run `bash scripts/test-host.sh` before sending one.

## Credits and license

The Bluetooth protocol, authentication and health file parsers are ported from
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge). Thanks to its
contributors. [NOTICE](NOTICE) lists the ported files and their authors.

Hypr Watch is [MIT licensed](LICENSE), except for the files ported from
Gadgetbridge, which stay under [AGPL-3.0-or-later](LICENSE-AGPL). Because the
Android app includes them, the app as a whole is AGPL. [NOTICE](NOTICE) lists
which files are which.
