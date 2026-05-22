package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.impl.DualStreamServiceImpl;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DualStreamServiceImplTest {

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
        assertNotNull(pending);
        assertEquals("applied", pending.getStatus());
        assertEquals("visible channel active", pending.getMessage());
    }

    @Test
    void mergeAgentState_buildsLiveGroupSnapshot() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "192.168.50.254");
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
        assertEquals("webrtc://192.168.50.254:58925/live/DRONE-001-0", group.getVisiblePlayUrl());
        assertNull(group.getThermalPlayUrl());
        assertTrue(group.getVisibleSupported());
        assertTrue(group.getThermalSupported());
    }

    @Test
    void getGroup_derivesVisiblePlaybackUrlFromLegacySnapshotWithoutMutatingThermalChannel() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "192.168.50.254");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("degraded")
                .setPlaybackStatus("awaiting-media-url"));

        DualStreamLiveGroupDTO group = service.getGroup("RC_PLUS_LOCAL");

        assertNotNull(group);
        assertEquals("webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0", group.getVisiblePlayUrl());
        assertNull(group.getThermalPlayUrl());
        assertEquals("visible-playback-ready", group.getPlaybackStatus());
    }

    @Test
    void getGroup_reusesVisiblePlaybackUrlForThermalWhenAgentReportsSharedPreview() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "192.168.50.254");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("running")
                .setPlaybackStatus("shared-side-by-side-preview")
                .setStatusReason("single-liveview-source-shared-side-by-side-preview"));

        DualStreamLiveGroupDTO group = service.getGroup("RC_PLUS_LOCAL");

        assertNotNull(group);
        assertEquals("webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0", group.getVisiblePlayUrl());
        assertEquals(group.getVisiblePlayUrl(), group.getThermalPlayUrl());
        assertEquals("shared-side-by-side-preview", group.getPlaybackStatus());
    }

    @Test
    void acceptStatus_resetsStaleSplitPlaybackUrlsWhenAgentReturnsToVisibleLiveReady() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "192.168.50.254");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("running")
                .setPlaybackStatus("shared-side-by-side-preview")
                .setVisiblePlayUrl("webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0-visible")
                .setThermalPlayUrl("webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0-thermal"));

        service.acceptStatus("RC_PLUS_LOCAL", new DualStreamAgentStatusDTO()
                .setDroneSn("RC_PLUS_LOCAL")
                .setVisibleState("running")
                .setThermalState("degraded")
                .setPlaybackStatus("visible-live-ready"));

        DualStreamLiveGroupDTO group = service.getGroup("RC_PLUS_LOCAL");

        assertNotNull(group);
        assertEquals("visible-live-ready", group.getPlaybackStatus());
        assertEquals("webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0", group.getVisiblePlayUrl());
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
    void acceptEvent_issuesThermalFocusWhenVisibleRiskIsSuspected() {
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

        assertNotNull(command);
        assertEquals("focus-thermal", command.getAction());
        assertEquals("pending", command.getStatus());
        assertEquals("focus-thermal", group.getLastCommandAction());
        assertEquals("pending", group.getLastCommandStatus());
        assertEquals("VISIBLE_SUSPECTED", events.get(0).getReviewStatus());
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

        assertNotNull(command);
        assertEquals("focus-thermal", command.getAction());
        assertEquals("visible", events.get(0).getAnalysisChannel());
        assertEquals("VISIBLE_SUSPECTED", events.get(0).getReviewStatus());
    }

    @Test
    void acceptEvent_issuesVisibleFocusAndMarksConfirmedAfterThermalHighRisk() {
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
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setVisibleScore(0.7)
                .setThermalScore(0.82)
                .setFusionScore(0.82)
                .setRiskLevel("HIGH"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("pending", command.getStatus());
        assertEquals("THERMAL_CONFIRMED", events.get(1).getReviewStatus());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        FireEventCreateParam fireEvent = fireEventCaptor.getValue();
        assertEquals("task-001-1779163200000", fireEvent.getEventId());
        assertEquals("M4T", fireEvent.getSource());
        assertEquals("DRONE-001", fireEvent.getDeviceSn());
        assertEquals("HIGH", fireEvent.getFireLevel());
        assertEquals(0, fireEvent.getConfidence().compareTo(java.math.BigDecimal.valueOf(0.82)));
    }

    @Test
    void acceptEvent_treatsVisibleLabeledEventAsThermalReviewAfterThermalFocusApplied() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH"));
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.82)
                .setRiskLevel("HIGH"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("thermal", events.get(1).getAnalysisChannel());
        assertEquals("THERMAL_CONFIRMED", events.get(1).getReviewStatus());
    }

    @Test
    void acceptEvent_issuesVisibleFocusAndMarksRejectedAfterThermalLowRisk() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH"));
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setFusionScore(0.18)
                .setRiskLevel("LOW"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("THERMAL_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_doesNotIssueThermalFocusWhileVisibleFocusIsPending() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setRiskLevel("HIGH"));
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId(thermalCommand.getCommandId())
                .setStatus("applied"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setFusionScore(0.1)
                .setRiskLevel("LOW"));
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.9)
                .setRiskLevel("HIGH"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        assertEquals("focus-visible", command.getAction());
        assertEquals(visibleCommand.getCommandId(), command.getCommandId());
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
}
