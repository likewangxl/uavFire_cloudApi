# Visible Fire Hover Laser Geolocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pause the active flight after a two-frame visible-fire confirmation, alert immediately, then use a stable-hover DJI laser measurement to replace the provisional aircraft position on the same event.

**Architecture:** AI publishes a normalized visible ROI on every analyzed visible frame and marks discrete fire events `LASER_LOCATING`. Backend persists/alerts immediately, coordinates the `visible-fire-hold` and `visible-fire-laser-measure` commands, and applies an idempotent laser-location update. The RC Plus Agent verifies stable hover, reacquires a fresh ROI through the backend, aims the gimbal, samples MSDK laser coordinates, and deliberately leaves the route paused.

**Tech Stack:** Python 3/FastAPI/Pydantic/pytest, Java 11/Spring Boot/MyBatis/JUnit 5/Mockito, Kotlin/Android/DJI MSDK V5/JUnit, Vue 3/TypeScript/Node test runner.

## Global Constraints

- Visible YOLO remains the only automatic fire detector; no thermal stream, thermal focus, or thermal measurement may be invoked.
- Alert creation must not wait for stable hover or laser completion.
- `LASER_LOCATING` and `LASER_FAILED` coordinates must not be presented or consumed as fire-point coordinates.
- Stable hover means horizontal speed `<= 0.3 m/s` and absolute vertical speed `<= 0.2 m/s` continuously for `1000 ms`, with an `8000 ms` timeout.
- Laser acquisition uses three valid `NORMAL` samples, `300 ms` between samples, maximum scatter `15 m`, and reported error radius `5 m`.
- Success and failure leave the aircraft/wayline paused until an explicit human command resumes or redirects it.
- Each aircraft may own at most one localization session; callbacks and commands are idempotent by event ID.
- Preserve all unrelated dirty-worktree changes and do not commit model weights.

---

### Task 1: Publish Fresh Visible ROI From AI

**Files:**
- Modify: `ai-service/app/models/event.py`
- Modify: `ai-service/app/services/continuous_runner.py`
- Modify: `ai-service/app/services/task_registry.py`
- Modify: `ai-service/app/services/fire_event_reporter.py`
- Test: `ai-service/tests/test_continuous_runner.py`
- Test: `ai-service/tests/test_task_registry_backend_reporting.py`
- Test: `ai-service/tests/test_fire_event_reporter.py`

**Interfaces:**
- Produces: `visible_roi: Optional[NormalizedRoi]` on `DualStreamEvent`.
- Produces: snake/camel payload aliases `visible_roi` and `visibleRoi`.
- Produces: discrete visible fire payload fields `geo_method=LASER_RANGEFINDER` and `geo_quality=LASER_LOCATING` only when a valid ROI exists.
- Consumes: detector `last_boxes` entries `{x1,y1,x2,y2,conf}` and current frame dimensions.

- [ ] **Step 1: Write failing ROI model and payload tests**

```python
def test_visible_frame_event_reports_normalized_top_box_roi():
    # 200x100 frame, box (100, 20)-(180, 60)
    assert payload["visible_roi"] == {
        "x": 0.5, "y": 0.2, "width": 0.4, "height": 0.4
    }
    assert payload["visibleRoi"] == payload["visible_roi"]


def test_visible_fire_payload_requests_laser_location_when_roi_exists():
    assert payload["geo_method"] == "LASER_RANGEFINDER"
    assert payload["geo_quality"] == "LASER_LOCATING"
    assert payload["visible_roi"] == expected_roi


def test_visible_fire_payload_without_box_does_not_claim_laser_locating():
    assert "visible_roi" not in payload
    assert payload["geo_quality"] == "LASER_FAILED"
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd ai-service
PYTHONPATH=. .venv/bin/pytest -q \
  tests/test_continuous_runner.py \
  tests/test_task_registry_backend_reporting.py \
  tests/test_fire_event_reporter.py
```

Expected: failures because `visible_roi` and its location-state fields do not exist.

- [ ] **Step 3: Add the normalized ROI type and extraction helper**

```python
class NormalizedRoi(BaseModel):
    x: float
    y: float
    width: float
    height: float


def _top_visible_roi(boxes: object, frame: object) -> Optional[NormalizedRoi]:
    if not boxes or frame is None:
        return None
    height, width = frame.shape[:2]
    top = max(boxes, key=lambda item: float(item.get("conf", 0.0)))
    return NormalizedRoi(
        x=max(0.0, min(1.0, float(top["x1"]) / width)),
        y=max(0.0, min(1.0, float(top["y1"]) / height)),
        width=max(0.0, min(1.0, (float(top["x2"]) - float(top["x1"])) / width)),
        height=max(0.0, min(1.0, (float(top["y2"]) - float(top["y1"])) / height)),
    )
```

Set it on the visible `DualStreamEvent`, include both JSON aliases in per-frame reporting, and include it in the discrete fire payload. Reuse the same ROI for the two-frame center check to avoid divergent box selection.

- [ ] **Step 4: Run the focused AI tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 5: Commit Task 1**

```bash
git add ai-service/app ai-service/tests
git commit -m "feat(ai): publish visible fire target roi"
```

---

### Task 2: Add Backend Localization State and Idempotent Coordinate Upgrade

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/param/FireLaserLocationParam.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/param/FireEventCreateParam.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/FireEventService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/entity/FireEventHistoryEntity.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/model/dto/FireEventHistoryDTO.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImplMergeTest.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImplOsdFillTest.java`

**Interfaces:**
- Consumes: create fields `visibleRoi`, `geoMethod`, `geoQuality`.
- Produces: `boolean applyLaserLocation(String eventId, FireLaserLocationParam param)`.
- Produces: `boolean markLaserLocationFailed(String eventId, String reason, long sourceTs)`.
- Invariant: OSD values remain stored as aircraft telemetry while `LASER_LOCATING`; spatial dedup and automatic approach are disabled until `PRECISE`.

- [ ] **Step 1: Write failing backend service tests**

```java
@Test
void laserLocatingEventStoresAircraftTelemetryButSkipsSpatialDedupAndApproach() {
    FireEventCreateParam param = visibleLaserLocatingEvent();
    service.create(param);
    FireEventEntity inserted = insertedEvent();
    assertEquals("LASER_LOCATING", inserted.getGeoQuality());
    assertEquals(inserted.getLat(), inserted.getAircraftLat());
    assertEquals(inserted.getLng(), inserted.getAircraftLng());
    verify(approachDispatcher, never()).dispatchIfEligible(any());
    verify(eventMapper, never()).acquireNamedLock(anyString(), anyInt());
}

@Test
void laserSuccessUpdatesSameEventAndIsIdempotent() {
    assertTrue(service.applyLaserLocation("fire-1", preciseLaserParam()));
    assertFalse(service.applyLaserLocation("fire-1", preciseLaserParam()));
    assertEquals("PRECISE", stored.getGeoQuality());
    assertEquals("LASER_RANGEFINDER", stored.getGeoMethod());
    assertEquals(34.960123, stored.getLat());
}

@Test
void laserFailureCannotOverwritePreciseLocation() {
    assertFalse(service.markLaserLocationFailed("fire-1", "target-lost", now));
    assertEquals("PRECISE", stored.getGeoQuality());
}
```

- [ ] **Step 2: Run focused backend tests and verify RED**

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire \
  -Dtest=FireEventServiceImplMergeTest,FireEventServiceImplOsdFillTest test
```

Expected: compilation/test failures for the missing localization API and aircraft telemetry semantics.

- [ ] **Step 3: Implement request types and service methods**

```java
@Data
public class FireLaserLocationParam {
    @NotNull private Double fireLat;
    @NotNull private Double fireLng;
    private Double fireAlt;
    @NotNull private Long sourceTs;
    private Double geoErrorRadiusM;
}
```

Implement transactional compare-and-set semantics:

```java
if (!"LASER_LOCATING".equals(existing.getGeoQuality())) {
    return false;
}
existing.setLat(param.getFireLat());
existing.setLng(param.getFireLng());
existing.setAlt(param.getFireAlt());
existing.setGeoMethod("LASER_RANGEFINDER");
existing.setGeoQuality("PRECISE");
existing.setGeoErrorRadiusM(param.getGeoErrorRadiusM() != null
    ? param.getGeoErrorRadiusM() : 5.0);
existing.setGeoSourceTs(param.getSourceTs());
```

Before OSD fills `lat/lng`, copy the current OSD position into `aircraftLat/aircraftLng/aircraftAlt`. Compute `spatialDedupCoordinateEligible` as false for `LASER_LOCATING` and `LASER_FAILED`. Gate `createdResponse()` so `FireApproachDispatcher` skips those two laser states while preserving all existing manual and ray/DEM behavior.

Record location changes in history using actions `LASER_LOCATED` and `LASER_FAILED`; both values fit the existing `varchar(16)` action column.

- [ ] **Step 4: Run focused backend tests and verify GREEN**

Run the Step 2 command. Expected: selected tests pass.

- [ ] **Step 5: Commit Task 2**

```bash
git add backend/uavfire/src/main backend/uavfire/src/test
git commit -m "feat(backend): track pending laser fire locations"
```

---

### Task 3: Coordinate Hold, Fresh ROI, and Laser ACKs in Backend

**Files:**
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamEventDTO.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamCommandAckDTO.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamCommandDTO.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/DualStreamController.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/VisibleFireLocalizationDispatcher.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImplMergeTest.java`

**Interfaces:**
- Consumes: per-frame `visibleRoi`.
- Produces: urgent commands `visible-fire-hold` and `visible-fire-laser-measure`.
- Produces: agent endpoint `GET /manage/api/v1/dual-stream/tasks/{taskId}/latest-visible-roi?after_source_ts=<ms>`.
- Produces: ACK fields `eventId`, `fireLat`, `fireLng`, `fireAlt`, `geoMethod`, `geoQuality`, `geoErrorRadiusM`, and optional laser target point.
- Maintains: latest valid visible ROI by task with its source timestamp; localization session by event ID/drone.

- [ ] **Step 1: Write failing coordinator tests**

```java
@Test
void laserLocatingCreateDispatchesOneUrgentHoldCommand() {
    service.startVisibleLaserLocalization("fire-1", "task-1", "DRONE-1", 1000L, roi());
    DualStreamCommandDTO command = service.pollCommand("DRONE-1");
    assertEquals("visible-fire-hold", command.getAction());
    assertTrue(command.getUrgent());
    assertEquals("fire-1", command.getParams().get("eventId"));
}

@Test
void stableAckUsesOnlyPostHoldFreshRoiForMeasureCommand() {
    acknowledgeStableHold();
    recordVisibleEvent(holdIssuedAt + 100, roi());
    DualStreamCommandDTO command = service.pollCommand("DRONE-1");
    assertEquals("visible-fire-laser-measure", command.getAction());
    assertEquals(roi(), command.getParams().get("visibleRoi"));
}

@Test
void staleOrMissingPostHoldRoiMarksEventFailedWithoutMeasureCommand() {
    acknowledgeStableHold();
    clock.advanceMillis(3001);
    service.expireLocalizationSessions();
    verify(fireEventService).markLaserLocationFailed("fire-1", "target-not-reacquired", clock.now());
}

@Test
void successfulLaserAckUpdatesOriginalEvent() {
    acknowledgeLaserSuccess();
    verify(fireEventService).applyLaserLocation(eq("fire-1"), argThat(p ->
        p.getFireLat().equals(34.960123) && p.getFireLng().equals(109.316456)));
}
```

- [ ] **Step 2: Run coordinator tests and verify RED**

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire \
  -Dtest=DualStreamServiceImplTest,FireEventServiceImplMergeTest test
```

Expected: failures for missing ROI fields, command actions, and ACK handling.

- [ ] **Step 3: Implement localization session coordinator**

Add a small internal value type rather than more parallel maps:

```java
private static final class VisibleLaserSession {
    String eventId;
    String taskId;
    String droneSn;
    long holdIssuedAt;
    long reacquireDeadlineAt;
    String phase; // HOLDING, REACQUIRING, MEASURING, COMPLETE, FAILED
}
```

On fire creation, invoke the focused dispatcher:

```java
visibleFireLocalizationDispatcher.dispatch(
    eventId, taskId, deviceSn, eventTimestamp, visibleRoi);
```

Store every valid visible ROI with `sourceTs`. On `HOVER_STABLE`, transition to `REACQUIRING`; select only an ROI newer than `holdIssuedAt`, no older than `1500 ms`, and center-close to the original ROI. Dispatch measure exactly once. On terminal laser ACK, call the Task 2 service method and clear the session.

Expose the same latest-ROI selection through the agent endpoint. It returns HTTP 204 when no ROI newer than `after_source_ts` exists; otherwise it returns the ROI plus its `sourceTs`. This endpoint lets the Agent verify the target again after moving the gimbal without running a second model on the RC Plus.

Add both actions to the urgent allowlist. Do not route them through thermal measurement handlers.

- [ ] **Step 4: Run coordinator tests and verify GREEN**

Run the Step 2 command. Expected: selected tests pass.

- [ ] **Step 5: Commit Task 3**

```bash
git add backend/uavfire/src/main backend/uavfire/src/test
git commit -m "feat(backend): coordinate hover laser localization"
```

---

### Task 4: Implement Agent Hold and Stable-Hover Verification

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/VisibleFireLaserLocator.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentCommandAckRequest.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/DualStreamApi.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/CommandPollingCoordinator.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/session/DualStreamSessionManager.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/VisibleFireLaserLocatorTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/CommandPollingCoordinatorTest.kt`

**Interfaces:**
- Produces: `hold(eventId): VisibleFireLaserResult`.
- Consumes: `MissionHoldControl`, `FlightControlActionClient.hover()`, and `AircraftVelocityProvider`.
- Produces: `HOVER_STABLE`, `LASER_FAILED:hover-command-failed`, or `LASER_FAILED:hover-stability-timeout`.
- Invariant: it never calls `resumeAfterConfirmation()`.

- [ ] **Step 1: Write failing hold/stability tests**

```kotlin
@Test
fun `hold pauses route then requires one continuous second of stable velocity`() = runTest {
    velocity.values = listOf(
        VelocitySample(0.4, 0.0),
        VelocitySample(0.2, 0.1),
        VelocitySample(0.2, 0.1),
        VelocitySample(0.2, 0.1),
    )
    val result = locator.hold("fire-1")
    assertEquals("applied", result.status)
    assertEquals("HOVER_STABLE", result.message)
    assertTrue(missionHold.holdCalled)
    assertFalse(missionHold.resumeCalled)
}

@Test
fun `hold times out when aircraft never stabilizes and stays paused`() = runTest {
    velocity.default = VelocitySample(1.0, 0.4)
    val result = locator.hold("fire-1")
    assertEquals("failed", result.status)
    assertEquals("LASER_FAILED:hover-stability-timeout", result.message)
    assertFalse(missionHold.resumeCalled)
}
```

- [ ] **Step 2: Run Agent tests and verify RED**

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest \
  --tests com.yinxin.uavfir.api.VisibleFireLaserLocatorTest \
  --tests com.yinxin.uavfir.api.CommandPollingCoordinatorTest
```

Expected: compilation failures because the locator and action routes do not exist.

- [ ] **Step 3: Implement velocity provider and hold state**

```kotlin
data class VelocitySample(val horizontalMps: Double, val verticalMps: Double)

class DjiAircraftVelocityProvider : AircraftVelocityProvider {
    override fun current(): VelocitySample? {
        val velocity = FlightControllerKey.KeyAircraftVelocity.create()
            .get(Velocity3D(Double.NaN, Double.NaN, Double.NaN))
        return VelocitySample(
            horizontalMps = hypot(velocity.x, velocity.y),
            verticalMps = abs(velocity.z),
        )
    }
}
```

Use monotonic time, reset the stable-window start whenever either threshold is exceeded, and poll at `200 ms`. If there is no active route, explicitly call `hover()`. If route pause fails, call `hover()` as fallback. Keep an atomic session containing the owning event ID.

Route `visible-fire-hold` parameters through `DualStreamSessionManager` and include the event ID in ACKs.

- [ ] **Step 4: Run focused Agent tests and verify GREEN**

Run the Step 2 command. Expected: selected tests pass.

- [ ] **Step 5: Commit Task 4**

```bash
git add rcplus-msdk-agent/app/src/main rcplus-msdk-agent/app/src/test
git commit -m "feat(agent): hold aircraft for visible fire location"
```

---

### Task 5: Aim and Sample DJI Laser Without Thermal Actions

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/VisibleFireLaserLocator.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/FireConfirmationProcessor.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/session/DualStreamSessionManager.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/CommandPollingCoordinator.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/VisibleFireLaserLocatorTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/FireConfirmationProcessorTest.kt`

**Interfaces:**
- Extracts/reuses: `DjiLaserRangefinderClient` and robust sample helpers without invoking the old thermal/fire-approach mission.
- Consumes: `measure(eventId, visibleRoi): VisibleFireLaserResult`.
- Produces: ACK coordinate fields and `geoMethod=LASER_RANGEFINDER`, `geoQuality=PRECISE`, `geoErrorRadiusM=5.0`.

- [ ] **Step 1: Write failing aim/measurement tests**

```kotlin
@Test
fun `measure rejects event that does not own held session`() = runTest {
    locator.hold("fire-1")
    val result = locator.measure("fire-2", roi)
    assertEquals("LASER_FAILED:event-session-mismatch", result.message)
}

@Test
fun `measure aims visible roi and returns robust normal laser fix`() = runTest {
    laser.results = listOf(normalFixA, normalFixB, normalFixC)
    val result = locator.measure("fire-1", roi)
    assertEquals("LASER_LOCATED", result.message)
    assertEquals("LASER_RANGEFINDER", result.geoMethod)
    assertEquals("PRECISE", result.geoQuality)
    assertFalse(missionHold.resumeCalled)
    assertTrue(thermalActions.isEmpty())
}

@Test
fun `measure rejects scattered or non-normal laser samples`() = runTest {
    laser.results = listOf(nonNormal, farApartA, farApartB)
    assertEquals("LASER_FAILED:laser-fix-unavailable", locator.measure("fire-1", roi).message)
}
```

- [ ] **Step 2: Run focused Agent tests and verify RED**

Run the Task 4 Agent command. Expected: failures because measurement routing/result fields are missing.

- [ ] **Step 3: Extract reusable laser sampling and implement aim**

Move laser DTOs and robust-fix helpers into focused top-level/internal types usable by both processors. Do not duplicate MSDK key access.

Use `DJICameraKey.KeyTapZoomAtTarget` with normalized ROI center. After each settle, call the Task 3 latest-ROI endpoint with the gimbal-move timestamp as `after_source_ts`; require a response no older than `1500 ms`. Read `LaserMeasureInformation.targetPoint`, normalize its `[0,100]` coordinates to `[0,1]`, and require that point to fall inside the returned fresh ROI. Repeat aim with the returned ROI center, for at most three iterations. If the key is unsupported, the ROI is stale, or containment never succeeds, return a specific `LASER_FAILED` result. Only after containment succeeds, collect three `NORMAL` samples with the existing `300 ms` interval and `15 m` scatter rejection.

Extend `CommandExecutionResult` and ACK DTOs with:

```kotlin
val eventId: String? = null
val fireLat: Double? = null
val fireLng: Double? = null
val fireAlt: Double? = null
val geoMethod: String? = null
val geoQuality: String? = null
val geoErrorRadiusM: Double? = null
```

In `finally`, always attempt `KeyLaserMeasureEnabled=false`; log SDK shutdown failure without replacing the primary localization result. Clear the localization session and never call thermal focus/measurement or route resume.

- [ ] **Step 4: Run all Agent unit tests and verify GREEN**

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest
```

Expected: complete Agent unit suite passes.

- [ ] **Step 5: Build the debug APK**

```bash
cd rcplus-msdk-agent
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and a new debug APK.

- [ ] **Step 6: Commit Task 5**

```bash
git add rcplus-msdk-agent/app/src/main rcplus-msdk-agent/app/src/test
git commit -m "feat(agent): locate visible fire with dji laser"
```

---

### Task 6: Hide Provisional Coordinates in Frontend

**Files:**
- Modify: `frontend/src/pages/page-web/projects/fire/FireEventList.vue`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit-situation.mjs`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit-summary.mjs`
- Test: `frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-situation.test.mjs`
- Test: `frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-summary.test.mjs`
- Test: `frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-visual-context.test.mjs`
- Create: `frontend/src/pages/page-web/projects/__tests__/fire-event-laser-location.test.mjs`

**Interfaces:**
- Consumes: `geoQuality` values `LASER_LOCATING`, `LASER_FAILED`, and `PRECISE`.
- Produces: location display strings `正在精确定位`, `激光定位失败`, or formatted target coordinates.
- Invariant: situation maps and route-ready metrics exclude locating/failed events even when database `lat/lng` contain provisional OSD values.

- [ ] **Step 1: Write failing frontend policy tests**

```javascript
test('pending and failed laser events never expose provisional coordinates', () => {
  assert.equal(formatFireLocation({ lat: 34.9, lng: 109.3, geoQuality: 'LASER_LOCATING' }), '正在精确定位')
  assert.equal(formatFireLocation({ lat: 34.9, lng: 109.3, geoQuality: 'LASER_FAILED' }), '激光定位失败')
})

test('pending laser events are excluded while precise laser events are placed', () => {
  const layers = buildSituationLayers([
    { lat: 34.9, lng: 109.3, geoQuality: 'LASER_LOCATING' },
    { lat: 34.8, lng: 109.2, geoQuality: 'PRECISE', geoMethod: 'LASER_RANGEFINDER' },
  ])
  assert.equal(layers.locatedEvents.length, 1)
})
```

- [ ] **Step 2: Run frontend tests and verify RED**

```bash
cd frontend
npm test -- \
  src/pages/page-web/projects/__tests__/fire-event-laser-location.test.mjs \
  src/pages/page-web/projects/__tests__/leadership-cockpit-situation.test.mjs \
  src/pages/page-web/projects/__tests__/leadership-cockpit-summary.test.mjs \
  src/pages/page-web/projects/__tests__/leadership-cockpit-visual-context.test.mjs
```

Expected: missing helper/incorrect coordinate rendering failures.

- [ ] **Step 3: Implement one shared location-quality policy**

Create/export small pure helpers from an existing project policy module:

```javascript
export const isUsableFireCoordinate = event => {
  const quality = String(event?.geoQuality || '').toUpperCase()
  if (['LASER_LOCATING', 'LASER_FAILED'].includes(quality)) return false
  return ['PRECISE', 'AUTO_WAYPOINT_READY', 'READY', 'OK'].includes(quality)
}

export const formatFireLocation = event => {
  const quality = String(event?.geoQuality || '').toUpperCase()
  if (quality === 'LASER_LOCATING') return '正在精确定位'
  if (quality === 'LASER_FAILED') return '激光定位失败'
  return isUsableFireCoordinate(event)
    ? `${Number(event.lat).toFixed(6)}, ${Number(event.lng).toFixed(6)}`
    : '坐标待复核'
}
```

Use it in the fire list, leadership cockpit, map-layer construction, summaries, and any route-ready calculations touched by these screens. Keep aircraft coordinates visible only in the explicit aircraft-position detail.

- [ ] **Step 4: Run frontend tests and build**

```bash
cd frontend
npm test
npm run build
```

Expected: all frontend tests pass and production build exits 0.

- [ ] **Step 5: Commit Task 6**

```bash
git add frontend/src
git commit -m "feat(frontend): show laser fire location state"
```

---

### Task 7: Full Regression and Live RC Plus Validation

**Files:**
- Modify only if failures reveal an in-scope defect.
- Update: `HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md` with final commands, observed timestamps, and limitations.

**Interfaces:**
- Validates the complete chain with aircraft `1581F7K3D249C00AEK3P`.
- Does not delete historical events or resume a paused aircraft without explicit operator action.

- [ ] **Step 1: Run complete automated suites**

```bash
cd ai-service
PYTHONPATH=. .venv/bin/pytest -q

cd ../backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire test

cd ../rcplus-msdk-agent
./gradlew :app:testDebugUnitTest :app:assembleDebug

cd ../frontend
npm test
npm run build
```

Expected: zero failures and all builds exit 0.

- [ ] **Step 2: Deploy backend/AI and install Agent APK**

Restart only the affected local services using their existing tmux sessions. Install the newly built Agent APK through the already-connected RC Plus ADB target, preserving app data unless a schema/version failure requires a clean install.

- [ ] **Step 3: Verify visible-only regression before lighting a fire**

Observe at least 60 seconds:

- AI logs contain only `channel=visible`.
- Agent logs contain no `focusThermal`, thermal monitor enable, or thermal measurement.
- Backend emits no thermal measurement commands.

- [ ] **Step 4: Execute controlled fire localization**

With an operator ready to take control:

1. Start/continue the planned route.
2. Present one controlled visible fire target.
3. Record the two-frame confirmation timestamp.
4. Confirm `visible-fire-hold` is ACKed only after velocity stability.
5. Confirm alert exists immediately with `LASER_LOCATING`.
6. Confirm post-hover ROI timestamp is newer than hold issuance.
7. Confirm laser ACK contains `NORMAL`-derived target coordinates.
8. Confirm the same event transitions to `PRECISE`.
9. Confirm the aircraft remains paused.

- [ ] **Step 5: Compare coordinates and verify safety gates**

Compare the stored target with DJI Pilot 2’s laser pin. Verify:

- target difference is within the declared error radius or document the observed deviation;
- `aircraftLat/Lng` remain the detection-time aircraft position;
- no mission/route is auto-created before `PRECISE`;
- failure injection (cover laser or lose target) yields `LASER_FAILED` without deleting the alert;
- no duplicate event is created by the success callback.

- [ ] **Step 6: Update handoff and inspect final diff**

```bash
git diff --check
git status --short
git diff --stat
```

Document live evidence and any unvalidated flight-only condition in the handoff. Do not claim live success without timestamped log and database evidence from this run.
