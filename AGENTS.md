# AGENTS.md

Guidance for AI agents working on this repo. See [DESIGN.md](DESIGN.md) for full architecture and [README.md](README.md) for user-facing docs.

## What this app is

An Android 9 (API 28) app, package `com.example.smsforwarder`, that forwards incoming SMS and calls to HTTP endpoints and sends a 30-minute heartbeat. Built with Nix.

- Build + test: `nix-build ./default.nix` → runs `testDebugUnitTest` then `assembleRelease` → `./result/app-release.apk`.
- Do **not** try `gradle` directly in `nix-shell` for a full build — offline dependency resolution needs the vendored Maven repo and init script that `default.nix` wires up. Use `nix-build`. (`nix-shell` + `gradle` can compile but fails to resolve the Android plugin offline.)
- The release signature is deterministic (keystore in `signing/`), so `adb install -r ./result/app-release.apk` preserves on-device data.

## Debugging a live device: getting the logs

The device is reachable via `adb`. The app keeps its authoritative log in an internal Room/SQLite table (`logs`); it is **not** reachable via `run-as` (release build) or the filesystem (no root). Instead, read it through the app's `ContentProvider`:

```sh
# newest 300 entries
adb shell "content read --uri 'content://com.example.smsforwarder.logs/logs?limit=300'"
```

Output is one entry per line: `<ISO-8601 local timestamp>\t<message>`, oldest-first. Filters (`since`, `until`, `contains`, `limit`) are URI query params — see the README table.

Two things that will bite you:
1. **Quote the URI.** `&` between params is a shell operator; `adb shell` re-parses on-device, so an unquoted `&limit=…` is silently dropped. Always wrap the URI in quotes.
2. **Launch the app once after any (re)install** — `adb shell am start -n com.example.smsforwarder/.MainActivity` — or the provider is unreachable (freshly installed apps are in the *stopped* state).

Prefer `contains`/`since`/`limit` over a full dump: the table can exceed 100k rows and each request filters the whole table in memory. `logcat` is **not** a substitute — on the target device it is flooded by vendor spam and app lines are evicted within seconds.

Do not add unfiltered SMS bodies or numbers to new log lines beyond what is already logged; the provider is exported without a permission on the dedicated device, so treat log contents as readable by anything on that phone.

## Log-export feature map

If you touch the log-retrieval path, these are the pieces:

- `app/src/main/java/com/example/smsforwarder/data/LogExportProvider.kt` — the provider. `openFile()` builds the payload via `exportText(uri)` and streams it over an `ParcelFileDescriptor.createPipe()` **on a background thread** (writing >64KB from the calling thread before the reader drains would deadlock). Read-only: `query` returns null, writes throw.
- `app/src/main/java/com/example/smsforwarder/util/LogQuery.kt` — pure filter model. `fromUri` parses params (epoch millis, ISO-offset, or ISO-local datetime); `applyLogQuery` filters/limits/sorts. **In-memory filtering is intentional** — no dynamic SQL from URI input (injection-safe, testable). Keep it pure.
- `app/src/main/java/com/example/smsforwarder/util/UiLogFormatter.kt` — `formatExport(logs)` produces the `timestamp\tmessage` lines (newlines/CRs sanitized to spaces).
- `app/src/main/java/com/example/smsforwarder/data/LogDao.kt` / `EventRepository.kt` — `getAll()` / `getAllLogs()` read the whole table oldest-first.
- `app/src/main/AndroidManifest.xml` — `<provider>` for authority `com.example.smsforwarder.logs`, `exported="true"`, no permission.

### Testing notes

- Tests are Robolectric unit tests run by `nix-build`. Provider/query/formatter/dao coverage: `LogExportProviderTest`, `LogQueryTest`, `UiLogFormatterTest`, `DaoTest`, `EventRepositoryTest`, `ManifestAndResourceTest`.
- **Robolectric does not stream real OS pipes.** Do not assert on bytes read back from `openFile()`'s pipe — it comes back empty. Test the content assembly via the extracted `LogExportProvider.exportText(uri)` instead (that is why it exists). The actual pipe streaming is verified on-device only.
- For provider tests, seed data through the app container (`installTestContainer()` + `testAppContainer().eventRepository`) and obtain the provider with `Robolectric.setupContentProvider(LogExportProvider::class.java, "com.example.smsforwarder.logs")` so it reads the same container.
