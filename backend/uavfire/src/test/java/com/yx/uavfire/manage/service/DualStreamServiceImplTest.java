package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.impl.DualStreamServiceImpl;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.firedetection.FireDetectionActivityTracker;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class DualStreamServiceImplTest {
    @Test
    void latestDetectorIntentCoalescesArmThenDisarmBeforePoll() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "allowInMemoryDetectorIntent", true);

        service.setDetectorIntent("DRONE-INTENT", true);
        service.setDetectorIntent("DRONE-INTENT", false);
        DualStreamCommandDTO command = service.pollCommand("DRONE-INTENT");

        assertEquals("visible-detector-disarm", command.getAction());
        assertEquals(2L, ((Number) command.getParams().get("intentVersion")).longValue());
        assertEquals(command.getCommandId(), service.pollCommand("DRONE-INTENT").getCommandId());
    }

    @Test
    void durableDetectorIntentRestoresAfterBackendRestartBeforePoll() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl first = serviceWithRedis(redis, version);
        first.setDetectorIntent("DRONE-RESTART", true);

        DualStreamServiceImpl restarted = serviceWithRedis(redis, version);
        DualStreamCommandDTO restored = restarted.pollCommand("DRONE-RESTART");

        assertNotNull(restored);
        assertEquals("visible-detector-arm", restored.getAction());
        assertEquals(1L, ((Number) restored.getParams().get("intentVersion")).longValue());
    }

    @Test
    void staleArmedHeartbeatReplaysOnlyNewerDisarm() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "allowInMemoryDetectorIntent", true);
        service.setDetectorIntent("DRONE-RACE", true);
        service.setDetectorIntent("DRONE-RACE", false);

        service.acceptHeartbeat("DRONE-RACE", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-RACE")
                .setDetectorIntent("ARMED")
                .setDetectorIntentVersion(1L));
        DualStreamCommandDTO replay = service.pollCommand("DRONE-RACE");

        assertEquals("visible-detector-disarm", replay.getAction());
        assertEquals(2L, ((Number) replay.getParams().get("intentVersion")).longValue());
    }

    @Test
    void convergedHeartbeatKeepsRepeatedPollsEmptyIncludingBackendRestart() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl first = serviceWithRedis(redis, version);
        first.setDetectorIntent("DRONE-CONVERGED", true);
        first.acceptHeartbeat("DRONE-CONVERGED", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-CONVERGED")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING")
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(1L));

        assertNull(first.pollCommand("DRONE-CONVERGED"));
        assertNull(first.pollCommand("DRONE-CONVERGED"));
        assertNull(serviceWithRedis(redis, version).pollCommand("DRONE-CONVERGED"));
    }

    @Test
    void secondBackendNodeNewerDisarmOverridesFirstNodeCachedArm() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl first = serviceWithRedis(redis, version);
        DualStreamServiceImpl second = serviceWithRedis(redis, version);
        first.setDetectorIntent("DRONE-MULTI", true);
        second.setDetectorIntent("DRONE-MULTI", false);

        DualStreamCommandDTO command = first.pollCommand("DRONE-MULTI");

        assertEquals("visible-detector-disarm", command.getAction());
        assertEquals(2L, ((Number) command.getParams().get("intentVersion")).longValue());
    }

    @Test
    void observedAheadRaisesAuthorityFloorBeforeReissuingArm() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl service = serviceWithRedis(redis, version);
        service.setDetectorIntent("DRONE-FLOOR", true);

        service.acceptHeartbeat("DRONE-FLOOR", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-FLOOR")
                .setDetectorIntent("DISARMED")
                .setDetectorIntentVersion(5L));
        DualStreamCommandDTO command = service.pollCommand("DRONE-FLOOR");

        assertEquals("visible-detector-arm", command.getAction());
        assertEquals(6L, ((Number) command.getParams().get("intentVersion")).longValue());
    }

    @Test
    void operatorDisarmRecoversAboveObservationAfterRedisRecordAndCounterLoss() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl service = serviceWithRedis(redis, version);
        service.setDetectorIntent("DRONE-LOSS", true);
        redis.remove("dual-stream:detector-intent:DRONE-LOSS");
        redis.remove("dual-stream:detector-intent-version:DRONE-LOSS");
        version.set(0L);
        service.acceptHeartbeat("DRONE-LOSS", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-LOSS")
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(10L));

        DualStreamCommandDTO command = service.setDetectorIntent("DRONE-LOSS", false);

        assertEquals("visible-detector-disarm", command.getAction());
        assertEquals(11L, ((Number) command.getParams().get("intentVersion")).longValue());
    }

    @Test
    void observedAheadArmRaisesAuthorityFloorBeforeReissuingDisarm() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl service = serviceWithRedis(redis, version);
        service.setDetectorIntent("DRONE-FLOOR-DISARM", false);

        service.acceptHeartbeat("DRONE-FLOOR-DISARM", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-FLOOR-DISARM")
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorIntentVersion(7L));
        DualStreamCommandDTO command = service.pollCommand("DRONE-FLOOR-DISARM");

        assertEquals("visible-detector-disarm", command.getAction());
        assertEquals(8L, ((Number) command.getParams().get("intentVersion")).longValue());
    }

    @Test
    void persistedArmReconciliationHeartbeatReceivesSameVersionConfirmationCommand() {
        Map<String, String> redis = new ConcurrentHashMap<>();
        AtomicLong version = new AtomicLong();
        DualStreamServiceImpl service = serviceWithRedis(redis, version);
        service.setDetectorIntent("DRONE-RECONFIRM", true);

        service.acceptHeartbeat("DRONE-RECONFIRM", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-RECONFIRM")
                .setDetectorIntent("ARMED")
                .setDetectorState("BLOCKED")
                .setDetectorHealth("UNHEALTHY")
                .setDetectorReason("authority-reconciliation-required")
                .setDetectorIntentVersion(1L));
        DualStreamCommandDTO command = service.pollCommand("DRONE-RECONFIRM");

        assertEquals("visible-detector-arm", command.getAction());
        assertEquals(1L, ((Number) command.getParams().get("intentVersion")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectorAuthorityReadFailureIsUnavailableInsteadOfUsingProcessCache() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any(String.class))).thenThrow(new IllegalStateException("redis-unavailable"));
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        assertNull(service.getDetectorIntent("DRONE-REDIS-DOWN"));
    }

    @SuppressWarnings("unchecked")
    private DualStreamServiceImpl serviceWithRedis(Map<String, String> redis, AtomicLong version) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any(String.class))).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        when(template.execute(any(RedisScript.class), anyList(), any(String.class), any(String.class))).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            String intent = invocation.getArgument(2);
            long floor = Long.parseLong(invocation.getArgument(3));
            long next = version.updateAndGet(current -> Math.max(current, floor) + 1L);
            redis.put(keys.get(0), Long.toString(next));
            redis.put(keys.get(1), "{\"intent\":\"" + intent + "\",\"version\":" + next + "}");
            return next;
        });
        when(template.execute(
                any(RedisScript.class), anyList(), any(String.class), any(String.class), any(String.class)))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String intent = invocation.getArgument(2);
                    long expectedVersion = Long.parseLong(invocation.getArgument(3));
                    long floor = Long.parseLong(invocation.getArgument(4));
                    String expected = "{\"intent\":\"" + intent + "\",\"version\":" + expectedVersion + "}";
                    if (!expected.equals(redis.get(keys.get(1)))) {
                        return 0L;
                    }
                    long next = version.updateAndGet(current -> Math.max(current, floor) + 1L);
                    redis.put(keys.get(0), Long.toString(next));
                    redis.put(keys.get(1), "{\"intent\":\"" + intent + "\",\"version\":" + next + "}");
                    return next;
                });
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(any(String.class), any(String.class));
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }

    @Test
    void heartbeatDetectorFieldsSurviveRealGroupCopy() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptHeartbeat("DRONE-COPY", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-COPY")
                .setConnectionState("CONNECTED")
                .setSessionState("RUNNING")
                .setDetectorIntent("ARMED")
                .setDetectorState("ARMED")
                .setDetectorHealth("HEALTHY")
                .setDetectorReason("ready")
                .setDetectorIntentVersion(7L));

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-COPY");

        assertEquals("ARMED", group.getDetectorIntent());
        assertEquals("ARMED", group.getDetectorState());
        assertEquals("HEALTHY", group.getDetectorHealth());
        assertEquals("ready", group.getDetectorReason());
        assertEquals(7L, group.getDetectorIntentVersion());
        assertNotNull(group.getDetectorObservedAt());
    }

    @Test
    void issueCommand_enqueuesPendingCommandForDrone() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        DualStreamCommandDTO issued = service.issueCommand("DRONE-001", "start");
        DualStreamCommandDTO pending = service.pollCommand("DRONE-001");

        assertNotNull(issued);
        assertEquals("start", issued.getAction());
        assertNotNull(issued.getCommandId());
        assertEquals("pending", issued.getStatus());
        assertNotNull(pending);
        assertEquals(issued.getCommandId(), pending.getCommandId());
        assertEquals("pending", pending.getStatus());
    }

    @Test
    void issueCommand_preservesParamsThroughQueue() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        DualStreamCommandDTO issued = service.issueCommand(
                "DRONE-001",
                "visible-detector-arm",
                Map.of("lat", 34.6596, "lng", 109.3416, "taskId", "fire-DRONE-001"));
        DualStreamCommandDTO pending = service.pollCommand("DRONE-001");

        assertNotNull(issued.getParams());
        assertNotNull(pending.getParams());
        assertEquals(34.6596, ((Number) pending.getParams().get("lat")).doubleValue(), 1e-6);
        assertEquals(109.3416, ((Number) pending.getParams().get("lng")).doubleValue(), 1e-6);
        assertEquals("fire-DRONE-001", pending.getParams().get("taskId"));
    }

    @Test
    void issueCommand_marksThermalControlCommandsUrgent() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        assertTrue(service.issueCommand("DRONE-THERMAL-MONITOR", "thermal-monitor-on").getUrgent());
        assertTrue(service.issueCommand("DRONE-FOCUS-THERMAL", "focus-thermal").getUrgent());
        assertTrue(service.issueCommand("DRONE-FOCUS-VISIBLE", "focus-visible").getUrgent());
        assertTrue(service.issueCommand("DRONE-MEASURE", "measure-thermal-region").getUrgent());
        assertTrue(service.issueCommand("DRONE-ARM", "visible-detector-arm").getUrgent());
        assertTrue(service.issueCommand("DRONE-DISARM", "visible-detector-disarm").getUrgent());
        assertNotEquals(Boolean.TRUE, service.issueCommand("DRONE-START", "start").getUrgent());
    }

    @Test
    void startVisibleLaserLocalization_dispatchesOneUrgentHold() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        assertNull(service.issueCommand("DRONE-001", "visible-fire-hold"));
    }

    @Test
    void stableHoldAck_waitsForPostHoldRoiThenDispatchesLaserMeasure() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        assertNull(service.issueCommand("DRONE-001", "visible-fire-laser-measure"));
    }

    @Test
    void stableHoldAck_usesFreshRoiAfterNormalHoverFrameShift() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        assertNull(service.issueCommand("DRONE-SHIFTED", "visible-fire-laser-measure"));
    }

    @Test
    void successfulLaserAck_updatesOriginalEvent() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        assertNull(service.issueCommand("DRONE-001", "visible-fire-hold"));
    }

    @Test
    void acknowledgeCommand_updatesCommandStatusWithoutErasingGroupState() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptHeartbeat("DRONE-001", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-001")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING"));

        DualStreamCommandDTO command = service.issueCommand("DRONE-001", "focus-visible");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("applied")
                .setMessage("visible channel active"));

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");
        DualStreamCommandDTO pending = service.pollCommand("DRONE-001");

        assertNotNull(group);
        assertEquals("RUNNING", group.getSessionState());
        assertEquals("focus-visible", group.getLastCommandAction());
        assertEquals("applied", group.getLastCommandStatus());
        assertNull(pending);
    }

    @Test
    void mergeAgentState_buildsLiveGroupSnapshot() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "172.20.10.7");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptHeartbeat("DRONE-001", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-001")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING"));
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setLiveStatus("ONLINE")
                .setCurrentMode("DUAL")
                .setVisibleState("running")
                .setThermalState("degraded")
                .setStatusReason("thermal-stream-source-unavailable")
                .setPlaybackStatus("awaiting-media-url"));
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setMessage("no-web-playback-url-yet"));
        service.acceptCapability("DRONE-001", new DualStreamAgentCapabilityDTO()
                .setDroneSn("DRONE-001")
                .setVisibleSupported(true)
                .setThermalSupported(true));

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");

        assertEquals("DRONE-001", group.getDroneSn());
        assertEquals("STREAMING", group.getConnectionState());
        assertEquals("RUNNING", group.getSessionState());
        assertEquals("ONLINE", group.getLiveStatus());
        assertEquals("DUAL", group.getCurrentMode());
        assertEquals("running", group.getVisibleState());
        assertEquals("degraded", group.getThermalState());
        assertEquals("thermal-stream-source-unavailable", group.getStatusReason());
        assertEquals("visible-playback-ready", group.getPlaybackStatus());
        assertEquals("no-web-playback-url-yet", group.getStatusMessage());
        assertEquals("webrtc://172.20.10.7:58925/live/DRONE-001-0", group.getVisiblePlayUrl());
        assertNull(group.getThermalPlayUrl());
        assertTrue(group.getVisibleSupported());
        assertTrue(group.getThermalSupported());
    }

    @Test
    void getGroup_derivesVisiblePlaybackUrlFromLegacySnapshotWithoutMutatingThermalChannel() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "172.20.10.7");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("degraded")
                .setPlaybackStatus("awaiting-media-url"));

        DualStreamLiveGroupDTO group = service.getGroup("RC_PLUS_LOCAL");

        assertNotNull(group);
        assertEquals("webrtc://172.20.10.7:58925/live/RC_PLUS_LOCAL-0", group.getVisiblePlayUrl());
        assertNull(group.getThermalPlayUrl());
        assertEquals("visible-playback-ready", group.getPlaybackStatus());
    }

    @Test
    void getGroup_reusesVisiblePlaybackUrlForThermalWhenAgentReportsSharedPreview() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "172.20.10.7");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("running")
                .setPlaybackStatus("shared-side-by-side-preview")
                .setStatusReason("single-liveview-source-shared-side-by-side-preview"));

        DualStreamLiveGroupDTO group = service.getGroup("RC_PLUS_LOCAL");

        assertNotNull(group);
        assertEquals("webrtc://172.20.10.7:58925/live/RC_PLUS_LOCAL-0", group.getVisiblePlayUrl());
        assertEquals(group.getVisiblePlayUrl(), group.getThermalPlayUrl());
        assertEquals("shared-side-by-side-preview", group.getPlaybackStatus());
    }

    @Test
    void acceptStatusStoresThermalCenterTemperatureInGroup() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setThermalState("running")
                .setThermalCenterTemperatureC(91.7));

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");

        assertEquals(91.7, group.getThermalCenterTemperatureC(), 1e-6);
    }

    @Test
    void hudCenterTemperatureAloneDoesNotConfirmThermalEvent() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        // 探针 HUD 中心温度是全画面最热点的瞬时值（日晒金属可到 80°C+），
        // 串行链只认对检出框的实测温度：无 ROI 无实测的高分帧直接拒绝。
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setThermalState("running")
                .setThermalCenterTemperatureC(86.3));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.82)
                .setFusionScore(0.82)
                .setThermalImageUrl("http://snapshots/thermal-center.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_REJECTED", events.get(0).getReviewStatus());
        assertNull(service.pollCommand("DRONE-001"));
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_preservesThermalMeasureRoiForFollowUpRegionTemperature() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalMeasureRoi(roi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals(roi, events.get(0).getThermalMeasureRoi());
    }

    @Test
    void acceptEvent_enqueuesThermalRegionMeasurementBeforeCreatingConfirmedFireEvent() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        assertEquals("THERMAL_MEASURING", events.get(0).getReviewStatus());
        assertNotNull(command);
        assertEquals("measure-thermal-region", command.getAction());
        assertEquals("task-001", command.getTaskId());
        assertEquals(1779163200000L, command.getSourceTs());
        assertEquals(roi, command.getThermalMeasureRoi());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void thermalMeasurementAck_createsHotspotAlertForVeryHighTemperatureWithoutVisibleConfirmation() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/thermal.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO measureCommand = service.pollCommand("DRONE-001");

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(measureCommand.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(153.0)
                .setThermalMeasureRoi(roi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        assertNotNull(visibleCommand);
        assertEquals("focus-visible", visibleCommand.getAction());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertEquals("HIGH", fireEventCaptor.getValue().getFireLevel());
        assertEquals(153.0, fireEventCaptor.getValue().getThermalTemperature());
        assertEquals("http://snapshots/thermal.jpg", fireEventCaptor.getValue().getThermalImageUrl());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
    }

    @Test
    void confirmedThermalEventMapsLaserGeoToFireEventParam() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(1.0)
                .setFusionScore(1.0)
                // 高温直通路径（>=80°C 直接确认），几何映射无需等可见光二次确认
                .setThermalTemperature(85.0)
                .setThermalImageUrl("http://snapshots/thermal.jpg")
                .setFireLat(34.658650)
                .setFireLng(109.340600)
                .setFireAlt(386.0)
                .setGeoMethod("LASER_RANGEFINDER")
                .setGeoErrorRadiusM(5.0));

        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        FireEventCreateParam param = fireEventCaptor.getValue();
        assertEquals(34.658650, param.getLat(), 1e-6);
        assertEquals(109.340600, param.getLng(), 1e-6);
        assertEquals(386.0, param.getAlt(), 1e-6);
        assertEquals("LASER_RANGEFINDER", param.getGeoMethod());
        assertEquals(5.0, param.getGeoErrorRadiusM(), 1e-6);
    }

    @Test
    void thermalMeasurementAck_confirmsWarmHotspotAndSwitchesVisibleForEvidence() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/thermal.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO measureCommand = service.pollCommand("DRONE-001");

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(measureCommand.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(62.0)
                .setThermalMeasureRoi(roi));

        // 串行链：实测温度达确认线（57°C）即确认建事件，切可见光只为补证据照
        List<DualStreamEventDTO> events = service.listEvents("task-001");
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        assertNotNull(visibleCommand);
        assertEquals("focus-visible", visibleCommand.getAction());
        ArgumentCaptor<FireEventCreateParam> captor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(captor.capture());
        assertEquals("MEDIUM", captor.getValue().getFireLevel());
        assertEquals(62.0, captor.getValue().getThermalTemperature(), 1e-6);
    }

    @Test
    void visibleEvent_afterWarmConfirmationAttachesEvidence() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/thermal.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO measureCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(measureCommand.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(62.0)
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(visibleCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("MEDIUM")
                .setVisibleScore(0.71)
                .setFusionScore(0.71)
                .setVisibleImageUrl("http://snapshots/visible.jpg"));

        // 串行链：实测 62°C 已确认建事件；可见光帧只作证据挂接，随后切回红外
        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        assertEquals("VISIBLE_CONFIRMED", events.get(1).getReviewStatus());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertEquals("http://snapshots/thermal.jpg", fireEventCaptor.getValue().getThermalImageUrl());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals(62.0, fireEventCaptor.getValue().getThermalTemperature());
        verify(fireEventService).attachVisibleImage(
                eq("task-001-1779163200000"),
                eq("task-001-1779163205000"),
                eq("http://snapshots/visible.jpg"),
                eq("2026-05-19T04:00:05Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/thermal.jpg"));
    }

    @Test
    void thermalConfirmedFireDoesNotUseCachedVisibleSnapshot() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("visible")
                .setVisibleState("running")
                .setThermalState("idle"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/visible-old.jpg"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/visible-new.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163210000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/thermal.jpg"));

        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals("http://snapshots/thermal.jpg", fireEventCaptor.getValue().getThermalImageUrl());
    }

    @Test
    void thermalConfirmedFireWithoutThermalImageIsRejectedBeforeCreatingHistory() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163210000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_IMAGE_MISSING", events.get(0).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void visibleEventAfterHighTemperatureThermalConfirmationAttachesActualVisibleSnapshot() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("HIGH")
                .setVisibleScore(0.72)
                .setFusionScore(0.72)
                .setVisibleImageUrl("http://snapshots/actual-visible-confirmation.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_CONFIRMED", events.get(1).getReviewStatus());
        verify(fireEventService).attachVisibleImage(
                eq("merged-fire-event"),
                eq("task-001-1779163205000"),
                eq("http://snapshots/actual-visible-confirmation.jpg"),
                eq("2026-05-19T04:00:05Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/thermal.jpg"));
    }

    @Test
    void visibleEventAfterHighTemperatureThermalConfirmationAcceptsLowScoreAndPassesThermalImage() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.11)
                .setFusionScore(0.11)
                .setVisibleImageUrl("http://snapshots/low-score-visible.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_CONFIRMED", events.get(1).getReviewStatus());
        verify(fireEventService).attachVisibleImage(
                eq("merged-fire-event"),
                eq("task-001-1779163205000"),
                eq("http://snapshots/low-score-visible.jpg"),
                eq("2026-05-19T04:00:05Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/high-thermal.jpg"));
    }

    @Test
    void visibleEventAfterHighTemperatureThermalConfirmationRecordsRejectedVisibleEvidence() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        when(fireEventService.recordVisibleConfirmationStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.05)
                .setFusionScore(0.05)
                .setVisibleImageUrl("http://snapshots/rejected-visible.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService, never()).attachVisibleImage(any(), any(), any(), any(), any(), any());
        verify(fireEventService).recordVisibleConfirmationStatus(
                eq("merged-fire-event"),
                eq("task-001-1779163205000"),
                eq("VISIBLE_REJECTED"),
                eq("http://snapshots/rejected-visible.jpg"),
                eq("2026-05-19T04:00:05Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/high-thermal.jpg"));
    }

    @Test
    void visibleFailureStatusAfterThermalConfirmationIsPersistedForHistory() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        when(fireEventService.recordVisibleConfirmationStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setReviewStatus("VISIBLE_CAPTURE_FAILED")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_CAPTURE_FAILED", events.get(1).getReviewStatus());
        verify(fireEventService).recordVisibleConfirmationStatus(
                eq("merged-fire-event"),
                eq("task-001-1779163205000"),
                eq("VISIBLE_CAPTURE_FAILED"),
                isNull(),
                eq("2026-05-19T04:00:05Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/high-thermal.jpg"));
    }

    @Test
    void debouncedThermalConfirmationRefreshesVisibleAssociationContext() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/first-thermal.jpg"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163220000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/latest-thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163225000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.11)
                .setFusionScore(0.11)
                .setVisibleImageUrl("http://snapshots/latest-visible.jpg"));

        verify(fireEventService).attachVisibleImage(
                eq("merged-fire-event"),
                eq("task-001-1779163225000"),
                eq("http://snapshots/latest-visible.jpg"),
                eq("2026-05-19T04:00:25Z"),
                eq("task-001-1779163220000"),
                eq("http://snapshots/latest-thermal.jpg"));
    }

    @Test
    void visibleEventDoesNotAttachConfirmationWhenAssociatedThermalImageIsMissing() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/first-thermal.jpg"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163220000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163225000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("HIGH")
                .setVisibleScore(0.72)
                .setFusionScore(0.72)
                .setVisibleImageUrl("http://snapshots/latest-visible.jpg"));

        verify(fireEventService, never()).attachVisibleImage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void visibleEvent_zeroScoreEvidenceAfterWarmConfirmationKeepsFireEvent() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.recordVisibleConfirmationStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/thermal.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO measureCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(measureCommand.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(62.0)
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(visibleCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/visible.jpg"));

        // 证据照零检出：事件不撤销，照片与 VISIBLE_REJECTED 标注记入履历——只标注不拦截
        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        assertEquals("VISIBLE_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService).create(any(FireEventCreateParam.class));
        verify(fireEventService).recordVisibleConfirmationStatus(
                eq("task-001-1779163200000"),
                eq("task-001-1779163205000"),
                eq("VISIBLE_REJECTED"),
                eq("http://snapshots/visible.jpg"),
                eq("2026-05-19T04:00:05Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/thermal.jpg"));
    }

    @Test
    void thermalYoloHitWithoutMeasurableRoiIsRejectedAndNeverSwitchesVisible() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        // 高分红外命中但无 ROI 无实测温度：串行链无从测温，直接拒绝——
        // 不建事件、不下发任何镜头切换（修复盛夏日晒场景反复切可见光）。
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.82)
                .setFusionScore(0.82)
                .setThermalImageUrl("http://snapshots/thermal-high.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_REJECTED", events.get(0).getReviewStatus());
        assertNull(service.pollCommand("DRONE-001"));
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void visibleEvent_doesNotAutoRequestThermalFocus() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/visible.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
        // 纯可见光模式：可见光事件不再自动下发 focus-thermal
        assertNull(command);
    }

    @Test
    void acceptEvent_skipsAutoThermalFocusWhenFireDetectionInactive() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "fireDetectionActivityTracker", new FireDetectionActivityTracker());

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/visible.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
        assertNull(service.pollCommand("DRONE-001"));
    }

    @Test
    void acceptEvent_doesNotAutoIssueThermalFocusEvenWhenFireDetectionActive() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireDetectionActivityTracker tracker = new FireDetectionActivityTracker();
        tracker.markActive("DRONE-001");
        ReflectionTestUtils.setField(service, "fireDetectionActivityTracker", tracker);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/visible.jpg"));

        // 纯可见光模式：即使监测激活，可见光事件也不再自动切红外
        assertNull(service.pollCommand("DRONE-001"));
    }

    @Test
    void acceptEvent_skipsThermalRegionMeasurementWhenFireDetectionInactive() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "fireDetectionActivityTracker", new FireDetectionActivityTracker());
        Map<String, Double> roi = Map.of(
                "x", 0.32,
                "y", 0.44,
                "width", 0.26,
                "height", 0.07);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.007)
                .setFusionScore(0.007)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertNotEquals("THERMAL_MEASURING", events.get(0).getReviewStatus());
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        assertTrue(command == null || !"measure-thermal-region".equals(command.getAction()));
    }

    @Test
    void acceptEvent_enqueuesThermalRegionMeasurementForWeakHotspotWithRoi() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        Map<String, Double> roi = Map.of(
                "x", 0.32,
                "y", 0.44,
                "width", 0.26,
                "height", 0.07);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.007)
                .setFusionScore(0.007)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        assertEquals("THERMAL_MEASURING", events.get(0).getReviewStatus());
        assertNotNull(command);
        assertEquals("measure-thermal-region", command.getAction());
        assertEquals(roi, command.getThermalMeasureRoi());
    }

    @Test
    void acceptEvent_rejectsAdditionalThermalFramesWhileMeasurementIsPending() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        Map<String, Double> roi = Map.of(
                "x", 0.32,
                "y", 0.44,
                "width", 0.08,
                "height", 0.08);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.011)
                .setFusionScore(0.011)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163201200L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.011)
                .setFusionScore(0.011)
                .setThermalImageUrl("http://snapshots/task-001-1779163201200-annotated.jpg")
                .setThermalMeasureRoi(roi));

        @SuppressWarnings("unchecked")
        Map<String, Deque<DualStreamCommandDTO>> queues =
                (Map<String, Deque<DualStreamCommandDTO>>) ReflectionTestUtils.getField(service, "commandQueueByDrone");

        assertNotNull(service.pollCommand("DRONE-001"));
        assertTrue(queues == null || !queues.containsKey("DRONE-001") || queues.get("DRONE-001").isEmpty());
        assertEquals("THERMAL_MEASURING", service.listEvents("task-001").get(0).getReviewStatus());
        assertEquals("THERMAL_REJECTED", service.listEvents("task-001").get(1).getReviewStatus());
    }

    @Test
    void acceptEvent_skipsThermalRegionMeasurementDuringCooldownAfterAck() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "thermalMeasurementCooldownMs", 60_000L);
        Map<String, Double> roi = Map.of(
                "x", 0.32,
                "y", 0.44,
                "width", 0.08,
                "height", 0.08);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.011)
                .setFusionScore(0.011)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                // 不达确认线：只验证测温冷却本身，避免确认路径往队列里排证据照命令
                .setThermalTemperature(30.0)
                .setThermalMeasureRoi(roi));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163201200L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.011)
                .setFusionScore(0.011)
                .setThermalImageUrl("http://snapshots/task-001-1779163201200-annotated.jpg")
                .setThermalMeasureRoi(roi));

        @SuppressWarnings("unchecked")
        Map<String, Deque<DualStreamCommandDTO>> queues =
                (Map<String, Deque<DualStreamCommandDTO>>) ReflectionTestUtils.getField(service, "commandQueueByDrone");

        assertTrue(queues == null || !queues.containsKey("DRONE-001") || queues.get("DRONE-001").isEmpty());
        assertEquals("THERMAL_REJECTED", service.listEvents("task-001").get(1).getReviewStatus());
    }

    @Test
    void acceptEvent_allowsThermalRegionMeasurementAfterDefaultFiveSecondCooldown() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        Map<String, Double> roi = Map.of(
                "x", 0.32,
                "y", 0.44,
                "width", 0.08,
                "height", 0.08);
        @SuppressWarnings("unchecked")
        Map<String, Long> completedAtByDrone =
                (Map<String, Long>) ReflectionTestUtils.getField(service, "lastThermalMeasurementCompletedAtByDrone");
        completedAtByDrone.put("DRONE-001", System.currentTimeMillis() - 5_100L);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(System.currentTimeMillis())
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.011)
                .setFusionScore(0.011)
                .setThermalImageUrl("http://snapshots/task-001-thermal-annotated.jpg")
                .setThermalMeasureRoi(roi));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        assertEquals("THERMAL_MEASURING", service.listEvents("task-001").get(0).getReviewStatus());
        assertNotNull(command);
        assertEquals("measure-thermal-region", command.getAction());
    }

    @Test
    void getGroup_expiresRestoredPendingCommandWhenNoCommandExistsAfterRestart() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setVisibleState("running"));
        DualStreamLiveGroupDTO restored = service.getGroup("DRONE-001")
                .setLastCommandAction("measure-thermal-region")
                .setLastCommandStatus("pending");
        @SuppressWarnings("unchecked")
        Map<String, DualStreamLiveGroupDTO> groups =
                (Map<String, DualStreamLiveGroupDTO>) ReflectionTestUtils.getField(service, "groups");
        groups.put("DRONE-001", restored);

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");

        assertEquals("measure-thermal-region", group.getLastCommandAction());
        assertEquals("expired", group.getLastCommandStatus());
        assertNull(service.pollCommand("DRONE-001"));
    }

    @Test
    void listEvents_marksMeasuringEventTimedOutWhenNoMeasurementCommandSurvivedRestart() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "thermalMeasurementTimeoutMs", 1L);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(System.currentTimeMillis() - 60_000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.011)
                .setFusionScore(0.011)
                .setThermalImageUrl("http://snapshots/task-001-old-annotated.jpg")
                .setThermalMeasureRoi(Map.of("x", 0.4, "y", 0.4, "width", 0.08, "height", 0.08)));
        @SuppressWarnings("unchecked")
        Map<String, DualStreamCommandDTO> commands =
                (Map<String, DualStreamCommandDTO>) ReflectionTestUtils.getField(service, "commandByDrone");
        commands.clear();

        List<DualStreamEventDTO> events = service.listEvents("task-001");

        assertEquals("THERMAL_MEASUREMENT_TIMEOUT", events.get(0).getReviewStatus());
        assertNull(service.pollCommand("DRONE-001"));
    }

    @Test
    void acknowledgeRegionMeasurementBackfillsEventAndConfirmsWarmTemperature() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(57.6)
                .setThermalMeasureRoi(roi));

        // 57.6°C 达确认线（57）：回填温度、直接确认建事件，切可见光补证据照
        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        assertEquals(57.6, events.get(0).getThermalTemperature(), 1e-6);

        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        assertNotNull(visibleCommand);
        assertEquals("focus-visible", visibleCommand.getAction());
        verify(fireEventService).create(any(FireEventCreateParam.class));
    }

    @Test
    void acknowledgeRegionMeasurementPreservesAiHotspotRoiWhenAckReturnsDifferentMeasureRegion() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> aiHotspotRoi = Map.of(
                "x", 0.24,
                "y", 0.74,
                "width", 0.08,
                "height", 0.08);
        Map<String, Double> measuredScanRoi = Map.of(
                "x", 0.35,
                "y", 0.35,
                "width", 0.30,
                "height", 0.30);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(aiHotspotRoi));
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(153.0)
                .setThermalMeasureRoi(measuredScanRoi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals(aiHotspotRoi, events.get(0).getThermalMeasureRoi());
    }

    @Test
    void acknowledgeRegionMeasurementDoesNotQueueFocusVisibleWhenAgentAlreadyRestoredVisible() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("applied")
                .setMessage("thermal-measured-visible-restored")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(57.6)
                .setThermalMeasureRoi(roi));

        DualStreamCommandDTO next = service.pollCommand("DRONE-001");

        assertNull(next);
    }

    @Test
    void acknowledgeCommandClearsTerminalCommandSoAgentPollDoesNotRepeatIt() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        DualStreamCommandDTO command = service.issueCommand("DRONE-001", "measure-thermal-region");
        assertNotNull(service.pollCommand("DRONE-001"));

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("failed")
                .setMessage("未知直播错误"));

        assertNull(service.pollCommand("DRONE-001"));
        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");
        assertEquals("measure-thermal-region", group.getLastCommandAction());
        assertEquals("failed", group.getLastCommandStatus());
    }

    @Test
    void thermalConfirmationRejectsWeakHotspotWhenMeasuredTemperatureIsLow() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setThermalState("running")
                .setThermalCenterTemperatureC(28.2));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.009)
                .setFusionScore(0.009)
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/thermal-measured.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_REJECTED", events.get(0).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acknowledgeRegionMeasurementRejectsThermalHotspotWhenMeasuredTemperatureIsLow() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        Map<String, Double> roi = Map.of(
                "x", 0.68,
                "y", 0.46,
                "width", 0.08,
                "height", 0.08);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("LOW")
                .setThermalScore(0.02)
                .setFusionScore(0.02)
                .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                .setThermalMeasureRoi(roi));
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(command.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(26.0)
                .setThermalMeasureRoi(roi));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_REJECTED", events.get(0).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptStatus_resetsStaleSplitPlaybackUrlsWhenAgentReturnsToVisibleLiveReady() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "172.20.10.7");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("running")
                .setPlaybackStatus("shared-side-by-side-preview")
                .setVisiblePlayUrl("webrtc://172.20.10.7:58925/live/RC_PLUS_LOCAL-0-visible")
                .setThermalPlayUrl("webrtc://172.20.10.7:58925/live/RC_PLUS_LOCAL-0-thermal"));

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("degraded")
                .setPlaybackStatus("visible-live-ready"));

        DualStreamLiveGroupDTO group = service.getGroup("RC_PLUS_LOCAL");

        assertNotNull(group);
        assertEquals("visible-live-ready", group.getPlaybackStatus());
        assertEquals("webrtc://172.20.10.7:58925/live/RC_PLUS_LOCAL-0", group.getVisiblePlayUrl());
        assertNull(group.getThermalPlayUrl());
    }

    @Test
    void getGroup_restoresSnapshotFromRedisWhenMemoryCacheIsEmpty() throws Exception {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DualStreamLiveGroupDTO snapshot = new DualStreamLiveGroupDTO()
                .setDroneSn("DRONE-REDIS")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING")
                .setVisibleSupported(true)
                .setThermalSupported(true);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("dual-stream:group:DRONE-REDIS"))
                .thenReturn(objectMapper.writeValueAsString(snapshot));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        DualStreamLiveGroupDTO restored = service.getGroup("DRONE-REDIS");

        assertNotNull(restored);
        assertEquals("DRONE-REDIS", restored.getDroneSn());
        assertEquals("STREAMING", restored.getConnectionState());
        assertEquals("RUNNING", restored.getSessionState());
        assertTrue(restored.getVisibleSupported());
        assertTrue(restored.getThermalSupported());
    }

    @Test
    void acceptCapability_preservesNullFlagsAsUnknown() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        service.acceptCapability("DRONE-NULL", new DualStreamAgentCapabilityDTO()
                .setDroneSn("DRONE-NULL")
                .setVisibleSupported(null)
                .setThermalSupported(null));

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-NULL");

        assertNotNull(group);
        assertNull(group.getVisibleSupported());
        assertNull(group.getThermalSupported());
    }

    @Test
    void acceptHeartbeat_ignoresConflictingDroneSnBetweenPathAndBody() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        service.acceptHeartbeat("DRONE-A", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-B")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING"));

        assertNull(service.getGroup("DRONE-A"));
        assertNull(service.getGroup("DRONE-B"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptEvent_appendsTaskEventWithoutErasingGroupState() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptHeartbeat("DRONE-001", new DualStreamAgentHeartbeatDTO()
                .setDroneSn("DRONE-001")
                .setConnectionState("STREAMING")
                .setSessionState("RUNNING"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setFusionScore(0.712)
                .setRiskLevel("HIGH"));

        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");
        assertNotNull(group);
        assertEquals("RUNNING", group.getSessionState());

        Object rawEvents = ReflectionTestUtils.getField(service, "taskEvents");
        assertNotNull(rawEvents);
        DualStreamEventDTO stored = ((java.util.List<DualStreamEventDTO>) ((java.util.Map<String, ?>) rawEvents).get("task-001")).get(0);
        assertEquals("HIGH", stored.getRiskLevel());
        assertEquals(0.712, stored.getFusionScore());
    }

    @Test
    void acceptEvent_doesNotIssueThermalFocusWhenVisibleRiskIsSuspected() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running")
                .setThermalState("degraded"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.67)
                .setRiskLevel("MEDIUM"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        // 纯可见光模式：可疑可见光帧不再切红外复核，事件由 ai-service 直接上报
        assertNull(command);
        assertNull(group.getLastCommandAction());
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_infersVisibleChannelFromCurrentModeWhenEventChannelIsMissing() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE_ONLY")
                .setVisibleState("running"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setFusionScore(0.6)
                .setRiskLevel("MEDIUM"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        // 纯可见光模式：不再自动切红外，但通道推断逻辑仍然生效
        assertNull(command);
        assertEquals("visible", events.get(0).getAnalysisChannel());
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
    }

    @Test
    void acceptEvent_doesNotIssueThermalFocusForVisibleLowRisk() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setVisibleScore(0.17)
                .setFusionScore(0.17)
                .setRiskLevel("LOW"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        // 纯可见光模式：LOW 风险可见光帧同样不切红外
        assertNull(command);
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
    }

    @Test
    void acceptEvent_confirmsViaMeasuredTemperatureThenSwitchesVisibleForEvidence() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        Map<String, Double> roi = Map.of(
                "x", 0.25,
                "y", 0.30,
                "width", 0.20,
                "height", 0.15);
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setVisibleScore(0.7)
                .setThermalScore(0.82)
                .setFusionScore(0.82)
                .setRiskLevel("HIGH")
                .setThermalImageUrl("http://snapshots/high-thermal.jpg")
                .setThermalMeasureRoi(roi));

        // 串行链：红外命中先测温，不直接切可见光
        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_MEASURING", events.get(1).getReviewStatus());
        DualStreamCommandDTO measureCommand = service.pollCommand("DRONE-001");
        assertNotNull(measureCommand);
        assertEquals("measure-thermal-region", measureCommand.getAction());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));

        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(measureCommand.getCommandId())
                .setStatus("applied")
                .setTaskId("task-001")
                .setSourceTs(1779163200000L)
                .setThermalTemperature(90.0)
                .setThermalMeasureRoi(roi));

        // 实测 90°C 达线：确认建事件，随后切可见光补证据照
        events = service.listEvents("task-001");
        assertEquals("THERMAL_CONFIRMED", events.get(1).getReviewStatus());
        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("pending", command.getStatus());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        FireEventCreateParam fireEvent = fireEventCaptor.getValue();
        assertEquals("task-001-1779163200000", fireEvent.getEventId());
        assertEquals("M4T", fireEvent.getSource());
        assertEquals("DRONE-001", fireEvent.getDeviceSn());
        assertEquals("HIGH", fireEvent.getFireLevel());
        assertEquals(90.0, fireEvent.getThermalTemperature(), 1e-6);
        assertEquals("http://snapshots/high-thermal.jpg", fireEvent.getThermalImageUrl());
        assertNull(fireEvent.getVisibleImageUrl());
    }

    @Test
    void acceptEvent_issuesVisibleFocusEvenInSharedSideBySidePreview() {
        // 旧行为在 shared-side-by-side-preview 下压制 focus-visible 并直接确认建事件；
        // 该压制已移除：实测温度确认后，共享预览模式下同样切可见光补证据照。
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setCurrentMode("THERMAL")
                .setVisibleState("running")
                .setThermalState("running")
                .setPlaybackStatus("shared-side-by-side-preview")
                .setStatusReason("single-liveview-source-shared-side-by-side-preview"));

        service.acceptEvent("fire-RC_PLUS_LOCAL", new DualStreamEventDTO()
                .setTaskId("fire-RC_PLUS_LOCAL")
                .setDroneSn("RC_PLUS_LOCAL")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.82)
                .setFusionScore(0.82)
                .setRiskLevel("HIGH")
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/msdk-local-thermal.jpg"));

        DualStreamCommandDTO command = service.pollCommand("RC_PLUS_LOCAL");
        List<DualStreamEventDTO> events = service.listEvents("fire-RC_PLUS_LOCAL");

        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        verify(fireEventService).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_rejectsLowRiskThermalScoreWithoutTemperature() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.17)
                .setVisibleScore(0.17)
                .setRiskLevel("LOW")
                .setVisibleImageUrl("http://snapshots/visible-suspected.jpg"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setVisibleScore(0.17)
                .setThermalScore(0.0)
                .setFusionScore(0.17)
                .setRiskLevel("LOW")
                .setVisibleImageUrl("http://snapshots/thermal-confirmation.jpg")
                .setThermalImageUrl("http://snapshots/thermal-confirmation.jpg"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        // 新流程：LOW 风险且无测温的红外帧不再确认建事件，也不触发可见光二次确认
        assertNull(command);
        assertEquals("thermal", events.get(1).getAnalysisChannel());
        assertEquals("THERMAL_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_doesNotOverrideExplicitVisibleChannelAfterThermalFocusApplied() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setVisibleScore(0.17)
                .setFusionScore(0.17)
                .setRiskLevel("LOW"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setSourceTs(1779163200000L)
                .setVisibleScore(0.61)
                .setFusionScore(0.61)
                .setRiskLevel("MEDIUM"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("visible", events.get(1).getAnalysisChannel());
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(1).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_debouncesConfirmedThermalFireEventsPerTask() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        // 两次实测确认相隔 2s：createConfirmedFireEvent 自身的 60s 去抖只放行第一次
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.6)
                .setFusionScore(0.6)
                .setRiskLevel("MEDIUM")
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/thermal-1.jpg"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163202000L)
                .setThermalScore(0.65)
                .setFusionScore(0.65)
                .setRiskLevel("MEDIUM")
                .setThermalTemperature(91.0)
                .setThermalImageUrl("http://snapshots/thermal-2.jpg"));

        verify(fireEventService, org.mockito.Mockito.times(1)).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_doesNotPromoteVisibleImageUrlIntoThermalImageUrl() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.4)
                .setVisibleScore(0.4)
                .setRiskLevel("MEDIUM")
                .setVisibleImageUrl("http://snapshots/real-visible-trigger.jpg"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        // 红外触发帧上混着可见光 URL：实测确认建事件时不得把它挪作视觉证据
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setFusionScore(0.6)
                .setVisibleScore(0.6)
                .setRiskLevel("MEDIUM")
                .setThermalTemperature(90.0)
                .setVisibleImageUrl("http://snapshots/visible-frame-during-thermal-step.jpg")
                .setThermalImageUrl("http://snapshots/thermal-frame-during-thermal-step.jpg"));

        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals("http://snapshots/thermal-frame-during-thermal-step.jpg", fireEventCaptor.getValue().getThermalImageUrl());
    }

    @Test
    void acceptEvent_doesNotLabelTriggerImageAsVisibleWhenCameraModeIsUnknown() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.4)
                .setVisibleScore(0.4)
                .setRiskLevel("MEDIUM")
                .setVisibleImageUrl("http://snapshots/unknown-mode-frame.jpg"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setFusionScore(0.6)
                .setVisibleScore(0.6)
                .setRiskLevel("MEDIUM")
                .setThermalTemperature(90.0)
                .setVisibleImageUrl("http://snapshots/thermal-confirmation.jpg")
                .setThermalImageUrl("http://snapshots/thermal-confirmation.jpg"));

        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals("http://snapshots/thermal-confirmation.jpg", fireEventCaptor.getValue().getThermalImageUrl());
    }

    @Test
    void acceptEvent_staysOnThermalAfterThermalNoiseRejected() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setFusionScore(0.005)
                .setThermalScore(0.005)
                .setRiskLevel("LOW"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        // 新流程红外是常驻监测通道：噪声帧直接否决，不再切回可见光
        assertNull(command);
        assertEquals("THERMAL_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_rejectsThermalReferenceFrameAtLowScore() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH")
                .setVisibleImageUrl("http://snapshots/visible.jpg"));
        // 纯可见光模式下可见光事件不再自动下发 focus-thermal，显式下发以进入红外分支
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setFusionScore(0.011)
                .setThermalScore(0.011)
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/thermal-reference.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");

        // 新流程：无测温的 LOW 分红外帧不再确认建事件
        assertEquals("THERMAL_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_doesNotDuplicateVisibleFocusWhileConfirmPending() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        // 命令超时复用 thermalMeasurementTimeoutMs，为 0 时 pending 命令 1ms 即过期造成误重发
        ReflectionTestUtils.setField(service, "thermalMeasurementTimeoutMs", 20000L);
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.6)
                .setFusionScore(0.6)
                .setRiskLevel("MEDIUM")
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/thermal-trigger.jpg"));
        // 焦点切换尚未执行前，后续实测确认帧走 10s 节流，不重复排队 focus-visible
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163201000L)
                .setThermalScore(0.65)
                .setFusionScore(0.65)
                .setRiskLevel("MEDIUM")
                .setThermalTemperature(91.0)
                .setThermalImageUrl("http://snapshots/thermal-trigger-2.jpg"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        // 未 ack 前重复 poll 返回同一条命令，而不是新排队的重复命令
        DualStreamCommandDTO repolled = service.pollCommand("DRONE-001");
        assertEquals(command.getCommandId(), repolled.getCommandId());
    }

    @Test
    void acceptEvent_doesNotReissueThermalFocusAfterAppliedCommand() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running")
                .setThermalState("degraded"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH"));
        service.issueCommand("DRONE-001", "focus-thermal");
        DualStreamCommandDTO firstCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(firstCommand.getCommandId())
                .setStatus("applied"));
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setCurrentMode("VISIBLE")
                .setVisibleState("running")
                .setThermalState("degraded"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.8)
                .setRiskLevel("HIGH"));

        // 纯可见光模式：后续可见光事件不再自动重发 focus-thermal
        assertNotNull(firstCommand);
        assertNull(service.pollCommand("DRONE-001"));
    }

    @Test
    void listEvents_restoresTaskEventsFromRedisWhenMemoryCacheIsEmpty() throws Exception {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper();
        List<DualStreamEventDTO> snapshot = List.of(new DualStreamEventDTO()
                .setTaskId("task-redis")
                .setDroneSn("DRONE-REDIS")
                .setFusionScore(0.931)
                .setRiskLevel("HIGH"));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("dual-stream:task-events:task-redis"))
                .thenReturn(objectMapper.writeValueAsString(snapshot));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        List<DualStreamEventDTO> restored = service.listEvents("task-redis");

        assertEquals(1, restored.size());
        assertEquals("task-redis", restored.get(0).getTaskId());
        assertEquals("HIGH", restored.get(0).getRiskLevel());
    }

    @Test
    void confirmationPhotoWithThermalSourceEventIdAttachesEvidenceEvenWhenLate() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        // 没有任何触发/确认上下文（模拟 backend 重启或超窗后关联丢失），只凭精确外键也要能挂接
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163600000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/late-visible-confirmation.jpg")
                .setThermalSourceEventId("task-001-1779163200000")
                .setThermalImageUrl("http://snapshots/source-thermal.jpg"));

        verify(fireEventService).attachVisibleImage(
                eq("task-001-1779163200000"),
                eq("task-001-1779163600000"),
                eq("http://snapshots/late-visible-confirmation.jpg"),
                eq("2026-05-19T04:06:40Z"),
                eq("task-001-1779163200000"),
                eq("http://snapshots/source-thermal.jpg"));
    }

    @Test
    void confirmationPhotoFallsBackToMergedFireEventIdWhenPreciseIdMissing() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        // 精确 event_id 对应行不存在（被空间合并）→ 第一次挂接失败，回退到合并后的 fire event id
        when(fireEventService.attachVisibleImage(eq("task-001-1779163200000"), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(fireEventService.attachVisibleImage(eq("merged-fire-event"), any(), any(), any(), any(), any()))
                .thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163600000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/merged-visible-confirmation.jpg")
                .setThermalSourceEventId("task-001-1779163200000")
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        verify(fireEventService).attachVisibleImage(
                eq("task-001-1779163200000"), any(), any(), any(), any(), any());
        verify(fireEventService).attachVisibleImage(
                eq("merged-fire-event"), any(), any(), any(), any(), any());
    }

    @Test
    void confirmationPhotoAttachesEvidenceEvenWhenTriggerReviewRejectsIt() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        // 夜间确认照 score=0：证据挂接不依赖任何复核结论——照片是证据，结论是结论
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("MEDIUM")
                .setThermalScore(0.6)
                .setFusionScore(0.6)
                .setThermalImageUrl("http://snapshots/trigger-thermal.jpg"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/night-visible-confirmation.jpg")
                .setThermalSourceEventId("fire-DRONE-001-1779163199000")
                .setThermalImageUrl("http://snapshots/agent-thermal.jpg"));

        verify(fireEventService).attachVisibleImage(
                eq("fire-DRONE-001-1779163199000"),
                eq("task-001-1779163205000"),
                eq("http://snapshots/night-visible-confirmation.jpg"),
                eq("2026-05-19T04:00:05Z"),
                eq("fire-DRONE-001-1779163199000"),
                eq("http://snapshots/agent-thermal.jpg"));
        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(1).getReviewStatus());
    }

    @Test
    void thermalConfirmationGraceSuppressesAutoThermalFocus() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        // 红外测温确认 → agent 将切可见光拍确认照，宽限期开启
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        // 宽限开启：确认切换在队首，随后暂停 agent 自主测温探针
        DualStreamCommandDTO first = service.pollCommand("DRONE-001");
        assertNotNull(first);
        assertEquals("focus-visible", first.getAction());
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(first.getCommandId())
                .setStatus("applied"));
        DualStreamCommandDTO monitorOff = service.pollCommand("DRONE-001");
        assertNotNull(monitorOff);
        assertEquals("thermal-monitor-off", monitorOff.getAction());
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(monitorOff.getCommandId())
                .setStatus("applied"));

        // 宽限期内：识别循环的可见光帧事件不得触发自动 focus-thermal（否则抢在确认照拍摄前切回红外）
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163203000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0));

        assertNull(service.pollCommand("DRONE-001"));
    }

    @Test
    void confirmationPhotoArrivalLiftsGraceAndResumesThermalFocus() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        when(fireEventService.attachVisibleImage(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(fireEventService.recordVisibleConfirmationStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        DualStreamCommandDTO first = service.pollCommand("DRONE-001");
        assertNotNull(first);
        assertEquals("focus-visible", first.getAction());
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(first.getCommandId())
                .setStatus("applied"));
        DualStreamCommandDTO monitorOff = service.pollCommand("DRONE-001");
        assertNotNull(monitorOff);
        assertEquals("thermal-monitor-off", monitorOff.getAction());
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(monitorOff.getCommandId())
                .setStatus("applied"));

        // 确认照到达（带精确外键）→ 宽限解除，复核路径立即恢复"拍完切回红外"
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163205000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0)
                .setVisibleImageUrl("http://snapshots/confirmation.jpg")
                .setThermalSourceEventId("task-001-1779163200000")
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        // 解除次序：先恢复探针（monitor-on），再切回红外
        DualStreamCommandDTO monitorOn = service.pollCommand("DRONE-001");
        assertNotNull(monitorOn);
        assertEquals("thermal-monitor-on", monitorOn.getAction());
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(monitorOn.getCommandId())
                .setStatus("applied"));
        DualStreamCommandDTO second = service.pollCommand("DRONE-001");
        assertNotNull(second);
        assertEquals("focus-thermal", second.getAction());
    }

    @Test
    void zeroGraceStillDoesNotAutoThermalFocus() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        when(fireEventService.create(any(FireEventCreateParam.class)))
                .thenReturn(new FireEventCreateResponse(1L, "merged-fire-event", true, "MISSION-001", "WAITING_REVIEW"));
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        ReflectionTestUtils.setField(service, "visibleConfirmationGraceMs", 0L);

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163200000L)
                .setAnalysisChannel("thermal")
                .setRiskLevel("HIGH")
                .setThermalScore(0.8)
                .setFusionScore(0.8)
                .setThermalTemperature(90.0)
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

        DualStreamCommandDTO first = service.pollCommand("DRONE-001");
        assertNotNull(first);
        assertEquals("focus-visible", first.getAction());
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(first.getCommandId())
                .setStatus("applied"));

        // 纯可见光模式：即使宽限置 0，可见光事件也不再把镜头抢回红外
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setTaskId("task-001")
                .setDroneSn("DRONE-001")
                .setSourceTs(1779163203000L)
                .setAnalysisChannel("visible")
                .setRiskLevel("LOW")
                .setVisibleScore(0.0)
                .setFusionScore(0.0));

        assertNull(service.pollCommand("DRONE-001"));
    }
}
