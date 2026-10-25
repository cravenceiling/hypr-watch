# Hypr Watch local plugin

Run `bash setup.sh` to install the pinned official MCP SDK privately into
`server/vendor`. Install this local stdio plugin using your agent host's local
plugin support, or register `python3 /absolute/path/server/main.py` as an MCP
server. The project installation already uses its private `.deps` directory.

This plugin operates a locally attached Android phone over authorized USB
and its Hypr Watch API. The phone app and private watch configuration must
already be installed. Run the project's `scripts/connect-pc.py` first.

Tools:

- Notes: `list_notes`, `create_note`, `update_note`, `delete_note`.
  `create_note` and `update_note` accept `send_to_watch`.
- Watch: `watch_status`, `connect_watch`, `disconnect_watch`,
  `read_watch_reminders`, `sync_watch_notes`, `device_status`, `find_watch`,
  `find_phone_stop`, `sync_watch_time`. Connect pauses Mi Fitness reversibly
  and force-stops Gadgetbridge; disconnect restores Mi Fitness's prior enabled
  state.
- Weather and music: `weather_get`, `weather_set`, `weather_refresh`,
  `music_status`, `music_set_enabled`.
- Alarms: `list_alarms`, `create_alarm`, `update_alarm`, `delete_alarm`,
  `get_sleep_schedule`, `set_sleep_schedule`. `update_alarm` and
  `delete_alarm` take an optional `expect`, the alarm as listed; a changed or
  missing alarm fails instead of changing an alarm that reused its id.
- Watch settings and live heart rate: `get_watch_settings`,
  `set_watch_settings`, `get_live_heart_rate`, `start_live_heart_rate`.
- Watch faces: `list_watch_faces`, `set_watch_face`, `delete_watch_face`,
  `install_watch_face`. Installation accepts a local `.bin` watch-face file,
  not firmware. Follow its progress in `watch_status`.
- Contacts and replies: `get_favourite_contacts`, `set_favourite_contacts`,
  `get_quick_replies`, `set_quick_replies`.
- Health: `health_status`, `sync_health`, `health_days`, `health_series`,
  `health_sleep`, `health_samples`, `health_workouts`, `get_health_profile`,
  `set_health_profile`. Dates are `YYYY-MM-DD`, at most 400 days per call.
  `health_series` takes `steps`, `heart_rate`, `spo2` or `stress` and a
  bucket of 1, 5, 15 or 60 minutes. `health_status` includes the Health
  Connect export's status under `health_connect`; turning the export on is
  left to the phone, where Health Connect asks for access.
- Health insights: `health_period_summary`, `health_trend`,
  `health_sleep_analysis`. They read the same phone routes and return small
  computed summaries instead of raw rows, so an agent can answer "how did I
  sleep this month?" or "this week vs last" in one call.
  `health_period_summary` compares a range with the same number of days just
  before `start_date`, not with the calendar's previous month. An `end_date`
  after today is cut to today. When the two ranges have day data on a different
  number of days, `change` leaves out the `*_total` fields.
  `health_trend` takes one `health_days` field and reports a least-squares
  slope per week. It leaves out today, which may be partial. It calls a
  direction `up` or `down` only with at least 14 days of data and a slope that
  passes a two-sided 95% t test; below 7 days there is no slope. Its
  `sleep_minutes` sums every session that ended that day, naps included.
  `health_sleep_analysis` reports duration, bedtime and wake-time regularity,
  stage shares and short nights (`short_night_minutes`, default 360). Each
  result has a `notes` list for missing, partial or sparse data. They report
  numbers only, not medical advice.
- Notifications: `get_notification_settings`, `set_notification_settings`
  (allowed package names and the calls toggle).

The plugin validates tool arguments before calling the phone. No firmware
upload, raw health file, backup or authentication-key tool is exposed.

No account tokens, watch keys or user notes are included. Cloud-hosted ChatGPT
sessions cannot use this local USB process merely by uploading the package.
