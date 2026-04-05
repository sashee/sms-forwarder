# Android App Design

Last updated: 2026-04-04

## Requirements
- Target device is fixed: Android 9 (API 28).
- On SMS received, send an HTTP request with sender and message contents.
- On incoming phone call, send an HTTP request with number and reject the call.
- Send a heartbeat HTTP request every 30 minutes.
- Continue working after phone restart.
- UI is minimal: basic configuration and basic logs.
- Logs are retained for 6 months and trimmed automatically during the first heartbeat execution of each UTC day.
- Build with Nix; `nix-build` should produce an installable APK.
- Run automated tests as part of the build.

## Architecture and Flows
- Hands-off operating model: after initial setup (permissions + config), app should run without routine manual action.
- SMS flow: receive SMS -> generate `eventId` -> enqueue event in DB queue -> worker delivers HTTP -> retry policy applies.
- Call flow: incoming call -> generate `eventId` -> enqueue event in DB queue -> attempt call reject -> log both enqueue/reject outcomes.
- Heartbeat flow: `AlarmManager`, `WorkManager`, and a persistent foreground service all act as heartbeat supervision triggers; each trigger runs the same supervisor path that repairs missing infrastructure and sends a heartbeat only when the persisted 30-minute slot is due.
- Heartbeat send execution is deduplicated by persisted last-attempt state so overlapping triggers inside the same 30-minute interval do not send duplicate heartbeat HTTP requests.
- Reboot flow: scheduler/receivers resume processing after reboot.
- Multipart SMS is handled per part as separate events (no reassembly).
- Duplicate broadcasts are allowed to produce duplicate events.

## Configuration

### Storage
- Configuration is stored in preferences (`DataStore`/prefs), not in DB.
- Configuration must survive DB reset/corruption recovery.

### Event Configs
Each event type has independent HTTP config with only these fields:
- `url` (required)
- `method` (required)
- `content_type` (required, default `text/plain`)
- `body` (required field; may be empty)

Applies to:
- `heartbeat`
- `sms`
- `call`

### Placeholders
- Placeholder replacement is best-effort; unknown placeholders remain unchanged.
- Timestamp placeholders are ISO-8601 UTC (`Z`).

Supported placeholders:
- SMS: `{{sms.number}}`, `{{sms.text}}`, `{{sms.timestamp}}`
- Call: `{{call.number}}`, `{{call.timestamp}}`
- Heartbeat: no placeholders required

Special values:
- Private/unknown caller maps `{{call.number}}` to empty string.

### HTTP Behavior
- Both `http` and `https` endpoints are allowed.
- Because the target device is Android 9 (API 28), the app must explicitly opt in to cleartext traffic so configured `http` endpoints work.
- For `https`, the app must use an app-bundled CA store derived from `nixpkgs` `cacert` and must not rely on the device system CA store.
- Hostname resolution for outbound webhook and heartbeat requests must use DNS-over-HTTPS instead of the device resolver when the request URL host is a hostname rather than a literal IP.
- The app ships a fixed DoH provider list in fallback order: Cloudflare, Google, then Quad9.
- Each DoH provider configuration includes both IPv4 and IPv6 bootstrap IP addresses so the app can reach the DoH endpoint without depending on system DNS.
- If one DoH provider fails, the app logs that failure and tries the next configured provider before failing the outbound request.
- Literal IPv4/IPv6 request hosts bypass DoH lookup and connect directly.
- No additional/custom HTTP headers are supported.
- No in-app body validation or method/body constraint validation.
- Any HTTP `2xx` response is success.
- HTTP client timeout values use library/platform defaults.

## Reliability and Fault Recovery
- SMS/call deliveries are worker-based and retried automatically.
- Retry policy for SMS/call:
  - exponential backoff up to max 1 day
  - after reaching 1 day delay, continue retries indefinitely at 24-hour intervals
- Heartbeat delivery has no retry: if send fails, log and wait for the next 30-minute slot.
- Heartbeat timing is still best-effort because Android/Huawei background behavior is not fully controllable. Exact alarms, periodic WorkManager, and the persistent foreground service are all used together so any one surviving wake path can repair the others.
- The app does not arm heartbeat scheduling from generic app-process startup; recurring heartbeat setup/repair is driven by explicit config-save, boot recovery, alarm fire, and other natural app wake paths such as SMS/call handling or opening the UI.
- `ensureRecurringWork()` must cancel legacy `HeartbeatWorker` WorkManager entries left behind by older app versions before arming the current multi-trigger heartbeat path.
- Heartbeat logs should explain wake reason, due-time decision, send/skip reason, and recovery-alarm scheduling/repair state to support device-side diagnosis.
- Any wake path that enters heartbeat supervision should ensure the watchdog work, recovery alarm, and foreground service are present; if heartbeat is already due, that same supervision pass may send it immediately.
- Log retention cleanup runs from the heartbeat path at most once per UTC day and deletes log rows older than 6 months.

Catastrophic fault mode:
- Trigger condition: only when SMS/call handler cannot enqueue an event in DB.
- Fault state is stored in preferences (`DataStore`/prefs) with reason + timestamp.
- While fault state is active, heartbeat still runs on schedule but does not send HTTP ping.
- If fault state remains active for 24 hours, heartbeat performs automatic full DB reset.
- DB reset clears DB data (queue/logs) but does not clear configuration in prefs.
- After reset, clear fault state and resume normal operation.
- After reset, write a log entry in the new DB with reset timestamp and original fault reason.
- Worker/network delivery errors do not trigger catastrophic fault mode.

## Permissions and UI
- SMS permission scope is `RECEIVE_SMS` only (`READ_SMS` is out of scope).
- Call handling uses `CallScreeningService` on Android 9.
- Android 9 does not support the `RoleManager` call-screening role request flow; setup must direct the user to the device's system call-screening/caller-ID settings if available.
- UI includes button to request standard Android battery optimization exemption.
- Heartbeat reliability may use a permanent foreground notification.
- Setup/status UI shows `OK`/`NOK` for:
  - SMS permission
  - call screening enabled
  - battery optimization exemption
- If required permission/access is not granted, status must clearly indicate action is needed.
- UI includes manual `Clear logs` action.

## Build/Testing/Deployment
- Deployment model is private/non-Play (not published on Google Play).
- Nix uses non-flake setup (`default.nix`/`shell.nix`) for now.
- Build inputs are pinned (toolchain + Gradle dependency locks).
- `nix-build` contract:
  - build app
  - run all automated tests (including unit + Robolectric)
  - produce installable APK in `result/`

## Open Decisions
- None currently.

## Decision Log
| ID | Date | Decision |
|---|---|---|
| D-001 | 2026-03-05 | Use `DESIGN.md` as a living design document in the repo root. |
| D-002 | 2026-03-05 | The owner drives requirements and decision order; no unstated assumptions. |
| D-003 | 2026-03-05 | MVP includes SMS forwarding, call-event forwarding + call reject, 30-minute heartbeat, reboot persistence, and minimal config/log UI. |
| D-004 | 2026-03-05 | Use Nix build outputs for APK generation and include automated tests in the build pipeline. |
| D-005 | 2026-03-05 | Distribution is private/non-Play (not published in Google Play). |
| D-006 | 2026-03-05 | Heartbeat is scheduled with `WorkManager` as periodic work at a 30-minute target cadence (best-effort timing). |
| D-007 | 2026-03-05 | UI includes a button to request standard Android battery optimization exemption. |
| D-008 | 2026-03-05 | Scope includes only standard Android battery optimization exemption; no OEM-specific battery manager flows. |
| D-009 | 2026-03-05 | Post-install setup requires user to tap the battery-exemption button for intended background reliability. |
| D-010 | 2026-03-05 | HTTP configuration is per event type: `heartbeat` has `url` + `method`; `sms` and `call` have `url` + `method` + `body` template. |
| D-011 | 2026-03-05 | Request body templates are raw text (not JSON-only). |
| D-012 | 2026-03-05 | SMS placeholders are `{{sms.number}}`, `{{sms.text}}`, and `{{sms.timestamp}}`. |
| D-013 | 2026-03-05 | Call placeholders are `{{call.number}}` and `{{call.timestamp}}`. |
| D-014 | 2026-03-05 | HTTP retries are automatic, non-configurable, and apply to any error. |
| D-015 | 2026-03-05 | Retry window is long-running, up to 24 hours from event time. |
| D-016 | 2026-03-05 | Timestamp placeholders use ISO-8601 format. |
| D-017 | 2026-03-05 | Retry uses exponential backoff with maximum delay of 1 hour. |
| D-018 | 2026-03-05 | Retry continues until 24 hours after event timestamp. |
| D-019 | 2026-03-05 | Logs are not deleted automatically. |
| D-020 | 2026-03-05 | UI includes a manual action to clear logs. |
| D-021 | 2026-03-05 | Heartbeat delivery is single-attempt only; no retry scheduling for heartbeat failures. |
| D-022 | 2026-03-05 | If heartbeat delivery fails, record it in logs and wait for the next 30-minute heartbeat slot. |
| D-023 | 2026-03-05 | SMS and call deliveries use worker-based scheduling with retries. |
| D-024 | 2026-03-05 | SMS and call retries continue indefinitely (no retry cutoff). |
| D-025 | 2026-03-05 | SMS and call retry backoff remains exponential with a maximum delay of 1 day. |
| D-026 | 2026-03-05 | Decisions `D-015`, `D-017`, and `D-018` are superseded by `D-024` and `D-025` for SMS/call retry behavior. |
| D-027 | 2026-03-05 | SMS permission scope is `RECEIVE_SMS` only for MVP; `READ_SMS` is out of scope. |
| D-028 | 2026-03-05 | Call detection/rejection uses Android call screening role; UI includes a button to request this role. |
| D-029 | 2026-03-05 | UI includes a button to request SMS permission (`RECEIVE_SMS`). |
| D-030 | 2026-03-05 | Setup UI shows grant status for SMS permission, call screening role, and battery optimization exemption. |
| D-031 | 2026-03-05 | Nix setup uses non-flake configuration (`default.nix`/`shell.nix`) for now. |
| D-032 | 2026-03-05 | Build and dependency inputs are pinned, including Nixpkgs/toolchain and Gradle dependency locks. |
| D-033 | 2026-03-05 | `nix-build` produces an installable APK artifact. |
| D-034 | 2026-03-05 | Automated tests run in `nix-build` include unit tests and Robolectric tests. |
| D-035 | 2026-03-05 | HTTP configuration is separate per event type (`heartbeat`, `sms`, `call`). |
| D-036 | 2026-03-05 | Each event config includes only `url`, `method`, `content_type`, and `body`. |
| D-037 | 2026-03-05 | No additional/custom HTTP headers are supported. |
| D-038 | 2026-03-05 | Placeholder replacement is best-effort; unknown placeholders are left unchanged. |
| D-039 | 2026-03-05 | No body validation is performed in the UI. |
| D-040 | 2026-03-05 | Any HTTP `2xx` response is treated as success. |
| D-041 | 2026-03-05 | Decision `D-010` is superseded by `D-035` and `D-036`. |
| D-042 | 2026-03-05 | Exact Android version support (`minSdk`/tested versions) is deferred until target device details are available. |
| D-043 | 2026-03-05 | Setup UI must reflect call-screening role/permission state directly on the button/status so grant requirements are visible. |
| D-044 | 2026-03-05 | For private/unknown callers, `{{call.number}}` resolves to empty string. |
| D-045 | 2026-03-05 | Call flow order is: enqueue HTTP event first, then attempt call rejection; both outcomes are logged. |
| D-046 | 2026-03-05 | Delivery queue items are deleted after successful processing; only pending/retrying items remain in the queue store. |
| D-047 | 2026-03-05 | Logs are append-only in local DB storage; write a new log entry for each meaningful state change. |
| D-048 | 2026-03-05 | Log retention remains manual-clear only; no automatic pruning/rotation is implemented. |
| D-049 | 2026-03-05 | All timestamp placeholders use ISO-8601 UTC format (`Z`). |
| D-050 | 2026-03-05 | No in-app method/body validation constraints are enforced; behavior is validated by testing configured endpoints. |
| D-051 | 2026-03-05 | `content_type` is required for each event config; default is `text/plain`. |
| D-052 | 2026-03-05 | After backoff reaches 24 hours, SMS/call retries continue indefinitely at 24-hour intervals. |
| D-053 | 2026-03-05 | After reboot, pending SMS/call retry processing resumes via scheduler-triggered execution. |
| D-054 | 2026-03-05 | `nix-build` must build the app, run all automated tests, and produce an installable APK in `result/`. |
| D-055 | 2026-03-05 | Setup UI permission/role status uses simple `OK`/`NOK` indicators with buttons to grant missing access. |
| D-056 | 2026-03-05 | HTTP client timeout values use library/platform defaults (no explicit app-level timeout configuration). |
| D-057 | 2026-03-05 | Both `http` and `https` endpoints are allowed; no scheme validation/restriction is applied. |
| D-058 | 2026-03-05 | `eventId` is generated when an SMS or call event is received. |
| D-059 | 2026-03-05 | Multipart SMS is processed per part as separate events; no message reassembly is performed. |
| D-060 | 2026-03-05 | Log entry schema is minimal: `timestamp` and `text`. |
| D-061 | 2026-03-05 | Catastrophic fault mode is triggered only when SMS/call handler cannot enqueue an event in DB. |
| D-062 | 2026-03-05 | On catastrophic fault, write global fault state with reason and timestamp. |
| D-063 | 2026-03-05 | While fault state is active, heartbeat still runs on schedule but skips sending heartbeat HTTP requests. |
| D-064 | 2026-03-05 | If fault state remains active for 24 hours, heartbeat performs automatic full DB reset. |
| D-065 | 2026-03-05 | After automatic DB reset, clear fault state so normal operation resumes. |
| D-066 | 2026-03-05 | After automatic DB reset, write a log entry in the new DB that includes reset timestamp and original fault reason. |
| D-067 | 2026-03-05 | Worker delivery/network errors do not trigger catastrophic fault mode. |
| D-068 | 2026-03-05 | Global fault state is stored in preferences (`DataStore`/prefs), not in the DB. |
| D-069 | 2026-03-05 | Duplicate SMS/call broadcasts are allowed to produce duplicate events (no deduplication). |
| D-070 | 2026-03-05 | Automatic DB reset clears only DB data (queue/logs); app configuration is retained in preferences (`DataStore`/prefs). |
| D-071 | 2026-03-05 | The app is hands-off after initial setup: once permissions and configuration are granted, no routine manual operation is required. |
| D-072 | 2026-03-05 | The app must automatically recover from reboot and DB fault scenarios without manual intervention. |
| D-073 | 2026-03-05 | Configuration must persist across DB corruption recovery and DB reset flows; recovery must never wipe configuration. |
| D-074 | 2026-03-05 | Operational failure visibility is primarily via missing heartbeats (with local logs as secondary diagnostics). |
| D-075 | 2026-04-03 | Target deployment is the specific Android 9 (API 28) device, so `minSdk` is 28 and Android 9 is the required tested version. |
| D-076 | 2026-04-03 | On Android 9, call handling uses `CallScreeningService` without `RoleManager`; setup UI must guide the user to system call-screening/caller-ID settings instead of requesting a role directly. |
| D-077 | 2026-04-03 | Because Android 9 disables cleartext traffic by default for apps targeting API 28+, the app must explicitly opt in so configured `http` endpoints are supported. |
| D-078 | 2026-04-03 | For `https`, the app must trust only an app-bundled CA set derived from `nixpkgs` `cacert` and must not rely on the device system CA store. |
| D-079 | 2026-04-04 | Heartbeat reliability is a priority. The current design uses `AlarmManager`, `WorkManager`, and a persistent foreground service as overlapping heartbeat supervision triggers, with one shared supervisor path responsible for repair and due-slot execution. |
| D-080 | 2026-04-04 | Outbound hostname resolution uses DoH with fixed provider fallback order `Cloudflare -> Google -> Quad9`, with both IPv4 and IPv6 bootstrap addresses per provider and app-log entries when a DoH provider fails. |
| D-081 | 2026-04-04 | Logs are retained for 6 months and trimmed automatically during the first heartbeat execution of each UTC day. |
| D-082 | 2026-04-04 | Heartbeat HTTP sends are deduplicated by persisted last-attempt state, and recurring-heartbeat setup must cancel legacy `HeartbeatWorker` WorkManager state from older app versions. |
| D-083 | 2026-04-04 | Heartbeat tracing logs must explain why the service woke, why a heartbeat was sent or skipped, and what recovery alarm timing was already present or newly scheduled. |
| D-084 | 2026-04-04 | Natural app wake paths such as boot, config-save, UI resume, SMS handling, and call handling enter the same heartbeat supervisor path used by alarms, workers, and the foreground service. |
