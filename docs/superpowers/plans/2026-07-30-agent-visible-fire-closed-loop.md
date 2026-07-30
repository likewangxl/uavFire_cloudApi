# Agent Visible-Light Fire Closed Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the RC Plus Agent the only production real-time fire/smoke detector and flight-localization coordinator while preserving backend event storage, alerts, task management, and Chinese cockpit presentation.

**Architecture:** MSDK visible RGBA frames feed a capacity-one buffer and a single NCNN YOLO worker. A pure Kotlin state machine confirms fire/smoke locally, durably records the event, immediately queues the first backend alert, pauses the current wayline, reacquires the ROI, obtains three laser samples, persists a precise or OSD-degraded terminal result, and safely resumes the original mission. The backend accepts ordered idempotent stage reports, stores and alerts from a transactional outbox, but never orchestrates hover/ROI/laser steps. `ai-service` remains only as offline export/validation tooling and is removed from the production runtime path.

**Tech Stack:** Kotlin 1.9/Android 34, DJI MSDK 5.18, coroutines, JNI/NCNN, SQLiteOpenHelper, Retrofit/OkHttp, Java 17/Spring Boot/MyBatis/MySQL, Vue 3/TypeScript/Node tests, Python/Ultralytics/pytest.

---

## Global Constraints

- Work only in `/Users/likewang/uavfire/.worktrees/agent-fire-detection` on `feature/agent-visible-fire-closed-loop`; never implement these tasks in the production checkout.
- Follow test-driven development: add the focused failing test, run it and observe the intended failure, add the minimum implementation, rerun focused tests, then run the affected module suite.
- Task 2 is a hard gate. The existing 640 thermal benchmark does not authorize the 960 visible model. Do not wire a production detector until the current visible model passes the visible benchmark on RC Plus 2.
- Production APK contains one detector runtime and one model only. The benchmark APK may contain ONNX, TFLite, and NCNN candidates.
- MSDK frame callbacks may only validate metadata and replace a capacity-one buffer. Inference, JPEG encoding, filesystem writes, network calls, and flight control never run on the callback thread.
- `eventId` is generated once by Agent and remains stable for visual confirmation, progress, localization, evidence, retries, and backend records.
- `fireLat/fireLng/fireAlt` are null unless a valid laser terminal result exists. OSD position is always stored in `aircraftLat/aircraftLng/aircraftAlt`.
- A terminal local result is durable when its session, evidence references, and final Outbox row commit in one SQLite transaction. A backend ACK is helpful but is not required to resume after the bounded one-second wait.
- Any unknown mission state, missing breakpoint, manual intervention, storage failure, detector failure during an active hold, or resume failure enters `MANUAL_HOLD`.
- All external status codes remain stable English enum values; all visible UI and notification strings use the centralized Chinese mapping.
- Do not remove `ai-service` source in this change. Remove its production startup, backend task lifecycle dependency, and formal production reporting authority only.
- Keep generated exports, benchmark images/results, credentials, `local.properties`, and the root generated `AGENTS.md` out of commits.

## Canonical Contracts

Agent state:

```kotlin
enum class FireSessionState {
    DISARMED, ARMING, SCANNING, VISUAL_CONFIRMING, VISUAL_CONFIRMED,
    HOLD_REQUESTED, HOVER_VERIFYING, TARGET_ALIGNING, LASER_MEASURING,
    RESULT_DURABLE, RESUME_REQUESTED, MISSION_RESUMED, MANUAL_HOLD
}

enum class DetectionKind { FIRE, SMOKE }
enum class LocationStatus { LASER_LOCATING, PRECISE, DEGRADED_OSD }
enum class GeoMethod { LASER_RANGEFINDER, AIRCRAFT_OBSERVATION }
```

Ordered report envelope:

```json
{
  "eventId": "agent-1581F7K3D249C00AEK3P-1785376800123-a1b2c3",
  "sessionId": "9fa1c2b0-7d91-4d30-9149-8e3117ac3332",
  "sequence": 1,
  "eventTimestamp": 1785376800123,
  "state": "VISUAL_CONFIRMED",
  "detectionKind": "FIRE",
  "confidence": 0.94,
  "visibleRoi": {"x": 0.31, "y": 0.22, "width": 0.10, "height": 0.14},
  "locationStatus": "LASER_LOCATING",
  "flightStatus": "HOLD_REQUESTED",
  "modelVersion": "visible-fire-wechat-best2-20260728",
  "modelHash": "957bec...",
  "policyVersion": "agent-visible-v1",
  "inputSize": 960,
  "runtime": "NCNN",
  "aircraft": {"lat": 34.1, "lng": 108.9, "alt": 72.0}
}
```

Backend response:

```json
{
  "eventId": "agent-1581F7K3D249C00AEK3P-1785376800123-a1b2c3",
  "acceptedSequence": 1,
  "duplicate": false,
  "eventPersisted": true,
  "notificationQueued": true
}
```

The backend returns HTTP 200 for an exact duplicate, HTTP 409 for an illegal transition or payload conflict, and never acknowledges a sequence before the event/history/notification-outbox transaction commits.

### Task 1: Re-export the current visible model reproducibly

**Files:**
- Rename: `ai-service/scripts/export_mobile_thermal_model.py` → `ai-service/scripts/export_mobile_visible_model.py`
- Modify: `ai-service/app/mobile_model.py`
- Modify: `ai-service/scripts/build_mobile_benchmark_set.py`
- Modify: `ai-service/tests/test_mobile_model.py`
- Modify: `ai-service/mobile-model/README.md`
- Modify: `.gitignore`

- [ ] Add failing tests proving the export manifest requires source model SHA-256, classes `["fire", "smoke"]`, input size `960`, RGB normalization, confidence/IoU thresholds, output layout, exporter versions, and ONNX/TFLite/NCNN artifact hashes.

```python
def test_visible_manifest_matches_production_contract(tmp_path):
    manifest = build_candidate_manifest(source_model, exported_files, input_size=960)
    assert manifest["classes"] == ["fire", "smoke"]
    assert manifest["input"]["width"] == 960
    assert manifest["source"]["sha256"] == sha256_file(source_model)
    assert {c["engine"] for c in manifest["candidates"]} == {"onnx", "tflite", "ncnn"}
```

- [ ] Run the focused test and confirm it fails because the current helper is thermal/640-specific.

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_mobile_model.py -q
```

- [ ] Generalize the manifest helper and visible exporter. The only accepted source for this release is `weights/visible-fire-wechat-best2-20260728.pt` with SHA-256 `957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650`; reject a model whose classes or source hash do not match the manifest.

- [ ] Build a deterministic visible benchmark set containing positive fire, positive smoke, hard-negative orange/red scenes, night/dark scenes, small targets, and zoomed ROI samples. Store stable sample IDs and hashes, not private absolute paths.

- [ ] Export all three candidates at 960 and generate the PyTorch per-sample baseline.

```bash
cd ai-service
./.venv/bin/python scripts/export_mobile_visible_model.py \
  --model weights/visible-fire-wechat-best2-20260728.pt \
  --input-size 960 \
  --output mobile-model/visible-960
./.venv/bin/python scripts/build_mobile_benchmark_set.py \
  --dataset mobile-model/source-visible-validation \
  --output mobile-model/visible-960/benchmark-set \
  --seed 20260730
```

- [ ] Verify hashes twice and rerun tests. Commit tooling and documentation, never generated binaries/data.

```bash
git add .gitignore ai-service/app/mobile_model.py ai-service/scripts \
  ai-service/tests/test_mobile_model.py ai-service/mobile-model/README.md
git commit -m "feat(ai): export production visible model for Agent"
```

### Task 2: Run the 960 visible three-engine RC Plus gate

**Files:**
- Modify: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/BenchmarkAssets.kt`
- Modify: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/BenchmarkRunContract.kt`
- Modify: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/EngineSelectionPolicy.kt`
- Modify: `rcplus-msdk-agent/fire-detector-benchmark/src/androidTest/java/com/yinxin/uavfir/benchmark/FireDetectorBenchmarkTest.kt`
- Modify: `rcplus-msdk-agent/fire-detector-benchmark/README.md`
- Modify corresponding JVM tests under `fire-detector-benchmark/src/test/`

- [ ] Add failing tests that reject thermal/640 manifests, require both classes in evaluated samples, reject recall loss over 2 percentage points from PyTorch, reject P95 over 200 ms, reject final-five-minute P95 degradation over 20%, and reject missing 30-minute stability evidence.

- [ ] Run the JVM suite and observe the new visible-contract failures.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :fire-detector-benchmark:testDebugUnitTest
```

- [ ] Stage the generated visible artifacts and benchmark data into the benchmark APK without committing them. Ensure identical RGBA→RGB letterbox preprocessing and NMS for every engine.

- [ ] Run all three engines on the connected RC Plus 2 with the formal Agent/UXSDK build installed or co-running; pull the result.

```bash
export UAVFIRE_ADB_SERIAL=192.168.50.141:5555
adb -s "$UAVFIRE_ADB_SERIAL" get-state
./gradlew --console=plain :fire-detector-benchmark:connectedDebugAndroidTest
adb -s "$UAVFIRE_ADB_SERIAL" pull \
  /sdcard/Android/data/com.yinxin.uavfir.benchmark/files/fire-detector-benchmark.json \
  fire-detector-benchmark/build/visible-960-fire-detector-benchmark.json
```

- [ ] Inspect the JSON: every candidate must have complete accuracy/performance/stability data. NCNN is the approved production target and must independently pass every gate; ONNX/TFLite remain comparison evidence. If NCNN fails any visible gate, stop here and do not substitute another runtime or weaken thresholds without a new architecture decision.

- [ ] Commit only harness changes after the result proves the gate.

```bash
git add rcplus-msdk-agent/fire-detector-benchmark
git commit -m "test(agent): gate visible detector on RC Plus"
```

### Task 3: Add production NCNN packaging and model integrity checks

**Files:**
- Modify: `rcplus-msdk-agent/app/build.gradle.kts`
- Create: `rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json`
- Add selected NCNN artifacts under: `rcplus-msdk-agent/app/src/main/assets/fire-detection/`
- Create: `rcplus-msdk-agent/app/src/main/cpp/CMakeLists.txt`
- Create: `rcplus-msdk-agent/app/src/main/cpp/visible_fire_ncnn_bridge.cpp`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/VisibleFireDetector.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/NcnnVisibleFireDetector.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/VisibleFireDetectorFactory.kt`
- Create tests under: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/firedetection/`

- [ ] Add failing tests for manifest parsing, exact source/artifact hashes, class order, input size, threshold values, unknown runtime rejection, native-load failure, normalized box mapping, and production APK dependency policy.

- [ ] Move the already benchmarked preprocessing/postprocessing code into the app package and expose:

```kotlin
interface VisibleFireDetector : AutoCloseable {
    suspend fun detect(frame: VisibleRgbaFrame): VisibleDetectionResult
}
```

- [ ] Make `VisibleFireDetectorFactory.create()` stream assets through SHA-256 before loading JNI. On mismatch it returns a typed arming failure and never opens a session.

- [ ] Ensure Gradle packages NCNN only; remove `AGENT_AI_SERVICE_BASE_URL` and any ONNX/TFLite production dependencies. Add an APK-content test that fails if ONNX/TFLite runtime libraries or a second fire model appear.

- [ ] Run focused/full Agent tests and assemble.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests 'com.yinxin.uavfir.firedetection.*'
./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug
```

- [ ] Commit.

```bash
git add rcplus-msdk-agent/app
git commit -m "feat(agent): package verified NCNN visible detector"
```

### Task 4: Feed only fresh visible RGBA frames to a latest-only detector loop

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/VisibleRgbaFrame.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/LatestVisibleFrameBuffer.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/VisibleInferenceLoop.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt`
- Create corresponding tests under `app/src/test/java/com/yinxin/uavfir/firedetection/`
- Modify: `app/src/test/java/com/yinxin/uavfir/stream/ThermalFrameProbeSourceTest.kt`

- [ ] Add failing tests for capacity one, deterministic old-buffer release, visible-source-only acceptance, RGBA validation, no callback-thread work, one in-flight inference, target 5 FPS, and stale-frame rejection above 300 ms.

- [ ] Add `offerVisibleFrame(...)` before snapshot throttling in `ThermalFrameProbe.onFrame()`. Copy only when the buffer accepts ownership; thermal frames continue down the existing thermal diagnostic path but never reach the visible detector.

- [ ] Implement a coroutine loop that consumes the latest frame, records captured/start/completed timestamps, never overlaps inference, and publishes detector health plus frame-age metrics.

- [ ] Add golden tests comparing Android RGBA preprocessing and fire-color pixel decisions to the Python OpenCV BGR baseline.

- [ ] Run focused/full tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests 'com.yinxin.uavfir.firedetection.*' \
  --tests com.yinxin.uavfir.stream.ThermalFrameProbeSourceTest
./gradlew --console=plain :app:testDebugUnitTest
git add app/src/main/java/com/yinxin/uavfir/firedetection \
  app/src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt \
  app/src/main/java/com/yinxin/uavfir/AppServices.kt app/src/test
git commit -m "feat(agent): infer from fresh visible MSDK frames"
```

### Task 5: Implement visual confirmation and typed session transitions

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireSessionModels.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/VisibleConfirmationPolicy.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/VisibleConfirmationTracker.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireSessionReducer.kt`
- Create tests with matching names under `app/src/test/java/com/yinxin/uavfir/firedetection/`

- [ ] Add table-driven failing tests for every legal and illegal transition in the canonical state enum.

- [ ] Add confirmation tests requiring two fresh consecutive detections with nearby normalized centers. `FIRE` must pass the ported fire-color check; `SMOKE` must not be rejected by fire-color logic.

- [ ] Centralize thresholds and versions in `VisibleConfirmationPolicy`; no constants may remain in the coordinator or UI.

```kotlin
data class VisibleConfirmationPolicy(
    val policyVersion: String = "agent-visible-v1",
    val requiredFreshFrames: Int = 2,
    val maxFrameAgeMs: Long = 300,
    val maxCenterDistance: Double,
    val fireConfidence: Float,
    val smokeConfidence: Float,
    val nmsIou: Float
)
```

- [ ] Make the reducer return typed effects (`PersistInitialAlert`, `PauseMission`, `AlignTarget`, `MeasureLaser`, `PersistTerminalResult`, `ResumeMission`) and keep hardware/network calls outside it.

- [ ] Run tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests \
  'com.yinxin.uavfir.firedetection.VisibleConfirmation*' \
  --tests com.yinxin.uavfir.firedetection.FireSessionReducerTest
git add app/src/main/java/com/yinxin/uavfir/firedetection app/src/test/java/com/yinxin/uavfir/firedetection
git commit -m "feat(agent): confirm visible fire and smoke locally"
```

### Task 6: Add transactional local session, evidence, and ordered Outbox storage

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/store/FireStoreContract.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/store/FireStoreOpenHelper.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/store/SqliteFireSessionStore.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/store/FireOutboxDispatcher.kt`
- Create matching tests under `app/src/test/java/com/yinxin/uavfir/firedetection/store/`

- [ ] Add failing tests for tables `fire_session`, `fire_evidence`, and `report_outbox`; unique `(event_id, sequence)`; atomic initial/final writes; strict per-event send ordering; retries `250ms, 500ms, 1s, 2s, 5s`; crash recovery; and permanent 409 quarantine.

- [ ] Use `SQLiteOpenHelper`, WAL, foreign keys, explicit schema versioning, and one transaction per state-plus-Outbox mutation. Store evidence paths and hashes, not JPEG blobs.

- [ ] Define `persistInitialConfirmation()` to allocate `eventId`, write `VISUAL_CONFIRMED`, evidence references, and sequence 1 in one transaction before any flight action.

- [ ] Define `persistTerminalResult()` to atomically write `PRECISE` or `DEGRADED_OSD` and its next sequence before resume eligibility becomes true.

- [ ] On startup, reload pending Outbox rows and active sessions. Any session left between `HOLD_REQUESTED` and `RESUME_REQUESTED` recovers to `MANUAL_HOLD` until MSDK state is reconciled.

- [ ] Run tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests 'com.yinxin.uavfir.firedetection.store.*'
git add app/src/main/java/com/yinxin/uavfir/firedetection/store \
  app/src/test/java/com/yinxin/uavfir/firedetection/store
git commit -m "feat(agent): persist fire sessions and ordered reports"
```

### Task 7: Make pause, breakpoint, and resume awaitable and safety-gated

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FlightSafetyGate.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/AwaitableMissionControl.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt`
- Modify: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/wayline/WaypointMissionExecutorSourceTest.kt`
- Create matching firedetection tests.

- [ ] Add failing tests proving pause/resume complete only after both callback success and observed wayline state, repeated calls are idempotent, breakpoint identity is preserved, and resume failure is never retried in a loop.

- [ ] Add safety-gate tests for stable hover (`horizontal <= 0.3m/s`, `abs(vertical) <= 0.2m/s` for one second), eight-second timeout, manual stick/control takeover, low battery/RTH/avoidance/flight errors, missing breakpoint, laser enabled, and another active fire control session.

- [ ] Keep existing callback entry points for legacy command routing; add suspend wrappers with `suspendCancellableCoroutine` and a timeout owned by the coordinator.

- [ ] A pause failure may fall back to an explicit hover command only when no wayline is active. An active wayline whose pause state is uncertain enters `MANUAL_HOLD`.

- [ ] Run focused/full tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests \
  com.yinxin.uavfir.wayline.WaypointMissionExecutorSourceTest \
  --tests 'com.yinxin.uavfir.firedetection.*Mission*' \
  --tests com.yinxin.uavfir.firedetection.FlightSafetyGateTest
./gradlew --console=plain :app:testDebugUnitTest
git add app/src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt \
  app/src/main/java/com/yinxin/uavfir/firedetection app/src/main/java/com/yinxin/uavfir/AppServices.kt \
  app/src/test
git commit -m "feat(agent): gate fire hold and mission resume locally"
```

### Task 8: Replace backend ROI polling with local ROI reacquisition and laser sampling

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/VisibleFireLaserLocator.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/LocalVisibleTargetAimer.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/LaserSampleValidator.kt`
- Modify: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/VisibleFireLaserLocatorTest.kt`
- Create matching firedetection tests.

- [ ] Add failing tests proving the aimer never calls `latestVisibleRoi`, accepts only frames captured after each tap-zoom completion, retries at most three align/reacquire cycles, and requires the laser reticle inside the newest ROI.

- [ ] Implement `LocalVisibleTargetAimer` using the same production detector and latest-frame buffer. Each zoom/action returns a completion timestamp that becomes the minimum accepted frame timestamp.

- [ ] Add laser tests requiring three `NORMAL` samples at 300 ms intervals, valid lat/lng, scatter no greater than 15 m, median coordinates, 5 m default error radius, raw sample retention, and guaranteed laser disable in `finally`.

- [ ] Return a typed terminal result. Failure contains the reason and OSD snapshot only; it never populates fire coordinates.

- [ ] Run tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests \
  com.yinxin.uavfir.api.VisibleFireLaserLocatorTest \
  --tests com.yinxin.uavfir.firedetection.LocalVisibleTargetAimerTest \
  --tests com.yinxin.uavfir.firedetection.LaserSampleValidatorTest
git add app/src/main/java/com/yinxin/uavfir/api/VisibleFireLaserLocator.kt \
  app/src/main/java/com/yinxin/uavfir/firedetection app/src/test
git commit -m "feat(agent): localize visible targets without backend ROI"
```

### Task 9: Implement the complete Agent closed-loop coordinator

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/AgentFireClosedLoopCoordinator.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/AgentFireRecoveryCoordinator.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt`
- Create matching tests.

- [ ] Add coroutine tests with virtual time for: fire success; smoke success; laser failure to OSD; backend offline; ACK under/over one second; pause timeout; ROI loss; laser scatter; manual takeover; resume success; resume failure; app restart during hold; and a second detection while one session controls flight.

- [ ] Implement the effect loop:
  1. persist initial session and Outbox;
  2. start Outbox delivery and pause concurrently;
  3. verify hover;
  4. reacquire local ROI;
  5. sample laser;
  6. persist `PRECISE` or `DEGRADED_OSD`;
  7. attempt immediate terminal send and wait at most one second;
  8. disable laser and close alignment;
  9. pass all safety gates;
  10. resume and wait for MSDK confirmation.

- [ ] Progress reports use increasing sequences but never increment backend notification version. Terminal reports increment notification version once.

- [ ] Arm the coordinator only while backend task monitoring is enabled, the visible source is active, model/store/flight adapters are healthy, and no manual hold exists.

- [ ] Run all Agent tests and assemble.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest
./gradlew --console=plain :app:assembleDebug
git add app/src/main/java/com/yinxin/uavfir/firedetection \
  app/src/main/java/com/yinxin/uavfir/AppServices.kt app/src/test
git commit -m "feat(agent): close visible fire localization loop"
```

### Task 10: Add the staged Agent/backend report API

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentFireReportModels.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/DualStreamApi.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
- Modify: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/AgentBackendClientTest.kt`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/param/AgentFireReportParam.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/dto/AgentFireReportResponse.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/controller/FireEventController.java`
- Create controller validation tests.

- [ ] Add MockWebServer tests for `POST /manage/api/v1/fire-events/agent-report`, exact JSON names, one-second terminal ACK timeout, 200 duplicate handling, 409 quarantine, and network retry classification.

- [ ] Define request validation: required Agent identity/task/event/session/sequence/timestamp/state/kind/model/policy fields; normalized ROI; allowed model hash; clock skew; precise-only fire coordinates; complete aircraft triple for OSD degradation; and raw laser samples for `PRECISE`.

- [ ] Add backend controller tests for invalid combinations and exact response semantics.

- [ ] Keep image upload separate from `agent-report`; image failures enqueue later evidence updates and cannot block sequence 1.

- [ ] Run Agent/backend focused tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew --console=plain :app:testDebugUnitTest --tests com.yinxin.uavfir.api.AgentBackendClientTest
cd ../backend/uavfire
mvn -q -Dtest='*AgentFireReport*' test
cd ../..
git add rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api \
  rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api \
  backend/uavfire/src/main/java/com/yx/uavfire/fc100/event \
  backend/uavfire/src/test
git commit -m "feat(fire): accept staged Agent fire reports"
```

### Task 11: Persist ordered backend state without corrupting coordinate semantics

**Files:**
- Create: `backend/sql/migrations/2026-07-30-agent-fire-report-state.sql`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/entity/FireEventEntity.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/entity/FireEventHistoryEntity.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/entity/AgentFireReportEntity.java`
- Create mapper: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/mapper/AgentFireReportMapper.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/FireEventService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`
- Modify/add service tests.

- [ ] Add migration columns for `detection_kind`, `detection_status`, `location_status`, `flight_status`, `agent_session_id`, `last_agent_sequence`, `model_version/hash`, `policy_version`, `input_size`, and `runtime`. Add immutable `agent_fire_report` rows with unique `(event_id, sequence)` and JSON payload hash.

- [ ] Add failing tests for exact duplicate idempotency, payload conflict, out-of-order rejection, terminal-state monotonicity, progress-only history, one event per eventId, and one terminal notification version increment.

- [ ] Add OSD-semantic tests: Agent `LASER_LOCATING` and `DEGRADED_OSD` skip `fillPositionFromOsdIfMissing`, keep `lat/lng/alt` null, store aircraft fields, skip spatial merge, and never become route-ready. Existing non-Agent legacy behavior remains covered.

- [ ] Validate the three raw laser samples before accepting `PRECISE`; only then copy median fire coordinates and allow spatial merge.

- [ ] Run focused/full backend tests and commit.

```bash
cd backend/uavfire
mvn -q -Dtest='FireEventServiceImpl*Test,*AgentFireReport*Test' test
mvn -q test
cd ../..
git add backend/sql/migrations/2026-07-30-agent-fire-report-state.sql \
  backend/uavfire/src/main/java/com/yx/uavfire/fc100/event backend/uavfire/src/test
git commit -m "feat(fire): persist ordered Agent localization state"
```

### Task 12: Add transactional alert Outbox and WebSocket delivery

**Files:**
- Create: `backend/sql/migrations/2026-07-30-fire-notification-outbox.sql`
- Create entity/mapper/service files under: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/notification/`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/component/websocket/model/BizCodeEnum.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`
- Create tests under: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/notification/`

- [ ] Add failing transactional tests: event/history/outbox commit together; rollback leaves none; duplicate sequence creates no duplicate notification; initial and terminal versions each notify once; progress and evidence updates do not notify.

- [ ] Create `fire_notification_outbox` with unique `(event_id, notification_version)`, status, attempts, next-attempt timestamp, payload, and sent timestamp.

- [ ] Enqueue Chinese notification payloads in the event transaction:
  - initial: `发现疑似火情，正在精确定位`;
  - precise: `目标已完成激光定位`;
  - degraded: `视觉火情已保存，激光定位失败，已记录飞机观测位置`.

- [ ] Add `FIRE_EVENT_UPDATE` to `BizCodeEnum`. A scheduled dispatcher locks pending rows, sends through `IWebSocketMessageService`, marks success, and retries failures without rolling back the event.

- [ ] Run tests and commit.

```bash
cd backend/uavfire
mvn -q -Dtest='*FireNotificationOutbox*Test,*AgentFireReport*Test' test
mvn -q test
cd ../..
git add backend/sql/migrations/2026-07-30-fire-notification-outbox.sql \
  backend/uavfire/src/main/java/com/yx/uavfire/fc100/event \
  backend/uavfire/src/main/java/com/yx/uavfire/component/websocket/model/BizCodeEnum.java \
  backend/uavfire/src/test
git commit -m "feat(fire): deliver fire alerts from transactional outbox"
```

### Task 13: Remove backend flight localization orchestration and ai-service runtime authority

**Files:**
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/firedetection/FireDetectionController.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/firedetection/FireDetectionService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`
- Delete: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/VisibleFireLocalizationDispatcher.java`
- Modify: `backend/uavfire/src/main/resources/application.yml`
- Modify related backend tests.

- [ ] Add failing tests proving start/stop arms/disarms the Agent through the existing Agent command channel, never calls `AiServiceClient`, and event creation never sends `visible-fire-hold` or laser commands.

- [ ] Change `/fire-detection/start|stop|status` to represent Agent detector state. Keep response compatibility fields but add `executor=AGENT` and truthful health/status from Agent heartbeat.

- [ ] Remove `dispatchVisibleLaserLocalization()` and `VisibleFireLocalizationDispatcher`. Keep manual operator hold/resume commands in the backend with their higher safety priority.

- [ ] Remove production `ai-service.*` configuration consumption from this service path. Retain `AiServiceClient` only if another explicitly tested non-production tool still imports it.

- [ ] Run tests and commit.

```bash
cd backend/uavfire
mvn -q -Dtest='FireDetectionServiceTest,FireEventServiceImpl*Test,*DualStream*Test' test
mvn -q test
cd ../..
git add -A backend/uavfire/src/main/java/com/yx/uavfire/firedetection \
  backend/uavfire/src/main/java/com/yx/uavfire/fc100/event \
  backend/uavfire/src/main/resources/application.yml backend/uavfire/src/test
git commit -m "refactor(fire): make Agent the realtime executor"
```

### Task 14: Centralize Chinese UI/status semantics

**Files:**
- Create: `frontend/src/pages/page-web/projects/fire/fire-event-status.mjs`
- Create: `frontend/src/pages/page-web/projects/fire/fire-event-status.d.ts`
- Modify: `frontend/src/pages/page-web/projects/fire/fire-event-location.mjs`
- Modify: `frontend/src/pages/page-web/projects/fire/FireEventList.vue`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit-situation.mjs`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit-summary.mjs`
- Modify: `frontend/src/api/manage.ts`
- Create: `frontend/src/pages/page-web/projects/__tests__/fire-event-status.test.mjs`
- Modify existing fire/cockpit tests.

- [ ] Add an exhaustive test for every canonical status/method/kind. An unknown code must render `未知状态`, never the raw English value.

- [ ] Export shared `formatDetectionStatus`, `formatLocationStatus`, `formatFlightStatus`, `formatGeoMethod`, `formatDetectionKind`, `formatLocationExplanation`, and route-readiness helpers.

- [ ] Replace every raw `geoQuality`, mission status, source, `VISIBLE_SUSPECTED`, and fallback interpolation in the list, detail, map, summary, and cockpit with the shared mapping.

- [ ] Render `DEGRADED_OSD` as `飞机观测位置，非火点精确位置`. Render `SMOKE + PRECISE` as `烟雾观测定位点，可能不是实际起火源`. Do not draw a precise fire marker or auto-route action for degraded events.

- [ ] Subscribe to `FIRE_EVENT_UPDATE`; update the same row/toast by `eventId + notificationVersion` rather than inserting a second event.

- [ ] Run policy tests and production build, then commit.

```bash
cd frontend
node --test src/pages/page-web/projects/__tests__/*.test.mjs scripts/*.test.mjs
npm run build
cd ..
git add frontend/src frontend/scripts
git commit -m "feat(web): present Agent fire states in Chinese"
```

### Task 15: Remove ai-service from production operations and complete acceptance

**Files:**
- Modify: `README.md`
- Modify: `RUNBOOK.md`
- Modify: `HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md`
- Modify: `deployment/zlmediakit/README.md`
- Create: `docs/runbooks/agent-visible-fire-closed-loop-acceptance.md`
- Create: `scripts/agent-visible-fire-static-check.sh`
- Create: `scripts/agent-visible-fire-static-check.test.sh`

- [ ] Add a failing static policy test that searches production docs/config/scripts/backend/Agent for ai-service startup URLs, ai-service task creation, backend ROI polling, and raw UI status rendering. Explicitly allow offline paths under `ai-service/`.

- [ ] Update production startup documentation to start backend, frontend, ZLMediaKit, and Agent only. State that ZLMediaKit/RTMP is display-only and is not a detection dependency.

- [ ] Document exact preflight, rollback, and evidence locations. Rollback disables Agent detector arming and leaves manual flight control available; it does not silently re-enable ai-service as a formal detector.

- [ ] Run the complete automated gate from a clean feature worktree.

```bash
./scripts/agent-visible-fire-static-check.test.sh
cd ai-service && ./.venv/bin/python -m pytest -q
cd ../backend/uavfire && mvn -q test
cd ../../rcplus-msdk-agent && ./gradlew --console=plain \
  :fire-detector-benchmark:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
cd ../frontend && node --test src/pages/page-web/projects/__tests__/*.test.mjs scripts/*.test.mjs
npm run build
```

- [ ] Perform device acceptance in order and record timestamps/log paths/result hashes:
  1. verify installed APK/model/runtime/version/hash;
  2. run visible fire and smoke static targets;
  3. run 30-minute UXSDK + RTMP + inference soak;
  4. verify first report under 1 s and cockpit notification under 1.5 s;
  5. verify precise laser update and route resume;
  6. verify laser failure stores OSD only and resumes;
  7. verify network loss, duplicate/late reports, backend restart, Agent restart, manual takeover, and resume failure;
  8. perform no-prop bench safety validation;
  9. perform controlled flight only after every prior gate passes.

- [ ] Do not enable Agent detection by default until controlled flight passes for both fire and smoke. Record any failed gate as release-blocking.

- [ ] Run final repository checks, review the diff, and commit the runbook/policy.

```bash
git diff --check
git status --short
git add README.md RUNBOOK.md HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md \
  deployment/zlmediakit/README.md docs/runbooks scripts/agent-visible-fire-static-check.sh \
  scripts/agent-visible-fire-static-check.test.sh
git commit -m "docs: cut production fire detection over to Agent"
```

## Final Review Checklist

- [ ] The feature branch still contains merge commit `a27f414` and production branch remains untouched after that merge.
- [ ] Visible 960 three-engine device benchmark evidence exists and NCNN passes every hard gate.
- [ ] APK inspection proves one runtime and one model.
- [ ] Initial report is durable before pause and does not wait for images, hover, or laser.
- [ ] Fire and smoke both pause, reacquire ROI, measure laser, persist, report, and safely resume.
- [ ] Laser failure stores only aircraft observation coordinates.
- [ ] Offline/restart/duplicate/out-of-order tests prove no event loss, duplicate event, or duplicate notification.
- [ ] Backend never drives normal hold/ROI/laser steps and never calls ai-service for production detection.
- [ ] All visible statuses and notification text are Chinese; unknown values never leak raw codes.
- [ ] Automated, device, bench, and controlled-flight evidence is linked from the acceptance runbook.
- [ ] `git diff --check`, module suites, build outputs, and worktree status are clean before requesting review.
