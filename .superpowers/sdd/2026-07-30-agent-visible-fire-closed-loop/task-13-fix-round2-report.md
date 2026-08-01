# Task 13 review fixes round 2

## Result

All six findings in `task-13-rereview-1.md` are addressed.

- Poll reconciliation now uses the latest fresh persisted Agent observation. Equal desired/observed intent versions converge to an empty command queue across repeated polls and backend restart.
- Redis is read on every authoritative desired-state decision. A process-local cache is used only by the explicit test fallback and cannot override newer Redis state in production.
- Observed-ahead versions are recovered with a Redis compare-and-set Lua script that raises the version floor before republishing the backend-owned intent. ARM and DISARM are covered in both directions.
- Operator ARM/DISARM uses the latest persisted Agent observation as a version floor. If both the Redis desired record and counter are lost, a new operator command still advances beyond the Agent's retained version. Missing/read-failed authority remains fail-closed until an operator establishes a new desired intent.
- Backend `running=true` now also requires an available Redis authority record whose intent and version exactly match the fresh legal Agent observation. Unavailable, conflicting, and reconciling states expose distinct reasons.
- Persisted Agent ARM starts `BLOCKED/UNHEALTHY` with `authority-reconciliation-required`; it cannot request detector arming until a current backend command confirms authority. A newer offline DISARM applies without an interim healthy ARM window.
- SharedPreferences read exceptions and wrong stored types produce a controlled fail-closed diagnostic. Persistence failure leaves desired state unchanged.
- All five dual-stream Agent calls share one controlled 401/403 path: invalidate the cached JWT, issue once, and retry once. Heartbeat, poll, and ACK are covered; ACK retry does not execute the detector command twice.

## Verification

- Full backend suite: 511 tests, 0 failures, 0 errors, 0 skipped.
- Full Agent JVM suite: 491 tests, 0 failures, 0 errors, 0 skipped.
- Debug APK assembly: passed.
- APK: `rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk`
- APK size: 229745558 bytes.
- APK SHA-256: `4c0b2146f523ff185c5c3da4dc7dba1a47c7ffc9770cee956b4cd960da9f9d44`
- `git diff --check`: passed.

## Remaining acceptance boundary

RC Plus remains unavailable. Device process-kill, real-flight, real-laser, and network-loss acceptance are therefore still pending; NCNN remains the agreed provisional runtime evidence.
