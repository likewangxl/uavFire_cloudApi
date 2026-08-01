# Task 13 report — Agent production cutover

## Outcome

- `/fire-detection/start` and `/stop` now enqueue `visible-detector-arm` / `visible-detector-disarm` through the existing Agent command channel.
- `/fire-detection/status` declares `executor=AGENT` and reports only observed Agent heartbeat state. A missing or older-than-15-second detector heartbeat is fail-closed as `running=false` with `agent-heartbeat-stale`.
- Agent heartbeat now carries stable detector intent, state, health, and reason fields. Arm intent is distinct from actual running state; failed safety/health gates report `ARMED/BLOCKED/UNHEALTHY`, never healthy-running.
- The production backend no longer contains or consumes `AiServiceClient`, `ai-service` configuration, livestream AI auto-triggering, latest-visible-ROI API/auth exclusions, automatic visible hold/ROI/laser ACK coordination, or automatic confirmation-flight dispatch.
- Agent legacy backend-driven hold/laser/confirmation command execution was retired. Local closed-loop localization remains connected directly to the Agent coordinator; manual stream/focus and safety controls remain available.

## Verification

- Backend full suite: `494/494`, failures `0`, errors `0`, skipped `0`.
- Agent JVM suite: `479/479`, failures `0`, errors `0`, skipped `0`.
- Android debug APK: `:app:assembleDebug` successful.
- Production-source audit for `AiServiceClient`, `ai-service`, livestream auto-trigger, latest ROI, and the three retired legacy actions: no matches.
- `git diff --check`: clean.

## Device boundary

RC Plus was unavailable. These results are automated backend/JVM/NCNN-backed development evidence only and do not claim RC Plus or real-flight production acceptance.
