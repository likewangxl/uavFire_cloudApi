# RC Plus Agent On-Device Fire Detection Design

Date: 2026-07-27

Branch: `feature/fire-precision-and-realtime-detection`

## 1. Background

During a monitoring wayline flight at 5 m/s, the aircraft passed over a fire
that was clearly visible in the thermal live view, but the system did not
detect it. This is a missed-detection problem rather than only a slow
notification problem.

The current primary detection path is:

```text
M4T thermal camera
  -> RC Plus Agent
  -> RTMP
  -> ZLMediaKit
  -> RTSP
  -> ai-service OpenCV decode
  -> PyTorch/Ultralytics YOLO
  -> backend FireEvent
```

The Agent can already receive decoded 1440x1080 thermal frames at about 30 FPS.
Its lightweight hotspot probe runs in about 1-2 ms, but that probe is not the
formal fire detector. The formal YOLO path is exposed to stream startup,
buffering, reconnect, network, and CPU inference delays. At 5 m/s, a detection
interval of 2.3-3.5 seconds moves the aircraft about 11.5-17.5 metres, which can
consume the useful observation window.

## 2. Goals

- Run the primary thermal fire detector on the RC Plus Agent using raw MSDK
  frames.
- Pause the active wayline and establish a hover as soon as a credible fire
  candidate is detected.
- Verify the candidate with multiple frames and an MSDK temperature
  measurement before creating a formal fire event.
- Resume the original wayline from its current breakpoint when a candidate is
  rejected.
- Preserve backend ownership of task state, event storage, geolocation
  aggregation, confirmation dispatch, and operator workflows.
- Keep `ai-service` as a shadow detector and explicit degraded fallback during
  migration.
- Continue detecting and entering a safe hold when the network is unavailable.

## 3. Non-Goals

- Moving backend event storage, geolocation aggregation, or business workflow
  code into the Android application.
- Removing `ai-service` in the first release.
- Automatically continuing a monitoring wayline when detector health is
  unknown.
- Starting an automatic approach when the aircraft has not confirmed a stable
  pause.
- Changing the existing 80 C measured-temperature confirmation policy.

## 4. Architecture

### 4.1 Agent Components

```text
ThermalFrameProbe
  -> LatestThermalFrameBuffer(capacity=1)
  -> OnDeviceFireDetector
  -> FireScanStateMachine
       -> WaypointMissionExecutor pause/resume
       -> MSDK region temperature measurement
       -> snapshot and telemetry capture
       -> AgentBackendClient event delivery
```

`ThermalFrameProbe` remains the owner of MSDK decoded-frame callbacks. It
publishes the newest eligible thermal frame to a capacity-one buffer.
`OnDeviceFireDetector` consumes from that buffer and never processes queued
historical frames. When inference is slower than frame production, old frames
are replaced.

`FireScanStateMachine` is the only component allowed to translate a detector
result into wayline pause or resume commands. The detector cannot call flight
control directly.

### 4.2 Backend Components

The backend continues to own:

- detector arm/disarm commands and actual Agent health status;
- fire candidate audit records;
- formal `FireEvent` creation for confirmed candidates;
- fire geolocation and multi-observation aggregation;
- approach/confirmation task dispatch;
- operator notifications and manual hold decisions.

The cockpit must render Agent-reported state. A backend task marked active is
not sufficient evidence that local inference is healthy.

### 4.3 ai-service Role

During migration, `ai-service` consumes the live stream in shadow mode and
records scores for comparison. It must not issue pause, resume, or approach
commands. It can become the primary detector only when an operator explicitly
starts a degraded cloud-detection mode before the wayline begins.

## 5. Inference Runtime

### 5.1 Model Conversion

The existing thermal YOLOv8n model is exported to these Android candidates:

- ONNX Runtime Mobile;
- TensorFlow Lite;
- NCNN FP16 with Vulkan where supported.

All exports are evaluated against the same versioned thermal validation set.
The production engine is selected by a deterministic gate:

1. Recall differs from the PyTorch reference by no more than two percentage
   points.
2. P95 inference latency on RC Plus 2 is at most 200 ms.
3. The engine runs for 30 minutes without a crash or sustained memory growth,
   and the final five-minute P95 latency is no more than 20 percent slower than
   the first five-minute P95 latency.
4. If multiple engines pass, choose the one with the lowest P95 latency. If
   results differ by less than 10 percent, choose the smaller measured APK
   size; an exact tie resolves in the order ONNX Runtime Mobile, TFLite, NCNN.
5. If none pass, optimize NCNN FP16/Vulkan and do not enable local detection
   for production waylines until the gate passes.

The model artifact is packaged with a version and SHA-256 digest. Detector
status reports both so the backend can reject an unexpected model.

### 5.2 Frame Policy

- Input must be an MSDK frame classified as thermal.
- The inference queue capacity is one.
- The initial inference target is at least 5 FPS.
- Detector health fails when no inference completes for more than one second
  while scanning.
- Confidence values and boxes use the unmodified model output and normalized
  frame coordinates.
- The existing brightness hotspot probe may add diagnostic evidence but cannot
  veto a YOLO candidate.

## 6. State Machine

### 6.1 States

```text
DISARMED
  -> ARMING
  -> ARMED
  -> SCANNING
  -> PAUSE_REQUESTED
  -> HOVER_VERIFYING
     -> CONFIRMED
     -> RESUME_REQUESTED -> SCANNING
     -> MANUAL_HOLD
```

Terminal mission completion, operator stop, or return-to-home moves the
detector to `DISARMED`. `MANUAL_HOLD` can only leave through an explicit
operator confirm, resume, or abort action.

### 6.2 Arming

The Agent preloads the model after MSDK initialization. Before a
detection-enabled wayline starts, the backend sends `fire-detection-arm` and
waits for `applied`. The Agent returns `applied` only when all of these are
true:

- the expected model and digest are loaded;
- the latest thermal decoded frame is no older than 300 ms;
- a successful inference completed within the preceding one second;
- wayline pause/resume control is available;
- local event persistence is writable.

The normal monitoring wayline does not start if arming fails. An operator may
explicitly choose cloud degraded mode instead.

### 6.3 Candidate Trigger

Initial thresholds are configuration values supplied by the arm command:

- candidate confidence: `0.10`;
- immediate single-frame confidence: `0.50`;
- temporal rule: at least two positive results among the most recent three
  completed inferences.

A result at or above `0.50` enters `PAUSE_REQUESTED` immediately. Otherwise,
the two-of-three rule applies. These low candidate thresholds intentionally
trade additional pauses for lower missed-detection risk.

### 6.4 Pause and Hover

On entry to `PAUSE_REQUESTED`:

1. Capture the candidate ID, source timestamp, box, confidence, aircraft
   position, gimbal attitude, mission ID, wayline ID, and waypoint index.
2. Invoke `WaypointMissionExecutor.pauseMission()` exactly once.
3. Wait for the MSDK paused state.
4. Require horizontal speed below 0.5 m/s continuously for one second.

If a stable hover cannot be confirmed within three seconds, enter
`MANUAL_HOLD`. Do not resume the wayline or dispatch an approach
automatically.

### 6.5 Hover Verification

After hover confirmation:

- collect the newest thermal inference results for a three-second verification
  window;
- measure the best candidate region using the existing MSDK multi-point and
  box-contained hotspot measurement path;
- retain thermal snapshots, model boxes, confidence history, actual
  temperature, aircraft telemetry, and gimbal attitude.

Decision rules:

- Actual measured temperature at or above 80 C confirms the fire.
- Actual measured temperature below 80 C plus no stable model candidate during
  the verification window rejects the candidate.
- Missing temperature, model failure, contradictory evidence, or incomplete
  telemetry enters `MANUAL_HOLD`.

`CONFIRMED` keeps the aircraft paused, reports the formal event, and waits for
the backend's existing confirmation/approach workflow. It does not start a
fly-to operation directly from the detector callback.

### 6.6 Resume

For a rejected candidate:

1. Invoke `resumeMission()` once.
2. Wait for MSDK to confirm resumed execution.
3. Preserve the existing mission and breakpoint; do not redispatch the KMZ.
4. Require five consecutive negative inference results before another
   candidate can trigger.
5. Return to `SCANNING`.

There is no long time-based cooldown because that would create a blind flight
distance.

## 7. Commands and Events

### 7.1 Backend-to-Agent Commands

`fire-detection-arm` includes:

- task and mission IDs;
- expected model version and SHA-256;
- candidate and immediate confidence thresholds;
- confirmation temperature;
- target inference FPS.

`fire-detection-disarm` stops scanning and releases inference resources.

`fire-detection-manual-confirm` converts the active manual hold into a
confirmed event.

`fire-detection-manual-resume` rejects the active manual hold and resumes the
existing mission after the same resume checks used by automatic rejection.

### 7.2 Agent-to-Backend Events

`fire_detection_status` contains:

- state and state transition timestamp;
- task and mission IDs;
- model version, digest, and inference engine;
- current and rolling inference FPS;
- P50/P95 inference latency;
- latest frame age;
- error code and message.

`fire_candidate` contains:

- stable candidate ID;
- source and detection timestamps;
- normalized boxes and confidence history;
- aircraft position, gimbal attitude, mission, wayline, and waypoint;
- pause request, acknowledgement, and stable-hover timestamps.

`fire_verification` contains:

- decision: `confirmed`, `rejected`, or `manual_hold`;
- measured temperature and measurement region;
- verification confidence history;
- thermal snapshot references;
- location evidence;
- failure reason where applicable.

Only `confirmed` creates a formal `FireEvent`. Candidates and rejected
verifications remain audit data.

## 8. Failure Handling

- **Detector not ready before start:** block the normal monitoring wayline.
- **Inference watchdog expires while flying:** request pause and enter
  `MANUAL_HOLD`.
- **Thermal frames stop or become stale:** request pause and enter
  `MANUAL_HOLD`.
- **Pause acknowledgement or stable hover fails:** enter `MANUAL_HOLD`; never
  approach or resume automatically.
- **Temperature cannot be measured:** remain in `MANUAL_HOLD`.
- **Resume fails:** remain paused and notify the operator.
- **Network unavailable:** local detection, pause, and verification continue.
  Events and evidence are written to a durable local queue and uploaded in
  order after reconnection.
- **Backend unavailable after confirmation:** keep the aircraft paused and
  retain the confirmed event locally until an operator acts or delivery
  succeeds.
- **Agent process restart during an active mission:** recover persisted state.
  If safe state cannot be proven, enter `MANUAL_HOLD` rather than resume.

No failure silently switches from local detection to cloud detection during
flight.

## 9. Cockpit Status

The cockpit displays the Agent's actual state:

- local detection not ready;
- local detection armed;
- scanning;
- candidate detected, pausing;
- hover verification;
- fire confirmed;
- candidate rejected, resuming;
- manual hold;
- cloud degraded mode.

It also displays inference FPS and latest-frame age. A stale Agent heartbeat
must replace the scanning indication with an error state.

## 10. Testing and Acceptance

### 10.1 Automated Tests

- Every valid and invalid state transition.
- Two-of-three temporal candidate triggering.
- Immediate high-confidence triggering.
- Capacity-one latest-frame replacement.
- Exactly-once pause and resume command issuance.
- Pause acknowledgement plus speed-based hover confirmation.
- Temperature-confirmed, rejected, contradictory, and unavailable outcomes.
- Manual-hold operator actions.
- Resume from the existing breakpoint.
- Five-negative-frame clear gate.
- Detector, frame, network, persistence, and process-restart failures.
- Durable event ordering and retry.
- Model version and digest mismatch.
- PyTorch-to-Android output and recall comparison.

### 10.2 RC Plus 2 Benchmarks

- P95 model inference latency at most 200 ms.
- P95 latest-frame age at most 300 ms.
- P95 candidate-frame-to-pause-command latency at most 700 ms.
- Thirty-minute continuous operation without crash or sustained memory growth;
  final five-minute P95 latency must be no more than 20 percent slower than the
  first five-minute P95 latency.

### 10.3 Flight Acceptance

- At least 20 passes over a real test fire at 5 m/s; all 20 must request a
  pause.
- At least ten minutes of no-fire flight; no more than one false pause.
- Every pause must preserve the wayline breakpoint.
- Rejected candidates must resume the original wayline.
- Confirmed candidates and uncertain failures must remain paused.
- Network-loss, backend-loss, stream-loss, detector-failure, and pause-failure
  drills must reach the state defined in this design.

The local detector becomes the primary production path only after all
acceptance gates pass. Until then, it runs in controlled test or shadow mode.
