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

No cloud account, no telemetry. The PC side reaches the phone over USB only.

## Getting started

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
- Python 3.10 or later for the PC helper and the MCP server.

Then:

```sh
# Build and install. The first build needs --new-key to create a signing key.
bash scripts/build-android.sh --new-key
adb install -r build/android/hypr-watch.apk

# Give the app your watch key. It stays in the app's private storage.
echo '{"auth_key": "<your 32 hex digits>"}' |
  adb shell "run-as local.hyprwatch sh -c 'mkdir -p files && cat > files/watch.json'"

# Pair the watch in Android's Bluetooth settings, open Hypr Watch, then:
python scripts/connect-pc.py --watch --open
```

`connect-pc.py --watch` pauses Mi Fitness so it doesn't fight over the watch,
connects, and opens the dashboard on your PC. `--release` hands the watch back
to Mi Fitness. Keep the signing key in `~/.local/state/hypr-watch/`: an APK
signed with a different key can't update the app, and reinstalling wipes your
data.

The build is debuggable so the PC helper can read the app's API token over
USB. That also means anyone with USB debugging access to your unlocked phone
can read the app's data, watch key included.

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
