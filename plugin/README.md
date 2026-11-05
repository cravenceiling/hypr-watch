# Hypr Watch

Connect Claude Code or Codex to your phone's notes, watch settings, alarms,
and health data. The MCP server runs on your computer over stdio.
Runs on macOS and Linux. Requires Node 22 or later and npm.
Windows is not supported.

1. On the phone, open **Settings > Agents**, enable **Allow agents on Wi-Fi**,
   and select **Connect an agent**.
2. On a computer on the same Wi-Fi, run `npx hypr-watch setup`.
3. Enter the phone's code and accept registration with Claude Code or Codex.
   Restart your agent, then ask "how did I sleep this week?"

Setup detects installed agents and replaces their `hypr-watch` MCP entry at
user scope. Re-running setup replaces the whole entry, dropping extra env
settings and timeouts. Run it again to check an existing pairing or register another agent.
Use `--claude` or `--codex` to select agents and `--yes` to accept registration
without questions. The phone code is still required for a new pairing.

```sh
# If discovery is blocked, specify the phone's address.
npx hypr-watch setup --host 192.168.1.23:8767 --name Laptop --codex --yes
npx hypr-watch status
npx hypr-watch unpair
```

Pairing pins the phone's TLS identity. Credentials stay in
`$XDG_STATE_HOME/hypr-watch/pairing.json`, defaulting to
`~/.local/state/hypr-watch/pairing.json`, with mode 0600 in a 0700 directory.
`unpair` deletes the local copy. To invalidate access, revoke the computer
under **Paired computers** on the phone.

The 50 tools cover notes, watch connection, alarms, sleep schedules, weather,
music, watch faces, contacts, quick replies, notifications, health records, and
health summaries. Firmware, raw health files, backups, and watch keys are not
exposed. Wi-Fi serves the API only. Allow unrestricted battery use on the
phone if agent access stops while idle.

`HYPR_WATCH_TRANSPORT=auto` tries paired Wi-Fi first and falls back to USB only
before a request is written. Changed identities and revoked credentials never
fall back. Set it to `lan` or `usb` to require that transport.
`ANDROID_SERIAL` selects the USB phone. The PC dashboard and export require USB.
Agents do not inherit your shell environment. Put `HYPR_WATCH_TRANSPORT` and
`ANDROID_SERIAL` in the agent entry's env, using `-e` for Claude Code or `--env`
for Codex. For example, after removing the existing entry:

```sh
claude mcp add -s user -e HYPR_WATCH_TRANSPORT=usb -e ANDROID_SERIAL=DEVICE_SERIAL hypr-watch -- npx --prefer-offline --fetch-retries=0 --fetch-timeout=5000 -y hypr-watch@0.1.1 mcp
codex mcp add --env HYPR_WATCH_TRANSPORT=usb --env ANDROID_SERIAL=DEVICE_SERIAL hypr-watch -- npx --prefer-offline --fetch-retries=0 --fetch-timeout=5000 -y hypr-watch@0.1.1 mcp
```

When `XDG_STATE_HOME` is set, setup includes it in the entry with
`-e XDG_STATE_HOME=...` or `--env XDG_STATE_HOME=...` so the server finds your
pairing. Preserve that flag when editing the entry yourself. Setup warms the
npm cache once before returning. If warming fails, it warns so you can retry
before launching agents.

## Contribute

From the repository root, install dependencies and build with
`bash plugin/setup.sh`. It also links the local CLI. Then run
`node plugin/server/cli.ts setup` or `hypr-watch setup`.
Checkout registration uses the current Node executable's absolute path and
prefers `server/cli.ts`, so a stale build is never selected on Node versions
with type stripping enabled by default. These include Node 22.18+, 23.6+, and
24 or later. Older Node versions use `dist/cli.js` and warn if `server/` is
newer. Run `npm run build` in `plugin/` to refresh it. `setup.sh` runs
`npm link`, which makes an `npx` registration resolve to the linked checkout.
`node plugin/server/main.ts` also runs the stdio server.

For USB and the dashboard, run `hypr-watch usb --watch --open`.
`hypr-watch usb --release` restores Mi Fitness's prior state. Without USB,
watch connection tools report that Mi Fitness was not paused.
Use `hypr-watch --help` for other CLI operations.

```sh
cd plugin
npm ci
npm run typecheck
npm test
npm pack
```

Tests use fake executables and local TLS phones, never a real phone. OpenSSL
must be on PATH. `npm pack` builds ESM JavaScript in `dist/`. The npm package
contains that build, this README, the MIT license, and `npm-shrinkwrap.json`
to pin dependency versions. `prepack` cleans `dist/` before rebuilding.
