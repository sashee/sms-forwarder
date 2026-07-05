# sms-forwarder

Android app (Android 9 / API 28) that forwards incoming SMS and calls to HTTP endpoints and sends a periodic heartbeat. See [DESIGN.md](DESIGN.md) for architecture.

## Build

```sh
nix-build ./default.nix     # runs unit tests + produces ./result/app-release.apk
adb install -r ./result/app-release.apk
```

The release signature is deterministic (keystore committed under `signing/`), so `adb install -r` upgrades in place and **preserves existing app data**, including the log history.

## Retrieving logs

The app records events (SMS/call forwarding, heartbeats, boot, config changes) into an internal SQLite table. On the device this is only visible in the app's own UI. For debugging from a connected computer, the app exposes the full log table through a read-only `ContentProvider` that streams it as plain text over adb — no root, no debuggable build, no `adb pull`.

```sh
# Full table (chronological, one entry per line: <ISO-8601 local timestamp>\t<message>)
adb shell "content read --uri 'content://com.example.smsforwarder.logs/logs'"
```

### Filters

Pass filters as URI query parameters. **Quote the whole URI** — `&` is a shell metacharacter and `adb shell` re-parses the command on the device, so an unquoted `&` silently drops later parameters.

| Parameter  | Meaning                                              | Example                       |
|------------|------------------------------------------------------|-------------------------------|
| `since`    | Inclusive lower bound; epoch millis or ISO datetime  | `since=2026-07-01T00:00`      |
| `until`    | Inclusive upper bound; epoch millis or ISO datetime  | `until=1719792000000`         |
| `contains` | Case-insensitive substring match on the message      | `contains=heartbeat`          |
| `limit`    | Keep only the newest N entries (still output oldest-first) | `limit=500`              |

```sh
# Everything since a time, containing "heartbeat", capped at the newest 200
adb shell "content read --uri 'content://com.example.smsforwarder.logs/logs?since=2026-07-01T00:00&contains=heartbeat&limit=200'"
```

### Gotchas

- **Launch the app once after each (re)install.** A freshly installed app is in the *stopped* state and its provider is not reachable from adb until the app is launched once:
  ```sh
  adb shell am start -n com.example.smsforwarder/.MainActivity
  ```
- **Quote the URI** (see above) whenever it contains `&`.
- The table can be large (100k+ rows; heartbeat supervision logs are verbose). Each request loads and filters the whole table in memory, so a full dump can take a few seconds — prefer `limit`/`since`/`contains` when you can.
- The provider is exported without a permission (the target device is dedicated to this app). Any app on the device can read the logs; this is intentional for that deployment.
