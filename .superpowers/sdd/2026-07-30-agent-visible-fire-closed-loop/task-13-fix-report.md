# Task 13 review fixes

## Result

All five findings in `task-13-review.md` are addressed.

- The authoritative dual-stream Agent heartbeat/status/capability/poll/ACK endpoints now use the dedicated Agent JWT. The Android client attaches the token to all five calls, and the interceptor binds the JWT drone SN to the path drone SN.
- Detector heartbeat fields, including the intent version and observation timestamp, survive cached copies and Redis restoration.
- Desired detector intent is persisted in Redis with a monotonically increasing version. A Lua script atomically increments the version and writes the desired record, so concurrent ARM/DISARM cannot publish an older record after a newer one.
- Backend polling and heartbeat receipt reconcile desired versus observed state. Rapid ARM then DISARM coalesces to DISARM only; restart-before-poll restores the desired command.
- Agent desired intent is synchronously persisted in SharedPreferences before ACK. Restart restores it; same-version commands are idempotent; stale versions are ignored; conflicts, invalid versions, and persistence failures fail closed.
- Backend `running=true` now requires real Agent states `STREAMING + RUNNING` and the legal `ARMED/ARMED/HEALTHY` tuple. Stale, contradictory, blocked, disconnected, and malformed observations remain false with an explicit reason.
- Obsolete commented localization/AI-service test bodies and vacuous assertions were removed.

## Verification

- Focused backend security, detector intent, copy/restore, and status tests: passed.
- Full backend suite: 500 tests, 0 failures, 0 errors, 0 skipped.
- Full Agent JVM suite: 484 tests, 0 failures, 0 errors, 0 skipped.
- Debug APK assembly: passed.
- APK: `rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `f126a546c4bb82eb02e173eacc2223d0e8a486fb59fe611893f9bd377ff238af`
- `git diff --check`: passed.

## Remaining acceptance boundary

RC Plus is unavailable, so this does not claim device, real-flight, real-laser, network-loss, or process-kill acceptance. Per the agreed temporary rule, NCNN evidence remains the provisional runtime substitute until device access returns.
