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
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    void issueCommand_marksThermalControlCommandsUrgent() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();

        assertTrue(service.issueCommand("DRONE-THERMAL-MONITOR", "thermal-monitor-on").getUrgent());
        assertTrue(service.issueCommand("DRONE-FOCUS-THERMAL", "focus-thermal").getUrgent());
        assertTrue(service.issueCommand("DRONE-FOCUS-VISIBLE", "focus-visible").getUrgent());
        assertTrue(service.issueCommand("DRONE-MEASURE", "measure-thermal-region").getUrgent());
        assertNotEquals(Boolean.TRUE, service.issueCommand("DRONE-START", "start").getUrgent());
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
    void confirmedThermalEventUsesLatestAgentCenterTemperatureWhenEventHasNoTemperature() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);

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

        ArgumentCaptor<FireEventCreateParam> captor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(captor.capture());
        assertEquals(86.3, captor.getValue().getThermalTemperature(), 1e-6);
        assertEquals("C", captor.getValue().getTemperatureUnit());
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
    void thermalMeasurementAck_requestsVisibleConfirmationForNonHighThermalHotspot() {
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

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        assertEquals("THERMAL_NEEDS_VISIBLE_CONFIRM", events.get(0).getReviewStatus());
        assertNotNull(visibleCommand);
        assertEquals("focus-visible", visibleCommand.getAction());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void visibleEvent_confirmsPendingThermalHotspotAndCreatesFireEvent() {
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

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_NEEDS_VISIBLE_CONFIRM", events.get(0).getReviewStatus());
        assertEquals("VISIBLE_CONFIRMED", events.get(1).getReviewStatus());
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        assertNotNull(thermalCommand);
        assertEquals("focus-thermal", thermalCommand.getAction());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertEquals("http://snapshots/thermal.jpg", fireEventCaptor.getValue().getThermalImageUrl());
        assertEquals("http://snapshots/visible.jpg", fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals(62.0, fireEventCaptor.getValue().getThermalTemperature());
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
    void visibleEvent_rejectsPendingThermalHotspotWhenYoloDoesNotConfirm() {
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

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("VISIBLE_REJECTED", events.get(1).getReviewStatus());
        DualStreamCommandDTO thermalCommand = service.pollCommand("DRONE-001");
        assertNotNull(thermalCommand);
        assertEquals("focus-thermal", thermalCommand.getAction());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void visibleEvent_withoutThermalContextRequestsThermalFirstEvenWithoutYoloDetection() {
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
        assertNotNull(command);
        assertEquals("focus-thermal", command.getAction());
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
    void acceptEvent_issuesAutoThermalFocusWhenFireDetectionActive() {
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

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        assertNotNull(command);
        assertEquals("focus-thermal", command.getAction());
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
                .setThermalTemperature(90.0)
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
    void acknowledgeRegionMeasurementBackfillsEventAndRequestsVisibleConfirmationForWarmTemperature() {
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

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_NEEDS_VISIBLE_CONFIRM", events.get(0).getReviewStatus());
        assertEquals(57.6, events.get(0).getThermalTemperature(), 1e-6);

        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");
        assertNotNull(visibleCommand);
        assertEquals("focus-visible", visibleCommand.getAction());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
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
    void acknowledgeRegionMeasurementAddsVersionToThermalImageUrlAfterAnnotationRefresh() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/snapshots/task-001-1779163200000/thermal-annotation", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"url\":\"http://snapshots/task-001-1779163200000-annotated.jpg\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DualStreamServiceImpl service = new DualStreamServiceImpl();
            FireEventService fireEventService = mock(FireEventService.class);
            ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
            ReflectionTestUtils.setField(service, "aiServiceBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
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

            List<DualStreamEventDTO> events = service.listEvents("task-001");
            assertEquals(
                    "http://snapshots/task-001-1779163200000-annotated.jpg?thermal_v=1779163200000",
                    events.get(0).getThermalImageUrl());
            assertTrue(requestBody.get().contains("\"thermal_temperature\":57.6"));
            assertTrue(requestBody.get().contains("\"thermal_measure_roi\""));

            verify(fireEventService, never()).create(any(FireEventCreateParam.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directThermalConfirmationRefreshesAnnotationBeforeCreatingFireEvent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/snapshots/task-001-1779163200000/thermal-annotation", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"url\":\"http://snapshots/task-001-1779163200000-annotated.jpg\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DualStreamServiceImpl service = new DualStreamServiceImpl();
            FireEventService fireEventService = mock(FireEventService.class);
            ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
            ReflectionTestUtils.setField(service, "aiServiceBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            Map<String, Double> roi = Map.of(
                    "x", 0.25,
                    "y", 0.30,
                    "width", 0.20,
                    "height", 0.15);
            List<Map<String, Object>> measurements = List.of(
                    Map.of("temperatureC", 88.8, "roi", roi),
                    Map.of("temperatureC", 57.2, "roi", Map.of(
                            "x", 0.10,
                            "y", 0.70,
                            "width", 0.06,
                            "height", 0.06)));

            service.acceptEvent("task-001", new DualStreamEventDTO()
                    .setTaskId("task-001")
                    .setDroneSn("DRONE-001")
                    .setSourceTs(1779163200000L)
                    .setAnalysisChannel("thermal")
                    .setRiskLevel("HIGH")
                    .setThermalScore(0.80)
                    .setFusionScore(0.80)
                    .setThermalTemperature(88.8)
                    .setThermalImageUrl("http://snapshots/task-001-1779163200000-annotated.jpg")
                    .setThermalMeasureRoi(roi)
                    .setThermalMeasurements(measurements));

            ArgumentCaptor<FireEventCreateParam> paramCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
            verify(fireEventService).create(paramCaptor.capture());
            assertEquals(
                    "http://snapshots/task-001-1779163200000-annotated.jpg?thermal_v=1779163200000",
                    paramCaptor.getValue().getThermalImageUrl());
            assertTrue(requestBody.get().contains("\"thermal_temperature\":88.8"));
            assertTrue(requestBody.get().contains("\"thermal_measure_roi\""));
            assertTrue(requestBody.get().contains("\"thermal_detect_roi\""));
            assertTrue(requestBody.get().contains("\"thermal_measurements\""));
            assertTrue(requestBody.get().contains("57.2"));
        } finally {
            server.stop(0);
        }
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
    void thermalConfirmationUsesMeasuredTemperatureEvenWhenHotspotScoreIsWeak() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setThermalState("running")
                .setThermalCenterTemperatureC(66.0));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.009)
                .setFusionScore(0.009)
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/thermal-measured.jpg"));

        List<DualStreamEventDTO> events = service.listEvents("task-001");
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());

        ArgumentCaptor<FireEventCreateParam> captor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(captor.capture());
        assertEquals("MEDIUM", captor.getValue().getFireLevel());
        assertEquals(66.0, captor.getValue().getThermalTemperature(), 1e-6);
        assertEquals(0, captor.getValue().getConfidence()
                .compareTo(java.math.BigDecimal.valueOf(0.7)));
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
    void thermalConfirmationCombinesWeakHotspotWithWarmMeasuredTemperature() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        FireEventService fireEventService = mock(FireEventService.class);
        ReflectionTestUtils.setField(service, "fireEventService", fireEventService);
        service.acceptStatus("DRONE-001", new DualStreamAgentStatusDTO()
                .setDroneSn("DRONE-001")
                .setThermalState("running")
                .setThermalCenterTemperatureC(48.0));

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.007)
                .setFusionScore(0.007)
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/thermal-warm.jpg"));

        ArgumentCaptor<FireEventCreateParam> captor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(captor.capture());
        assertEquals("LOW", captor.getValue().getFireLevel());
        assertEquals(0, captor.getValue().getConfidence()
                .compareTo(java.math.BigDecimal.valueOf(0.3)));
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

        assertNotNull(command);
        assertEquals("focus-thermal", command.getAction());
        assertEquals("visible", events.get(0).getAnalysisChannel());
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
    }

    @Test
    void acceptEvent_issuesThermalFocusForVisibleLowRiskDuringTesting() {
        DualStreamServiceImpl service = new DualStreamServiceImpl();
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setVisibleScore(0.17)
                .setFusionScore(0.17)
                .setRiskLevel("LOW"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        assertNotNull(command);
        assertEquals("focus-thermal", command.getAction());
        assertEquals("VISIBLE_SKIPPED_THERMAL_FIRST", events.get(0).getReviewStatus());
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
                .setRiskLevel("HIGH")
                .setThermalImageUrl("http://snapshots/high-thermal.jpg"));

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
    void acceptEvent_doesNotIssueVisibleFocusForMsdkLocalThermalSnapshot() {
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
                .setThermalImageUrl("http://snapshots/msdk-local-thermal.jpg"));

        DualStreamCommandDTO command = service.pollCommand("RC_PLUS_LOCAL");
        List<DualStreamEventDTO> events = service.listEvents("fire-RC_PLUS_LOCAL");

        assertNull(command);
        assertEquals("THERMAL_CONFIRMED", events.get(0).getReviewStatus());
        verify(fireEventService).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_confirmsLowRiskSingleStreamScoreAfterThermalFocusApplied() {
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

        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("thermal", events.get(1).getAnalysisChannel());
        assertEquals("THERMAL_CONFIRMED", events.get(1).getReviewStatus());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertEquals("LOW", fireEventCaptor.getValue().getFireLevel());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals("http://snapshots/thermal-confirmation.jpg", fireEventCaptor.getValue().getThermalImageUrl());
        assertEquals(0, fireEventCaptor.getValue().getConfidence()
                .compareTo(java.math.BigDecimal.valueOf(0.17)));
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

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163200000L)
                .setThermalScore(0.04)
                .setFusionScore(0.04)
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/thermal-1.jpg"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163201000L)
                .setThermalScore(0.05)
                .setFusionScore(0.05)
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/thermal-2.jpg"));

        verify(fireEventService, org.mockito.Mockito.times(1)).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_usesThermalFocusTriggerFrameAsVisibleImage() {
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
                .setThermalImageUrl("http://snapshots/thermal-confirmation.jpg"));
        DualStreamCommandDTO visibleCommand = service.pollCommand("DRONE-001");

        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("visible")
                .setFusionScore(0.7)
                .setVisibleScore(0.7)
                .setRiskLevel("HIGH")
                .setVisibleImageUrl("http://snapshots/thermal-window-mislabeled-visible.jpg"));
        service.acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setAnalysisChannel("thermal")
                .setSourceTs(1779163261000L)
                .setFusionScore(0.8)
                .setVisibleScore(0.8)
                .setRiskLevel("HIGH")
                .setThermalImageUrl("http://snapshots/thermal-confirmation-2.jpg"));

        assertEquals("focus-visible", visibleCommand.getAction());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService, org.mockito.Mockito.times(2)).create(fireEventCaptor.capture());
        FireEventCreateParam secondEvent = fireEventCaptor.getAllValues().get(1);
        assertNull(secondEvent.getVisibleImageUrl());
        assertEquals("http://snapshots/thermal-confirmation-2.jpg", secondEvent.getThermalImageUrl());
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
                .setVisibleImageUrl("http://snapshots/thermal-confirmation.jpg")
                .setThermalImageUrl("http://snapshots/thermal-confirmation.jpg"));

        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals("http://snapshots/thermal-confirmation.jpg", fireEventCaptor.getValue().getThermalImageUrl());
    }

    @Test
    void acceptEvent_issuesVisibleFocusAndMarksRejectedAfterThermalNoise() {
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
                .setFusionScore(0.005)
                .setThermalScore(0.005)
                .setRiskLevel("LOW"));

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");
        List<DualStreamEventDTO> events = service.listEvents("task-001");

        assertNotNull(command);
        assertEquals("focus-visible", command.getAction());
        assertEquals("THERMAL_REJECTED", events.get(1).getReviewStatus());
        verify(fireEventService, never()).create(any(FireEventCreateParam.class));
    }

    @Test
    void acceptEvent_confirmsThermalReferenceFrameAtLowScoreAndKeepsThermalImage() {
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

        assertEquals("THERMAL_CONFIRMED", events.get(1).getReviewStatus());
        ArgumentCaptor<FireEventCreateParam> fireEventCaptor = ArgumentCaptor.forClass(FireEventCreateParam.class);
        verify(fireEventService).create(fireEventCaptor.capture());
        assertNull(fireEventCaptor.getValue().getVisibleImageUrl());
        assertEquals("http://snapshots/thermal-reference.jpg", fireEventCaptor.getValue().getThermalImageUrl());
        assertEquals(0, fireEventCaptor.getValue().getConfidence()
                .compareTo(java.math.BigDecimal.valueOf(0.011)));
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
                .setRiskLevel("LOW")
                .setThermalImageUrl("http://snapshots/inferred-thermal.jpg"));
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
    void acceptEvent_reissuesThermalFocusWhenPreviousAppliedCommandNoLongerMatchesRuntimeState() {
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

        DualStreamCommandDTO command = service.pollCommand("DRONE-001");

        assertNotEquals(firstCommand.getCommandId(), command.getCommandId());
        assertEquals("focus-thermal", command.getAction());
        assertEquals("pending", command.getStatus());
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
