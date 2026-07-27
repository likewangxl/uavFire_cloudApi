# Agent On-Device Fire Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run thermal YOLO detection directly on the RC Plus Agent, pause the active wayline immediately on a stable candidate, verify while hovering, and either create a confirmed fire event or resume from the exact mission breakpoint.

**Architecture:** `ThermalFrameProbe` feeds a capacity-one latest-frame buffer. A selected mobile inference engine produces candidates for a pure Kotlin state machine. The Agent owns pause, hover verification, offline event queuing, and resume; the backend remains authoritative for task state, confirmed fire persistence, geolocation, and approach dispatch. The existing Python AI service remains shadow-only or an explicitly selected fallback and is never allowed to control flight.

**Tech Stack:** Kotlin/Android, DJI MSDK 5.18, coroutines, selected mobile inference runtime (ONNX Runtime Mobile, TensorFlow Lite, or NCNN), Java/Spring Boot, Vue 3/TypeScript, Python/Ultralytics/pytest.

---

## Delivery Rules

- Implement the tasks in order. Task 2 is a hard gate: do not add a production inference runtime until the benchmark has selected one.
- Preserve the existing visible/thermal streaming path. Detection consumes decoded frames but must not wait for RTSP, screenshots, disk I/O, or backend responses.
- Use one in-flight inference and a latest-only frame buffer. Never queue stale video frames.
- Only `CONFIRMED` verification events may create formal `FireEvent` records.
- The Python AI service may report shadow telemetry, but it may not pause, resume, confirm, or create a formal fire event.
- Keep every threshold in one typed policy object and include the policy/model version in emitted events.
- Leave unrelated working-tree changes, especially generated `AGENTS.md` changes, out of all commits.

## File Map

**AI model export and benchmark input**

- Modify: `.gitignore`
- Create: `ai-service/app/mobile_model.py`
- Create: `ai-service/scripts/export_mobile_thermal_model.py`
- Create: `ai-service/scripts/build_mobile_benchmark_set.py`
- Create: `ai-service/tests/test_mobile_model.py`
- Create: `ai-service/mobile-model/README.md`
- Create at export time: `ai-service/mobile-model/model-candidates.json`
- Create at export time: `ai-service/mobile-model/benchmark-set/manifest.json`

**Android benchmark gate**

- Modify: `rcplus-msdk-agent/settings.gradle.kts`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/build.gradle.kts`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/main/AndroidManifest.xml`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/androidTest/java/com/yinxin/uavfir/benchmark/FireDetectorBenchmarkTest.kt`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/BenchmarkContracts.kt`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/OnnxBenchmarkDetector.kt`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/TfliteBenchmarkDetector.kt`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/NcnnBenchmarkDetector.kt`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/test/java/com/yinxin/uavfir/benchmark/BenchmarkSelectorTest.kt`
- Create: `rcplus-msdk-agent/fire-detector-benchmark/src/main/java/com/yinxin/uavfir/benchmark/BenchmarkSelector.kt`

**Android production detector and flight state machine**

- Modify: `rcplus-msdk-agent/app/build.gradle.kts`
- Create: `rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json`
- Add selected model artifact under: `rcplus-msdk-agent/app/src/main/assets/fire-detection/`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireDetectionModels.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/LatestThermalFrameBuffer.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/OnDeviceFireDetector.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/SelectedFireDetectorFactory.kt`
- Create selected adapter: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/SelectedRuntimeFireDetector.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireScanStateMachine.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireDetectionCoordinator.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireVerificationCoordinator.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection/FireDetectionEventStore.kt`
- Create corresponding tests under: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/firedetection/`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/RealMsdkStreamProvider.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt`

**Agent/backend contract**

- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/DualStreamApi.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/FireDetectionEventRequest.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/CommandPollingCoordinator.kt`
- Modify tests: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/AgentBackendClientTest.kt`
- Modify tests: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/CommandPollingCoordinatorTest.kt`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/firedetection/model/AgentFireDetectionEventDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/firedetection/model/AgentFireDetectionStatusDTO.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/firedetection/FireDetectionController.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/firedetection/FireDetectionService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamCommandDTO.java`
- Modify corresponding backend tests.

**Mission arming and UI**

- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/agent/mqtt/WaylineAgentEventListener.java`
- Modify: `backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineServiceTest.java`
- Modify: `backend/uavfire/src/test/java/com/yx/uavfire/wayline/agent/mqtt/WaylineAgentEventListenerTest.java`
- Modify: `frontend/src/api/manage.ts`
- Create: `frontend/src/api/fire-detection-state.mjs`
- Create: `frontend/src/api/fire-detection-state.d.ts`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Create: `frontend/scripts/agent-fire-detection-status.test.mjs`

**Shadow mode and acceptance**

- Modify: `ai-service/app/config/settings.py`
- Modify: `ai-service/app/services/fire_event_reporter.py`
- Modify: `ai-service/.env.example`
- Modify: `ai-service/tests/test_fire_event_reporter.py`
- Create: `docs/runbooks/agent-fire-detection-flight-acceptance.md`

### Task 1: Export reproducible mobile model candidates

**Files:**
- Modify `.gitignore`
- Create `ai-service/app/mobile_model.py`
- Create `ai-service/scripts/export_mobile_thermal_model.py`
- Create `ai-service/scripts/build_mobile_benchmark_set.py`
- Create `ai-service/tests/test_mobile_model.py`
- Create `ai-service/mobile-model/README.md`

- [ ] Write failing tests for a manifest containing `modelVersion`, source SHA-256, input size, class names, normalization, confidence threshold, IoU threshold, output layout, and one entry each for ONNX, TFLite, and NCNN.

```python
def test_candidate_manifest_is_reproducible(tmp_path):
    manifest = build_candidate_manifest(SOURCE_MODEL, exported_files)
    assert manifest["source"]["sha256"] == sha256_file(SOURCE_MODEL)
    assert {item["engine"] for item in manifest["candidates"]} == {
        "onnx", "tflite", "ncnn"
    }
    assert all(item["sha256"] for item in manifest["candidates"])
```

- [ ] Run the test and confirm it fails because `app.mobile_model` does not exist.

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_mobile_model.py -q
```

Expected: import failure for `app.mobile_model`.

- [ ] Add ignore rules for `ai-service/mobile-model/*`, then re-include only `ai-service/mobile-model/README.md`. Generated exports, source validation data, benchmark inputs, baselines, and result JSON must stay out of Git.

- [ ] Implement structured manifest helpers and an exporter that invokes Ultralytics with:

```bash
yolo export model=weights/thermal-fire-yolov8n-640-gt-20260709.pt format=onnx imgsz=640 simplify=True
yolo export model=weights/thermal-fire-yolov8n-640-gt-20260709.pt format=tflite imgsz=640
yolo export model=weights/thermal-fire-yolov8n-640-gt-20260709.pt format=ncnn imgsz=640
```

The script must reject missing outputs, hash every artifact, and write `mobile-model/model-candidates.json` atomically.

- [ ] Stage a read-only export of the labeled validation split used to train the deployed checkpoint at `ai-service/mobile-model/source-validation/`. The directory must contain its dataset YAML plus referenced images/labels and remain gitignored. Implement `build_mobile_benchmark_set.py` to validate that structure and create a deterministic benchmark set: 200 positive images and 200 negative images selected by stable sorted path plus seed `20260727`. Record source path, label path, image hash, and expected boxes in `benchmark-set/manifest.json`. Do not copy private source paths into Android results; use stable sample IDs.

- [ ] Run tests, export candidates, then run PyTorch baseline inference over the benchmark set and store per-sample detections in `benchmark-set/pytorch-baseline.json`.

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_mobile_model.py -q
./.venv/bin/python scripts/export_mobile_thermal_model.py \
  --model weights/thermal-fire-yolov8n-640-gt-20260709.pt \
  --output mobile-model
./.venv/bin/python scripts/build_mobile_benchmark_set.py \
  --dataset mobile-model/source-validation \
  --output mobile-model/benchmark-set \
  --positive-count 200 \
  --negative-count 200 \
  --seed 20260727
```

Expected: tests pass; all three exports and both manifests exist with verified hashes.

- [ ] Commit the export tooling and README, but not generated model binaries or benchmark images.

```bash
git add .gitignore ai-service/app/mobile_model.py ai-service/scripts/export_mobile_thermal_model.py \
  ai-service/scripts/build_mobile_benchmark_set.py ai-service/tests/test_mobile_model.py \
  ai-service/mobile-model/README.md
git commit -m "feat(ai): add reproducible mobile model export"
```

### Task 2: Benchmark and select the production inference engine

**Files:**
- Modify `rcplus-msdk-agent/settings.gradle.kts`
- Create all files under `rcplus-msdk-agent/fire-detector-benchmark/` listed in the file map

- [ ] Write failing JVM tests for the deterministic selection policy:
  - reject recall more than 2 percentage points below PyTorch;
  - reject P95 above 200 ms;
  - reject a 30-minute run whose final 5-minute P95 is more than 20% slower than the first 5-minute P95;
  - among passing engines choose lowest P95;
  - within 10% P95 choose smallest production APK delta;
  - exact tie order is ONNX, TFLite, NCNN.

- [ ] Add a standalone Android benchmark module so three native runtimes never enter the production APK together. Its instrumentation test must:
  - verify every model and sample hash before use;
  - run a 30-frame warm-up;
  - run the 400-image correctness set;
  - continuously cycle inputs for 30 minutes;
  - record inference duration, source frame age, RSS, temperature, recall, false positives, and APK delta;
  - write `/sdcard/Android/data/com.yinxin.uavfir.benchmark/files/fire-detector-benchmark.json`.

- [ ] Implement engine adapters with identical preprocessing and NMS. Convert RGBA to the manifest input tensor without a `Bitmap` allocation. Map output boxes back to normalized source-frame coordinates.

- [ ] Run unit tests.

```bash
cd rcplus-msdk-agent
./gradlew :fire-detector-benchmark:testDebugUnitTest
```

Expected: all selection-policy tests pass.

- [ ] Install/run the benchmark on the RC Plus 2 and pull the result.

```bash
export ADB_SERIAL=192.168.50.141:5555
adb -s "$ADB_SERIAL" get-state
./gradlew :fire-detector-benchmark:connectedDebugAndroidTest
adb -s "$ADB_SERIAL" pull \
  /sdcard/Android/data/com.yinxin.uavfir.benchmark/files/fire-detector-benchmark.json \
  build/fire-detector-benchmark.json
```

Expected: device state `device`; instrumentation passes; result contains three completed engine records and one `selectedEngine`.

- [ ] Apply the hard gate. If no engine passes all three thresholds, stop implementation and record the result in the design spec; do not weaken thresholds or proceed to Task 3. If an engine passes, preserve the JSON result as release evidence and use only `selectedEngine` in Task 3.

- [ ] Commit the benchmark harness without generated binaries/results.

```bash
git add rcplus-msdk-agent/settings.gradle.kts rcplus-msdk-agent/fire-detector-benchmark
git commit -m "test(agent): add mobile detector benchmark gate"
```

### Task 3: Add the selected production detector and latest-frame pipeline

**Files:**
- Modify `rcplus-msdk-agent/app/build.gradle.kts`
- Create production detector files and tests from the file map
- Modify `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt`

- [ ] Write failing tests for `LatestThermalFrameBuffer`: capacity one, replacement releases the previous frame, one inference at a time, and consumed frame age is measurable.

- [ ] Write failing contract tests for `OnDeviceFireDetector`: invalid model hash refuses arming; preprocessing preserves ROI coordinates; output confidence and boxes match the selected benchmark adapter on golden samples.

- [ ] Add only the selected runtime dependency and its required native packaging. Copy only the selected model artifact into `app/src/main/assets/fire-detection/` and create `model-manifest.json` with the benchmark-selected engine, source SHA-256, artifact SHA-256, input/output contract, version, and thresholds.

- [ ] Implement `SelectedRuntimeFireDetector` by moving the selected benchmark adapter's preprocessing/inference/postprocessing code into the production package. `SelectedFireDetectorFactory` must verify SHA-256 before constructing the detector.

- [ ] Add a `ThermalFrameListener` to `ThermalFrameProbe`. For valid infrared RGBA frames, offer a lightweight immutable frame descriptor to the latest-frame buffer before hotspot sampling or screenshot logic. Copy bytes only when the buffer accepts the frame; do not block the MSDK callback.

- [ ] Run focused and full Agent tests.

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests 'com.yinxin.uavfir.firedetection.*'
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass; existing stream/hotspot tests remain green.

- [ ] Commit.

```bash
git add rcplus-msdk-agent/app/build.gradle.kts \
  rcplus-msdk-agent/app/src/main/assets/fire-detection \
  rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection \
  rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt \
  rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/firedetection
git commit -m "feat(agent): run thermal fire detector on latest frames"
```

### Task 4: Implement the deterministic scan state machine

**Files:**
- Create `FireDetectionModels.kt`
- Create `FireScanStateMachine.kt`
- Create `FireScanStateMachineTest.kt`

- [ ] Define typed states:

```kotlin
enum class FireScanState {
    DISARMED, ARMING, ARMED, SCANNING, PAUSE_REQUESTED,
    HOVER_VERIFYING, CONFIRMED, RESUME_REQUESTED, MANUAL_HOLD
}
```

- [ ] Define one immutable policy with:

```kotlin
candidateThreshold = 0.10f
immediateThreshold = 0.50f
candidateWindowSize = 3
candidateVotesRequired = 2
targetInferenceFps = 5.0
maxInferenceGapMs = 1_000
hoverSpeedThresholdMps = 0.5
hoverStableDurationMs = 1_000
pauseTimeoutMs = 3_000
verificationDurationMs = 3_000
confirmationTemperatureC = 80.0
negativeFramesToClear = 5
```

- [ ] Write table-driven failing tests for every legal transition and explicit tests that:
  - one score `>= 0.50` requests pause immediately;
  - two scores `>= 0.10` in the latest three request pause;
  - duplicate candidates cannot issue a second pause;
  - inference silence over one second while scanning requests pause and then manual hold;
  - pause timeout enters `MANUAL_HOLD`;
  - a resume callback failure remains paused and enters `MANUAL_HOLD`;
  - confirmed remains held;
  - rejected verification requires five negative frames before returning to scanning;
  - manual resume is rejected from `CONFIRMED` unless the operator first disarms.

- [ ] Implement a pure reducer returning state plus effects (`PauseMission`, `BeginVerification`, `ResumeMission`, `EmitEvent`). Keep MSDK and network calls outside the reducer.

- [ ] Run tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests \
  com.yinxin.uavfir.firedetection.FireScanStateMachineTest
git add app/src/main/java/com/yinxin/uavfir/firedetection \
  app/src/test/java/com/yinxin/uavfir/firedetection
git commit -m "feat(agent): add fire scan safety state machine"
```

### Task 5: Make mission pause/resume awaitable and verify hover

**Files:**
- Modify `WaypointMissionExecutor.kt`
- Modify `AppServices.kt`
- Create `FireVerificationCoordinator.kt`
- Create `FireVerificationCoordinatorTest.kt`
- Modify `WaypointMissionExecutorSourceTest.kt`

- [ ] Add failing tests for an awaitable mission control adapter:
  - pause completes only on MSDK success callback;
  - pause failure is surfaced;
  - repeated pause is idempotent for one candidate;
  - resume completes only on success and retains the active mission ID;
  - an observed DJI mission execution state distinguishes command acceptance from `PAUSED`;
  - hover requires horizontal speed below `0.5 m/s` continuously for one second.

- [ ] Change `WaypointMissionExecutor.pauseMission()` and `resumeMission()` to expose suspend wrappers backed by `suspendCancellableCoroutine`, while retaining nonblocking entry points required by `WaylineAgentCommandRouter`.

- [ ] Extract a reusable flight-state provider from the same MSDK velocity key used by `DjiDeviceSession`; do not depend on backend OSD round trips.

- [ ] Implement `FireVerificationCoordinator`:
  1. issue pause once;
  2. wait at most three seconds for both observed DJI mission state `PAUSED` and one continuous second below `0.5 m/s`;
  3. if timeout/failure, return `MANUAL_HOLD`;
  4. while hovering, collect detector results and `measureThermalHotspot` results for three seconds;
  5. confirm when a stable model candidate has measured region temperature `>= 80 C`;
  6. reject when temperature is `< 80 C` and the model candidate is no longer stable;
  7. return manual hold for missing or contradictory evidence.

- [ ] Reuse normalized detection boxes as the seed `ThermalMeasureRegion`; retain the existing multi-region fallback in `DjiMsdkStreamBinder`.

- [ ] Run focused tests.

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests \
  com.yinxin.uavfir.firedetection.FireVerificationCoordinatorTest \
  --tests com.yinxin.uavfir.wayline.WaypointMissionExecutorSourceTest
```

Expected: all tests pass.

- [ ] Commit.

```bash
git add rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt \
  rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt \
  rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/firedetection \
  rcplus-msdk-agent/app/src/test
git commit -m "feat(agent): pause and verify fire candidates in hover"
```

### Task 6: Coordinate inference, watchdog, and mission effects

**Files:**
- Create `FireDetectionCoordinator.kt`
- Create `FireDetectionCoordinatorTest.kt`
- Modify `RealMsdkStreamProvider.kt`
- Modify `AppServices.kt`

- [ ] Write coroutine tests with virtual time for arm/disarm, five-FPS scheduling, latest-frame replacement, inference watchdog, one-shot pause, confirmed hold, rejected resume, and clean cancellation.

- [ ] Implement one supervisor-owned coordinator loop:
  - `arm()` verifies model hash/load, thermal camera capability, a decoded thermal frame no older than 300 ms, available pause/resume control, and a writable durable event store before entering `ARMED`;
  - `startScanning()` consumes only the newest frame;
  - inference never overlaps;
  - every result includes `capturedAt`, `inferenceStartedAt`, `completedAt`, frame age, inference duration, model version, and bounding boxes;
  - watchdog effects have priority over new inference;
  - disarm closes detector resources and clears pending frames.

- [ ] Wire the coordinator in `AppServices` without removing `ThermalHotspotMonitor` yet. The legacy monitor remains telemetry-only and may not call `FireConfirmationProcessor` when the on-device coordinator is armed.

- [ ] Run full Agent unit tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest
git add app/src/main/java/com/yinxin/uavfir/AppServices.kt \
  app/src/main/java/com/yinxin/uavfir/stream/RealMsdkStreamProvider.kt \
  app/src/main/java/com/yinxin/uavfir/firedetection \
  app/src/test/java/com/yinxin/uavfir/firedetection
git commit -m "feat(agent): coordinate realtime fire scan and mission hold"
```

### Task 7: Add durable ordered Agent event delivery

**Files:**
- Create `FireDetectionEventStore.kt`
- Create `FireDetectionEventStoreTest.kt`
- Create `FireDetectionEventRequest.kt`
- Modify `DualStreamApi.kt`
- Modify `AgentBackendClient.kt`
- Modify `AgentBackendClientTest.kt`
- Modify `AppServices.kt`

- [ ] Write failing tests for an append-only JSONL store in `application.filesDir/fire-detection-events`:
  - atomic append and restart recovery;
  - monotonically increasing local sequence;
  - ordered delivery;
  - acknowledge/delete only after 2xx;
  - retry after network failure without duplication;
  - bounded retention of 10,000 events or 100 MB, never deleting unuploaded `CONFIRMED` events.

- [ ] Define three event kinds: `fire_detection_status`, `fire_candidate`, and `fire_verification`. Common fields must include event ID, local sequence, task ID, drone SN, source timestamp, state, policy version, model version/hash, frame age, inference duration, and queue timestamp.

- [ ] Add `POST /manage/api/v1/fire-detection/agents/{droneSn}/events`. Make backend idempotency key `eventId`; Agent retries the oldest unacknowledged event first.

- [ ] Emit status on every transition, candidate on pause-triggering evidence, and verification on confirmed/rejected/manual-hold outcome. Store locally before attempting HTTP.

- [ ] Run tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests \
  com.yinxin.uavfir.firedetection.FireDetectionEventStoreTest \
  --tests com.yinxin.uavfir.api.AgentBackendClientTest
git add app/src/main/java/com/yinxin/uavfir/firedetection \
  app/src/main/java/com/yinxin/uavfir/api app/src/main/java/com/yinxin/uavfir/AppServices.kt \
  app/src/test
git commit -m "feat(agent): queue fire detection events offline"
```

### Task 8: Extend backend command, status, and confirmed-event contracts

**Files:**
- Create backend DTOs from the file map
- Modify `FireDetectionController.java`
- Modify `FireDetectionService.java`
- Modify `DualStreamServiceImpl.java`
- Modify `DualStreamCommandDTO.java`
- Modify `FireDetectionServiceTest.java`
- Modify `DualStreamServiceImplTest.java`
- Modify `DualStreamControllerTest.java`

- [ ] Write failing backend tests for commands:
  - `fire-detection-arm`
  - `fire-detection-disarm`
  - `fire-detection-manual-confirm`
  - `fire-detection-manual-resume`

- [ ] Write failing event ingestion tests for idempotency, ordered status projection, stale-sequence rejection, and all three event kinds.

- [ ] Write the safety invariant test first: candidate and status events never call `createConfirmedFireEvent`; only a `fire_verification` event with outcome `CONFIRMED` may do so.

- [ ] Refactor `FireDetectionService.startForDrone` to arm the Agent by default. Keep the current Python task start behind an explicit `mode=cloud-fallback` request. `stopForDrone` disarms whichever mode owns the task.

- [ ] On confirmed ingestion, translate evidence into the existing `DualStreamEventDTO` contract, call the existing geolocation/`createConfirmedFireEvent` path once, and let the existing approach dispatcher continue unchanged.

- [ ] Expose status with state, armed mode, FPS, latest frame age, inference P95, queue depth, model version, last verification, and failure reason.

- [ ] Run focused backend tests.

```bash
cd backend
mvn -pl uavfire -Dtest=FireDetectionServiceTest,DualStreamServiceImplTest,DualStreamControllerTest test
```

Expected: all tests pass; test logs show no formal fire event for candidate-only payloads.

- [ ] Commit.

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/firedetection \
  backend/uavfire/src/main/java/com/yx/uavfire/manage \
  backend/uavfire/src/test/java/com/yx/uavfire/firedetection \
  backend/uavfire/src/test/java/com/yx/uavfire/manage
git commit -m "feat(backend): control and persist Agent fire detection"
```

### Task 9: Execute new commands in the Agent

**Files:**
- Modify `MsdkCommandExecutor.kt`
- Modify `CommandPollingCoordinator.kt`
- Modify `CommandPollingCoordinatorTest.kt`
- Modify `AppServices.kt`

- [ ] Add failing tests that normalize and route the four fire-detection commands, acknowledge success only after the state transition completes, and return a failed acknowledgement for invalid transitions.

- [ ] Treat arm/disarm/manual commands as urgent MSDK-plane commands. Route them directly to `FireDetectionCoordinator`; do not pass them through stream focus or Python confirmation logic.

- [ ] Make manual confirm legal only in `HOVER_VERIFYING` or `MANUAL_HOLD`. Make manual resume legal only in `MANUAL_HOLD` or rejected verification; confirmed incidents require disarm before resuming.

- [ ] Include resulting state and local event ID in acknowledgement messages so backend/UI polling can reconcile command completion with event delivery.

- [ ] Run tests and commit.

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests \
  com.yinxin.uavfir.api.CommandPollingCoordinatorTest
git add app/src/main/java/com/yinxin/uavfir/api \
  app/src/main/java/com/yinxin/uavfir/AppServices.kt app/src/test
git commit -m "feat(agent): execute fire detection control commands"
```

### Task 10: Arm before a detection-enabled wayline starts

**Files:**
- Modify `PlannedWaylineServiceImpl.java`
- Modify `WaylineAgentEventListener.java`
- Modify `PlannedWaylineServiceTest.java`
- Modify `WaylineAgentEventListenerTest.java`

- [ ] Write failing orchestration tests:
  - detection-enabled mission sends arm before wayline start/dispatch;
  - mission start is blocked until Agent reports `ARMED`;
  - arm timeout/failure does not dispatch the mission;
  - operator-selected cloud fallback starts Python and may dispatch after fallback readiness;
  - mission finish/failure/cancel sends disarm;
  - waypoint index zero no longer starts detection.

- [ ] Add a `fireDetectionMode` mission preparation field with values `agent`, `cloud-fallback`, and `disabled`; default new detection-enabled missions to `agent`.

- [ ] During preparation, enqueue arm, poll projected Agent status with a bounded 10-second readiness timeout, then dispatch the wayline. Return a clear preparation error if not armed.

- [ ] Remove the `currentWaypointIndex == 0` start trigger from `WaylineAgentEventListener`. Keep finish/failure cleanup idempotent.

- [ ] Run focused tests and commit.

```bash
cd backend
mvn -pl uavfire -Dtest=PlannedWaylineServiceTest,WaylineAgentEventListenerTest test
git add uavfire/src/main/java/com/yx/uavfire/wayline \
  uavfire/src/test/java/com/yx/uavfire/wayline
git commit -m "feat(wayline): require fire detector readiness before flight"
```

### Task 11: Expose operational state and manual safety controls

**Files:**
- Modify `frontend/src/api/manage.ts`
- Create `frontend/src/api/fire-detection-state.mjs`
- Create `frontend/src/api/fire-detection-state.d.ts`
- Modify `leadership-cockpit.vue`
- Create `frontend/scripts/agent-fire-detection-status.test.mjs`

- [ ] Write failing normalizer tests for all states and stale telemetry. The UI must distinguish `DISARMED`, `ARMING`, `SCANNING`, `PAUSE_REQUESTED`, `HOVER_VERIFYING`, `CONFIRMED`, `RESUME_REQUESTED`, and `MANUAL_HOLD`.

- [ ] Extend API types/actions for mode, arm/disarm, manual confirm, and manual resume.

- [ ] In the existing cockpit fire-detection control area, show compact status fields: state, mode, inference FPS, latest frame age, inference P95, model version, offline queue depth, and last failure. Mark frame data stale when age exceeds 300 ms.

- [ ] Use explicit buttons:
  - arm/disarm for normal control;
  - manual confirm only during verification/manual hold;
  - manual resume only when backend says it is legal.
  Disable controls while their command is pending and surface acknowledgement failure.

- [ ] Do not add another video player or card. Reuse the existing live HUD and control strip.

- [ ] Run frontend tests and build.

```bash
cd frontend
node --test scripts/agent-fire-detection-status.test.mjs \
  scripts/leadership-cockpit-livestream.test.mjs
npm run build
```

Expected: tests pass; production build succeeds.

- [ ] Commit.

```bash
git add frontend/src/api/manage.ts frontend/src/api/fire-detection-state.mjs \
  frontend/src/api/fire-detection-state.d.ts \
  frontend/src/pages/page-web/projects/leadership-cockpit.vue \
  frontend/scripts/agent-fire-detection-status.test.mjs
git commit -m "feat(frontend): show Agent fire detection safety state"
```

### Task 12: Enforce Python shadow/fallback isolation

**Files:**
- Modify `ai-service/app/config/settings.py`
- Modify `ai-service/app/services/fire_event_reporter.py`
- Modify `ai-service/.env.example`
- Modify `ai-service/tests/test_fire_event_reporter.py`
- Modify backend tests if the reporter contract requires a shadow flag

- [ ] Write failing tests that `AI_SERVICE_MODE=shadow` may emit diagnostic detections but cannot call the confirmed-event endpoint. Write a separate test that `AI_SERVICE_MODE=cloud-fallback` retains the existing formal reporting path only when backend has explicitly assigned fallback ownership.

- [ ] Add `shadow`, `cloud-fallback`, and `disabled` modes. Default deployment configuration to `shadow`.

- [ ] Tag shadow reports with source model/version and make backend store them as diagnostics outside the formal `FireEvent` path.

- [ ] Run AI and backend regression tests.

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_fire_event_reporter.py \
  tests/test_continuous_runner.py tests/test_task_lifecycle.py -q
cd ../backend
mvn -pl uavfire -Dtest=FireDetectionServiceTest,DualStreamServiceImplTest test
```

Expected: all tests pass; shadow tests prove flight control and formal confirmation are unreachable.

- [ ] Commit.

```bash
git add ai-service/app/config/settings.py ai-service/app/services/fire_event_reporter.py \
  ai-service/.env.example ai-service/tests/test_fire_event_reporter.py \
  backend/uavfire/src/test
git commit -m "fix(ai): isolate shadow detection from flight control"
```

### Task 13: Run integration, device, and flight acceptance gates

**Files:**
- Create `docs/runbooks/agent-fire-detection-flight-acceptance.md`

- [ ] Write the runbook before testing. Include operator, aircraft, payload/camera, model hash, APK hash, weather, altitude, route, emergency takeover, expected state timeline, log collection, and abort criteria.

- [ ] Run all automated suites.

```bash
cd ai-service && ./.venv/bin/python -m pytest -q
cd ../rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd ../backend && mvn -pl uavfire test
cd ../frontend && npm run test:policies && \
  node --test scripts/agent-fire-detection-status.test.mjs \
  scripts/leadership-cockpit-livestream.test.mjs && npm run build
```

Expected: all commands exit zero.

- [ ] Install the exact APK that passed tests and verify its hash/model manifest.

```bash
export ADB_SERIAL=192.168.50.141:5555
cd rcplus-msdk-agent
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ADB_SERIAL" shell dumpsys package com.yinxin.uavfir | rg versionName
```

- [ ] Run a stationary bench test for at least 30 minutes. Acceptance:
  - inference P95 `<= 200 ms`;
  - effective inference rate `>= 5 FPS`;
  - latest frame age P95 `<= 300 ms`;
  - no rising memory trend or final-five-minute P95 degradation over 20%;
  - airplane/RC network loss preserves scanning and ordered queued events;
  - detector crash/watchdog pauses and enters `MANUAL_HOLD`.

- [ ] Run controlled low-risk flight drills before real-fire passes:
  - pause callback failure;
  - hover speed never settles;
  - temperature unavailable;
  - backend offline then reconnect;
  - manual confirm;
  - manual resume after rejection;
  - confirmed incident remains paused.

- [ ] Run 20 instrumented passes over the approved real-fire target at `5 m/s`. All 20 must issue pause; candidate-to-pause-command P95 must be `<= 700 ms`. Record any miss as a release blocker.

- [ ] Run a 10-minute no-fire route under comparable thermal conditions. Acceptance is at most one false pause.

- [ ] Confirm rejected candidates resume from the exact DJI mission breakpoint and confirmed candidates enter the existing backend approach workflow exactly once.

- [ ] Add measured results and artifact hashes to the runbook. If any acceptance gate fails, leave Agent mode disabled by default and file the failure with captured logs; do not compensate by silently lowering thresholds.

- [ ] Commit the completed runbook and final configuration.

```bash
git add docs/runbooks/agent-fire-detection-flight-acceptance.md \
  rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json
git commit -m "test(fire): record on-device detection acceptance"
```

## Final Verification

- [ ] Confirm the implementation matches every state and threshold in `docs/superpowers/specs/2026-07-27-agent-on-device-fire-detection-design.md`.
- [ ] Confirm only one production inference runtime and one model artifact are present in the APK.
- [ ] Confirm no RTSP URL, backend response, screenshot write, or network queue sits on the detection-to-pause path.
- [ ] Confirm candidates do not create formal fire records.
- [ ] Confirm `CONFIRMED` never auto-resumes.
- [ ] Confirm rejected verification clears only after five negative frames and resumes the same active mission.
- [ ] Confirm arm readiness precedes wayline dispatch.
- [ ] Confirm offline events upload in local-sequence order after reconnect.
- [ ] Confirm Python shadow mode cannot control flight or produce formal confirmation.
- [ ] Inspect the final diff for unrelated files:

```bash
git status --short
git diff --check
git log --oneline --decorate -15
```
