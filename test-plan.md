# Robolectric Test Plan

## Scope

This plan covers only tests that can be implemented and run with Robolectric as part of the normal JVM test suite.

It excludes:
- on-device/manual tests
- instrumentation tests
- Nix/build contract tests
- live network integration against external services

## Goals

- Verify all app logic reachable in a JVM/Robolectric environment.
- Cover Android entry points (`Activity`, `BroadcastReceiver`, `Service`, `Worker`).
- Cover persistence, scheduling, and configuration behavior.
- Cover failure handling and recovery behavior.
- Cover the hybrid heartbeat reliability model: foreground service primary path plus `AlarmManager` recovery.
- Keep tests deterministic and independent of the local `robolectric/` source checkout.

## Test Categories

1. Pure logic tests
2. Repository and persistence tests
3. Worker tests
4. Receiver and service tests
5. Activity/UI tests
6. Manifest/security configuration tests

## 1. Pure Logic Tests

### Placeholder Rendering

File target:
- `app/src/main/java/com/example/smsforwarder/util/PlaceholderRenderer.kt`

Tests:
- renders SMS placeholders:
  - `{{sms.number}}`
  - `{{sms.text}}`
  - `{{sms.timestamp}}`
- renders call placeholders:
  - `{{call.number}}`
  - `{{call.timestamp}}`
- leaves unknown placeholders unchanged
- leaves heartbeat body unchanged
- renders empty string for unknown/private call number
- uses ISO-8601 UTC timestamps with `Z`

### Time Formatting

File target:
- `app/src/main/java/com/example/smsforwarder/util/TimeFormatter.kt`

Tests:
- formats epoch correctly
- formats arbitrary timestamps correctly
- always uses UTC / `Z`

### Retry Delay Logic

File target:
- `app/src/main/java/com/example/smsforwarder/work/EventDeliveryWorker.kt`

Tests:
- first retry delay is correct
- retry delay grows exponentially
- retry delay caps at 1 day
- retries beyond the cap remain at 1 day

### Enum Conversion

File target:
- `app/src/main/java/com/example/smsforwarder/data/RoomConverters.kt`

Tests:
- converts `EventType` to string and back for all enum values

## 2. Repository And Persistence Tests

### ConfigRepository

File target:
- `app/src/main/java/com/example/smsforwarder/data/ConfigRepository.kt`

Tests:
- default config values are returned when nothing is saved
- saving config persists:
  - heartbeat config
  - sms config
  - call config
- saved config round-trips correctly
- fault state save/load works
- fault state clear works
- call-screening-seen timestamp save/load works
- telephony-call-seen timestamp save/load works
- heartbeat last-attempt timestamp save/load works
- heartbeat last-success timestamp save/load works
- heartbeat foreground-service-seen timestamp save/load works
- log-trim timestamp save/load works

### EventRepository

File target:
- `app/src/main/java/com/example/smsforwarder/data/EventRepository.kt`

Tests:
- enqueue SMS creates queue entry with:
  - generated `eventId`
  - type `SMS`
  - correct number
  - correct text
  - correct timestamp
  - rendered body
  - copied config fields
- enqueue call creates queue entry with:
  - generated `eventId`
  - type `CALL`
  - correct number
  - empty text
  - correct timestamp
  - rendered body
- `markDelivered` removes queued event
- `scheduleRetry` updates attempt count and next attempt time
- `clearLogs` removes all logs
- deleting logs older than a cutoff removes only older rows
- `resetDatabase`:
  - clears queued events
  - clears existing logs
  - writes reset log entry
- `heartbeatConfig()` returns heartbeat config from preferences

### QueueDao

File target:
- `app/src/main/java/com/example/smsforwarder/data/QueueDao.kt`

Tests:
- insert and fetch by id
- fetch all returns events ordered by `nextAttemptAt`
- update attempt count and next attempt time
- delete by id
- delete all

### LogDao

File target:
- `app/src/main/java/com/example/smsforwarder/data/LogDao.kt`

Tests:
- inserted logs are returned newest first
- observe latest respects limit
- delete all clears logs

## 3. Worker Tests

### EventDeliveryWorker

File target:
- `app/src/main/java/com/example/smsforwarder/work/EventDeliveryWorker.kt`

Tests:
- returns failure for missing `event_id` input
- returns success when queue item no longer exists
- on HTTP `2xx`:
  - sends request with queued values
  - removes queued event
  - writes success log
- on HTTP non-`2xx`:
  - increments attempt count
  - schedules retry
  - writes retry log
- on thrown exception:
  - increments attempt count
  - schedules retry
  - writes retry log
  - does not set catastrophic fault state
- retry delay matches expected exponential schedule

### HeartbeatWorker

File target:
- `app/src/main/java/com/example/smsforwarder/work/HeartbeatWorker.kt`

Tests:
- skips and logs when heartbeat URL is blank
- sends heartbeat once when config is present
- skips and logs when a second heartbeat trigger arrives before the 30-minute interval is due
- sends again once the 30-minute interval has elapsed
- logs success on HTTP response
- logs failure on exception
- does not schedule retry on failure
- does not set catastrophic fault state on delivery failure
- updates heartbeat attempt timestamp on each run
- updates heartbeat success timestamp on success only
- when fault state is active and younger than 24 hours:
  - skips heartbeat send
  - writes skip log
- when fault state is older than 24 hours:
  - resets DB
  - clears fault state
  - writes reset log
  - preserves DataStore configuration
- on the first heartbeat execution of a UTC day:
  - trims logs older than 6 months
  - records log-trim timestamp
- on later heartbeat executions in the same UTC day:
  - does not trim logs again
- when heartbeat crosses a UTC day boundary:
  - trimming becomes eligible again on the first run of the new day
- when fault state is active and younger than 24 hours:
  - daily log trimming still runs before the heartbeat send is skipped
- same-day heartbeat runs do not update the stored log-trim timestamp

### EventScheduler

File target:
- `app/src/main/java/com/example/smsforwarder/work/EventScheduler.kt`

Tests:
- schedules unique delivery work for queued event
- reschedules queued events with zero delay when overdue
- reschedules queued events with remaining delay when in future
- applies network-required constraints to delivery work
- starts the heartbeat foreground service when recurring work is ensured
- schedules a heartbeat recovery alarm for the next due time
- cancels legacy `HeartbeatWorker` work tagged for older heartbeat scheduling before arming the current heartbeat path
- logs the exact recovery-alarm trigger time when scheduling heartbeat recovery

### HeartbeatForegroundService

File target:
- `app/src/main/java/com/example/smsforwarder/heartbeat/HeartbeatForegroundService.kt`

Tests:
- starts in the foreground with a persistent notification
- records foreground-service-seen timestamp
- sends heartbeat immediately when overdue or never attempted
- waits for the next due time when the last attempt is recent
- schedules the next recovery alarm each loop iteration
- keeps using single-attempt heartbeat semantics via shared heartbeat execution logic
- logs service start reason, loop state, next due time, and delay before sleeping
- logs whether the recovery alarm was already aligned or repaired

### HeartbeatAlarmReceiver

File target:
- `app/src/main/java/com/example/smsforwarder/heartbeat/HeartbeatAlarmReceiver.kt`

Tests:
- ignores unrelated actions
- starts the heartbeat foreground service for the app alarm action
- logs that the recovery alarm fired
- logs the previously stored recovery-alarm timestamp when the alarm fires

### BootReceiver

Additional tests:
- ignores unrelated broadcast actions
- schedules recurring heartbeat work on `BOOT_COMPLETED`
- schedules recurring heartbeat work on `LOCKED_BOOT_COMPLETED`
- writes boot log only for boot actions
- reschedules multiple queued events with the correct overdue vs future delays

### AppWorkerFactory

File target:
- `app/src/main/java/com/example/smsforwarder/work/AppWorkerFactory.kt`

Tests:
- creates `EventDeliveryWorker` for correct class name
- creates `HeartbeatWorker` for correct class name
- returns `null` for unknown worker class

## 4. Receiver And Service Tests

### SmsReceiver

File target:
- `app/src/main/java/com/example/smsforwarder/receiver/SmsReceiver.kt`

Tests:
- ignores unrelated broadcast actions
- handles `SMS_RECEIVED_ACTION`
- enqueues one event per SMS message part
- schedules delivery for each queued message
- writes queue log
- enqueue failure stores catastrophic fault state
- duplicate broadcasts produce duplicate queued events

### BootReceiver

File target:
- `app/src/main/java/com/example/smsforwarder/receiver/BootReceiver.kt`

Tests:
- schedules recurring heartbeat work on boot
- reschedules queued events on boot
- writes boot log
- works for `BOOT_COMPLETED`
- works for `LOCKED_BOOT_COMPLETED`
- re-arms the heartbeat service/alarm hybrid path via scheduler startup
- restores multiple queued deliveries with the correct relative delays

### SmsForwarderApp

File target:
- `app/src/main/java/com/example/smsforwarder/SmsForwarderApp.kt`

Tests:
- app startup initializes the container without automatically arming recurring heartbeat work

### ForwardingCallScreeningService

File target:
- `app/src/main/java/com/example/smsforwarder/telecom/ForwardingCallScreeningService.kt`

Tests:
- extracts incoming number from call details
- uses empty string for missing handle
- records call-screening seen timestamp
- enqueues call event
- schedules delivery
- responds with:
  - `setDisallowCall(true)`
  - `setRejectCall(true)`
  - `setSkipCallLog(true)`
  - `setSkipNotification(true)`
- enqueue failure stores catastrophic fault state
- fault-state path does not clear existing config

Note:
- The design says call flow should log both enqueue and reject outcomes. Add a direct test once the implementation logs the reject outcome from `onScreenCall()` rather than only queueing.
- Duplicate call-screening invocations should be allowed to create duplicate events, matching the design's duplicate-event rule.

## 5. Activity / UI Tests

### MainActivity Startup And Binding

File target:
- `app/src/main/java/com/example/smsforwarder/MainActivity.kt`

Tests:
- activity launches successfully
- save button exists
- clear logs button exists
- config fields are populated from saved preferences on launch
- log text updates when log entries exist
- log view shows only the latest 100 entries when more logs exist

### MainActivity Save Behavior

Tests:
- save button persists heartbeat config
- save button persists SMS config
- save button persists call config
- save button writes configuration saved log
- save button re-schedules recurring heartbeat work
- blank method defaults to `POST`
- blank content type defaults to `text/plain`

### MainActivity Status Indicators

Tests:
- SMS status shows `NOK` when permission missing
- SMS status shows `OK` when permission granted
- battery status shows `NOK` when optimization exemption missing
- battery status shows `OK` when exemption granted
- call-screening status shows `NOK` when no recent seen timestamp exists
- call-screening status shows `OK` when recent seen timestamp exists
- telephony fallback status shows `NOK` when no recent seen timestamp exists
- telephony fallback status shows `OK` when recent seen timestamp exists

### MainActivity Button Actions

Tests:
- SMS permission button requests `RECEIVE_SMS`
- battery button launches `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- call settings button launches `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`
- clear logs button clears persisted logs

Note:
- Robolectric coverage for the Activity Result permission launcher may require either a shadow-based assertion or a narrower test of the click wiring if direct request capture is not available.

## 6. HTTP Client Tests

### EventHttpClient

File target:
- `app/src/main/java/com/example/smsforwarder/net/EventHttpClient.kt`

Robolectric-compatible tests can use local in-process HTTP/HTTPS servers.

Tests:
- sends `POST` request body
- sets configured `Content-Type`
- sends `GET` without body
- returns response code for HTTP success
- returns response code for HTTP failure
- supports plain `http`
- supports `https` with app-bundled trust anchor
- rejects `https` certificates not rooted in bundled trust anchor
- falls back to the next DoH provider when the current one fails
- writes a log message when a DoH provider fails
- fails the request after all configured DoH providers fail
- bypasses DoH for literal IP request hosts
- supports both IPv4 and IPv6 bootstrap/resolved addresses in resolver configuration
- heartbeat delivery uses the shared HTTP client wiring for outbound requests

## 7. Manifest And Resource Configuration Tests

These can be verified with Robolectric package manager and resource access.

Tests:
- app manifest declares `RECEIVE_SMS`
- app manifest declares `RECEIVE_BOOT_COMPLETED`
- app manifest declares `INTERNET`
- `SmsReceiver` is registered for `android.provider.Telephony.SMS_RECEIVED`
- `BootReceiver` is registered for boot actions
- `ForwardingCallScreeningService` is registered with `android.permission.BIND_SCREENING_SERVICE`
- app uses `@xml/network_security_config`
- app enables cleartext traffic for Android 9 HTTP support
- raw CA resource `nixpkgs_cacert` is packaged and readable

## 8. Fault Recovery Behavior Tests

These overlap multiple components but should be explicitly covered.

Tests:
- SMS receiver DB enqueue failure sets fault state without clearing config
- call screening DB enqueue failure sets fault state without clearing config
- worker/network delivery failure does not set fault state
- heartbeat during active fault state skips send
- heartbeat after 24-hour fault age resets DB
- DB reset preserves DataStore configuration
- fault state persists across app/container recreation and is still honored by heartbeat
- saved event configuration persists across app/container recreation
- saved log-trim timestamp persists across app/container recreation

## 9. Current Coverage vs Missing Coverage

Currently implemented:
- `PlaceholderRendererTest`
- `MainActivityTest` smoke test
- DAO, repository, worker, receiver/service, HTTP client, and resource tests

High-priority tests to add next:
1. Real `SMS_RECEIVED_ACTION` / PDU entrypoint coverage if feasible under Robolectric
2. Real `Call.Details` extraction coverage if feasible under Robolectric
3. Main activity save-flow assertions for the saved log and recurring work reschedule
4. Scheduler assertions for network constraints and 30-minute heartbeat cadence
5. Remaining small unit/DAO coverage gaps (`observeLatest(limit)`, `deleteAll`, retry-delay cap persistence)
6. Process-recreation persistence coverage for fault state, saved config, and log-trim timestamp

## 10. Notes

- Prefer fakes or test doubles for HTTP delivery, scheduler behavior, and repositories where possible.
- Use in-memory Room databases for repository/DAO tests.
- Use Robolectric shadows for Android system services and started intents.
- Keep tests independent of external network, device state, and the local `robolectric/` source tree.
