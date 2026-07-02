package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryBypassStreamDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceLiveDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineDTO;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeliveryBypassStreamRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.dao.FireMissionLogMapper;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionLogEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.enums.ReleaseExecutionMode;
import com.yx.uavfire.fc100.mission.model.enums.ReleasePolicy;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.operation.command.CommandQueueService;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.payload.service.PayloadReleasePolicyService;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckResult;
import com.yx.uavfire.fc100.safety.service.SafetyCheckService;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.model.param.WaypointGenerateParam;
import com.yx.uavfire.fc100.waypoint.service.WaypointPlannerService;
import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.service.IPlannedWaylineService;
import com.yx.uavfire.wayline.service.IWaylineFileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryControllerApifoxWorkflowTest {

    @Test
    void prepareFireMissionDeliveryTaskGeneratesRouteAndCreatesTaskWithoutStartingIt() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireEventMapper eventMapper = mock(FireEventMapper.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        SafetyCheckService safety = mock(SafetyCheckService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, eventMapper, planner, safety);

        FireMissionEntity approved = fireMission("M-AUTO-001", "APPROVED");
        FireMissionEntity routeGenerated = fireMission("M-AUTO-001", "ROUTE_GENERATED");
        FireMissionEntity routeExported = fireMission("M-AUTO-001", "ROUTE_EXPORTED");
        FireEventEntity event = new FireEventEntity();
        event.setId(99L);
        event.setLat(22.123456);
        event.setLng(113.654321);
        event.setAlt(12.0);
        event.setConfidence(new BigDecimal("0.96"));
        event.setGeoQuality("AUTO_WAYPOINT_READY");
        event.setGeoErrorRadiusM(6.0);

        SafetyCheckResult safe = new SafetyCheckResult();
        safe.setPassed(true);
        RouteFileDTO route = new RouteFileDTO();
        route.setId(22L);
        route.setObjectKey("/tmp/M-AUTO-001.kmz");
        route.setSign("sha256");
        MissionWaypointDTO wp = new MissionWaypointDTO();
        wp.setWaypointIndex(0);

        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(approved, routeGenerated, routeExported);
        when(eventMapper.selectById(99L)).thenReturn(event);
        when(safety.check(any(), any())).thenReturn(safe);
        when(planner.plan(any(WaypointGenerateParam.class))).thenReturn(List.of(wp));
        when(routeService.exportKmz("M-AUTO-001", "operator-1", "127.0.0.1", "REQ-001")).thenReturn(route);
        when(routeService.getLatest("M-AUTO-001")).thenReturn(route);
        when(routeService.downloadById(22L)).thenReturn(kmzWithTemplate("<kml><Document><name>M-AUTO-001</name></Document></kml>"));
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("WAYLINE-AUTO-001")
            .name("M-AUTO-001")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-AUTO-001", "2"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-001");

        DeliveryController.PrepareFireMissionDeliveryTaskParam param =
            new DeliveryController.PrepareFireMissionDeliveryTaskParam();
        param.setOperatorId("operator-1");
        ApiResult<DeliveryTaskRef> result = controller.prepareFireMissionDeliveryTask("M-AUTO-001", param, request);

        assertEquals("TASK-AUTO-001", result.getData().getTaskId());
        ArgumentCaptor<WaypointGenerateParam> waypointParam = ArgumentCaptor.forClass(WaypointGenerateParam.class);
        verify(planner).plan(waypointParam.capture());
        assertEquals(22.123456, waypointParam.getValue().getFireLat());
        assertEquals(113.654321, waypointParam.getValue().getFireLng());
        assertEquals(30.01, waypointParam.getValue().getTakeoffLat());
        assertEquals(120.01, waypointParam.getValue().getTakeoffLng());
        verify(planner).persistForMission(11L, List.of(wp));
        verify(routeService).exportKmz("M-AUTO-001", "operator-1", "127.0.0.1", "REQ-001");
        verify(adapter).createTask(any(CreateTaskRequest.class));
        verify(adapter, never()).startTask(any());
        ArgumentCaptor<TransitCommand> transit = ArgumentCaptor.forClass(TransitCommand.class);
        verify(stateMachine, times(2)).transit(transit.capture());
        assertEquals(FireMissionEvent.GEN_WP, transit.getAllValues().get(0).getEvent());
        assertEquals(FireMissionEvent.CREATE_DELIVERY_TASK, transit.getAllValues().get(1).getEvent());
    }

    @Test
    void prepareFireMissionDeliveryTaskRejectsLowQualityFireCoordinates() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireEventMapper eventMapper = mock(FireEventMapper.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        SafetyCheckService safety = mock(SafetyCheckService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, eventMapper, planner, safety);

        FireMissionEntity approved = fireMission("M-LOW-GEO-001", "APPROVED");
        FireEventEntity event = new FireEventEntity();
        event.setId(99L);
        event.setLat(22.123456);
        event.setLng(113.654321);
        event.setGeoQuality("DEM_MISSING");
        event.setGeoErrorRadiusM(99.0);

        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(approved);
        when(eventMapper.selectById(99L)).thenReturn(event);

        DeliveryController.PrepareFireMissionDeliveryTaskParam param =
            new DeliveryController.PrepareFireMissionDeliveryTaskParam();
        param.setOperatorId("operator-1");

        Fc100BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
            Fc100BusinessException.class,
            () -> controller.prepareFireMissionDeliveryTask("M-LOW-GEO-001", param, request));
        assertEquals(Fc100ErrorCode.INVALID_PARAM, ex.getErrorCode());
        verify(planner, never()).plan(any(WaypointGenerateParam.class));
    }

    @Test
    void prepareFireMissionDeliveryTaskRecreatesDeliveryTaskWhenSentTaskIsStale() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireEventMapper eventMapper = mock(FireEventMapper.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        SafetyCheckService safety = mock(SafetyCheckService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, eventMapper, planner, safety);

        FireMissionEntity sent = fireMission("M-STALE-001", "SENT_TO_DELIVERY");
        sent.setDjiTaskId("OLD-TASK-001");
        RouteFileDTO route = new RouteFileDTO();
        route.setId(22L);
        route.setObjectKey("/tmp/M-STALE-001.kmz");
        route.setSign("sha256");

        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(sent, sent);
        when(routeService.getLatest("M-STALE-001")).thenReturn(route);
        when(routeService.downloadById(22L)).thenReturn(kmzWithTemplate("<kml><Document><name>M-STALE-001</name></Document></kml>"));
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("WAYLINE-STALE-001")
            .name("M-STALE-001")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("NEW-TASK-001", "2"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-STALE");

        DeliveryController.PrepareFireMissionDeliveryTaskParam param =
            new DeliveryController.PrepareFireMissionDeliveryTaskParam();
        param.setOperatorId("operator-1");
        ApiResult<DeliveryTaskRef> result = controller.prepareFireMissionDeliveryTask("M-STALE-001", param, request);

        assertEquals("NEW-TASK-001", result.getData().getTaskId());
        verify(adapter).importWayline(any(WaylineImportRequest.class));
        verify(adapter).createTask(any(CreateTaskRequest.class));
        verify(missionMapper).update(any(), any(UpdateWrapper.class));
        ArgumentCaptor<TransitCommand> transit = ArgumentCaptor.forClass(TransitCommand.class);
        verify(stateMachine).transit(transit.capture());
        assertEquals(FireMissionEvent.CREATE_DELIVERY_TASK, transit.getValue().getEvent());
    }

    @Test
    void prepareFireMissionDeliveryTaskRecoversInProgressMissionWhenDeliveryTaskEndedAbnormallyAndAircraftStopped() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireEventMapper eventMapper = mock(FireEventMapper.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        SafetyCheckService safety = mock(SafetyCheckService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, eventMapper, planner, safety);

        FireMissionEntity inProgress = fireMission("M-RECOVER-001", "IN_PROGRESS");
        inProgress.setDjiTaskId("FAILED-TASK-001");
        RouteFileDTO route = new RouteFileDTO();
        route.setId(22L);
        route.setObjectKey("/tmp/M-RECOVER-001.kmz");
        route.setSign("sha256");

        DeliveryTaskStatus abnormal = new DeliveryTaskStatus();
        abnormal.setTaskId("FAILED-TASK-001");
        abnormal.setPhase("abnormal");
        abnormal.setTaskCode(620179);
        abnormal.setEndTime(1779978628898L);
        DeliveryDeviceProperties stopped = new DeliveryDeviceProperties();
        stopped.setDeviceSn("FC100-SN-001");
        stopped.setFlying(false);

        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(inProgress, inProgress);
        when(adapter.queryTaskStatus("FAILED-TASK-001")).thenReturn(abnormal);
        when(adapter.getDeviceProperties("FC100-SN-001")).thenReturn(stopped);
        when(routeService.getLatest("M-RECOVER-001")).thenReturn(route);
        when(routeService.downloadById(22L)).thenReturn(kmzWithTemplate("<kml><Document><name>M-RECOVER-001</name></Document></kml>"));
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("WAYLINE-RECOVER-001")
            .name("M-RECOVER-001")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("NEW-TASK-001", "2"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-RECOVER");

        DeliveryController.PrepareFireMissionDeliveryTaskParam param =
            new DeliveryController.PrepareFireMissionDeliveryTaskParam();
        param.setOperatorId("operator-1");
        ApiResult<DeliveryTaskRef> result = controller.prepareFireMissionDeliveryTask("M-RECOVER-001", param, request);

        assertEquals("NEW-TASK-001", result.getData().getTaskId());
        verify(adapter).queryTaskStatus("FAILED-TASK-001");
        verify(adapter).getDeviceProperties("FC100-SN-001");
        verify(adapter).createTask(any(CreateTaskRequest.class));
        ArgumentCaptor<TransitCommand> transit = ArgumentCaptor.forClass(TransitCommand.class);
        verify(stateMachine).transit(transit.capture());
        assertEquals(FireMissionEvent.CREATE_DELIVERY_TASK, transit.getValue().getEvent());
    }

    @Test
    void prepareFireMissionDeliveryTaskAllowsReleasedMissionToCreateAnotherTask() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireEventMapper eventMapper = mock(FireEventMapper.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        SafetyCheckService safety = mock(SafetyCheckService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, eventMapper, planner, safety);

        FireMissionEntity released = fireMission("M-SECOND-DROP-001", "PAYLOAD_RELEASED");
        released.setDjiTaskId("DONE-TASK-001");
        RouteFileDTO route = new RouteFileDTO();
        route.setId(22L);
        route.setObjectKey("/tmp/M-SECOND-DROP-001.kmz");
        route.setSign("sha256");

        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(released, released);
        when(routeService.getLatest("M-SECOND-DROP-001")).thenReturn(route);
        when(routeService.downloadById(22L)).thenReturn(kmzWithTemplate("<kml><Document><name>M-SECOND-DROP-001</name></Document></kml>"));
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("WAYLINE-SECOND-DROP-001")
            .name("M-SECOND-DROP-001")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("NEXT-TASK-001", "2"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-SECOND-DROP");

        DeliveryController.PrepareFireMissionDeliveryTaskParam param =
            new DeliveryController.PrepareFireMissionDeliveryTaskParam();
        param.setOperatorId("operator-1");
        ApiResult<DeliveryTaskRef> result = controller.prepareFireMissionDeliveryTask("M-SECOND-DROP-001", param, request);

        assertEquals("NEXT-TASK-001", result.getData().getTaskId());
        verify(adapter).createTask(any(CreateTaskRequest.class));
        ArgumentCaptor<TransitCommand> transit = ArgumentCaptor.forClass(TransitCommand.class);
        verify(stateMachine).transit(transit.capture());
        assertEquals(FireMissionEvent.CREATE_DELIVERY_TASK, transit.getValue().getEvent());
    }

    @Test
    void deviceLiveStartsDeliveryBypassStreamAndReturnsZlmPlaybackUrl() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);
        ReflectionTestUtils.setField(controller, "deliveryBypassRtmpUrl", "rtmp://192.168.0.30:1935/live/");
        ReflectionTestUtils.setField(controller, "webrtcPlaybackHost", "192.168.0.30");
        ReflectionTestUtils.setField(controller, "webrtcPlaybackPort", 58925);

        DeliveryDeviceProperties properties = new DeliveryDeviceProperties();
        properties.setDeviceSn("FC100-SN-001");
        properties.setOnlineStatus(true);
        when(adapter.getDeviceProperties("FC100-SN-001")).thenReturn(properties);
        when(adapter.startBypassStream(any(DeliveryBypassStreamRequest.class))).thenReturn(DeliveryBypassStreamDTO.builder()
            .converterId("CONVERTER-001")
            .playRtmpUrl("rtmp://192.168.0.30:1935/live/FC100-SN-001_39-0-7")
            .converterState("running")
            .build());

        ApiResult<DeliveryDeviceLiveDTO> result = controller.deviceLive("FC100-SN-001");
        ApiResult<DeliveryDeviceLiveDTO> cachedResult = controller.deviceLive("FC100-SN-001");

        assertEquals("FC100-SN-001", result.getData().getDeviceSn());
        assertEquals("running", result.getData().getStreamStatus());
        assertEquals("webrtc://192.168.0.30:58925/live/FC100-SN-001_39-0-7", result.getData().getPlayUrl());
        assertEquals(result.getData().getPlayUrl(), cachedResult.getData().getPlayUrl());
        assertEquals("delivery-platform", result.getData().getSource());
        assertTrue(result.getData().getMessage().contains("CONVERTER-001"));

        ArgumentCaptor<DeliveryBypassStreamRequest> bypassReq = ArgumentCaptor.forClass(DeliveryBypassStreamRequest.class);
        verify(adapter, times(1)).startBypassStream(bypassReq.capture());
        assertEquals("FC100-SN-001", bypassReq.getValue().getDeviceSn());
        assertEquals("rtmp://192.168.0.30:1935/live", bypassReq.getValue().getRtmpUrl());
        assertEquals("39-0-7", bypassReq.getValue().getCamera());
        assertEquals("normal-0", bypassReq.getValue().getVideo());
        assertEquals(7200L, bypassReq.getValue().getExpireTs());
        assertEquals(0, bypassReq.getValue().getVideoQuality());
    }

    @Test
    void statusAutoReleaseIsBlockedByManualPolicyAndAudited() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, planner, null, releasePolicyService(missionLogMapper));

        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(1L);
        mission.setMissionNo("M-AUTO-RELEASE-001");
        mission.setStatus("IN_PROGRESS");
        mission.setAircraftSn("FC100-SN-001");
        mission.setDjiTaskId("TASK-AUTO-RELEASE-001");
        mission.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);
        when(stateMachine.allowedEvents(FireMissionStatus.IN_PROGRESS))
            .thenReturn(Set.of(FireMissionEvent.MARK_RELEASE_PENDING));

        DeliveryTaskStatus completed = new DeliveryTaskStatus();
        completed.setTaskId("TASK-AUTO-RELEASE-001");
        completed.setPhase("completed");
        completed.setAccepted(true);
        when(adapter.queryTaskStatus("TASK-AUTO-RELEASE-001")).thenReturn(completed);

        // 新行为：脱钩要求飞机到投放点并悬停稳定，不再因 phase=completed 提前脱钩
        MissionWaypointDTO dropWp = new MissionWaypointDTO();
        dropWp.setWaypointIndex(1);
        dropWp.setWaypointType("DROP");
        dropWp.setLat(34.667795);
        dropWp.setLng(109.326400);
        when(planner.listLatest(1L)).thenReturn(List.of(dropWp));
        DeliveryDeviceProperties hovering = new DeliveryDeviceProperties();
        hovering.setDeviceSn("FC100-SN-001");
        hovering.setLatitude(34.667795);
        hovering.setLongitude(109.326400);
        hovering.setHorizontalSpeed(0.0);
        hovering.setVerticalSpeed(0.0);
        when(adapter.getDeviceProperties("FC100-SN-001")).thenReturn(hovering);

        DeliveryCommandRef releaseRef = new DeliveryCommandRef();
        releaseRef.setBid("BID-AUTO-HOOK");
        when(adapter.sendDeviceCommand(any(DeviceCommandRequest.class))).thenReturn(releaseRef);

        ApiResult<DeliveryTaskStatus> result = controller.status("M-AUTO-RELEASE-001");

        assertEquals("completed", result.getData().getPhase());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        verify(stateMachine).transit(any(TransitCommand.class));
    }

    @Test
    void scheduledPollingBlocksManualPolicyAutoReleaseWithoutPageStatusPoll() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, planner, null, releasePolicyService(missionLogMapper));

        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(2L);
        mission.setMissionNo("M-SCHEDULED-RELEASE-001");
        mission.setStatus("IN_PROGRESS");
        mission.setAircraftSn("FC100-SN-001");
        mission.setDjiTaskId("TASK-SCHEDULED-RELEASE-001");
        mission.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
        when(missionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mission));
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);
        when(stateMachine.allowedEvents(FireMissionStatus.IN_PROGRESS))
            .thenReturn(Set.of(FireMissionEvent.MARK_RELEASE_PENDING));

        DeliveryTaskStatus completed = new DeliveryTaskStatus();
        completed.setTaskId("TASK-SCHEDULED-RELEASE-001");
        completed.setPhase("completed");
        completed.setAccepted(true);
        when(adapter.queryTaskStatus("TASK-SCHEDULED-RELEASE-001")).thenReturn(completed);

        // 新行为：脱钩要求飞机到投放点并悬停稳定，不再因 phase=completed 提前脱钩
        MissionWaypointDTO dropWp = new MissionWaypointDTO();
        dropWp.setWaypointIndex(1);
        dropWp.setWaypointType("DROP");
        dropWp.setLat(34.667795);
        dropWp.setLng(109.326400);
        when(planner.listLatest(2L)).thenReturn(List.of(dropWp));
        DeliveryDeviceProperties hovering = new DeliveryDeviceProperties();
        hovering.setDeviceSn("FC100-SN-001");
        hovering.setLatitude(34.667795);
        hovering.setLongitude(109.326400);
        hovering.setHorizontalSpeed(0.0);
        hovering.setVerticalSpeed(0.0);
        when(adapter.getDeviceProperties("FC100-SN-001")).thenReturn(hovering);

        DeliveryCommandRef releaseRef = new DeliveryCommandRef();
        releaseRef.setBid("BID-SCHEDULED-HOOK");
        when(adapter.sendDeviceCommand(any(DeviceCommandRequest.class))).thenReturn(releaseRef);

        controller.pollInProgressMissionsForAutoRelease();

        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        verify(stateMachine).transit(any(TransitCommand.class));
    }

    @Test
    void scheduledPollingBlocksAutoReleaseWhenPolicyIsNotControlledTestAuto() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        WaypointPlannerService planner = mock(WaypointPlannerService.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, planner, null, releasePolicyService(missionLogMapper));

        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(11L);
        mission.setMissionNo("M-DROP-HOVER-001");
        mission.setStatus("IN_PROGRESS");
        mission.setAircraftSn("FC100-SN-001");
        mission.setDjiTaskId("TASK-DROP-HOVER-001");
        mission.setReleasePolicy(ReleasePolicy.DRY_RUN.name());
        when(missionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mission));
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);
        when(stateMachine.allowedEvents(FireMissionStatus.IN_PROGRESS))
            .thenReturn(Set.of(FireMissionEvent.MARK_RELEASE_PENDING));

        DeliveryTaskStatus running = new DeliveryTaskStatus();
        running.setTaskId("TASK-DROP-HOVER-001");
        running.setPhase("normal");
        running.setAccepted(true);
        when(adapter.queryTaskStatus("TASK-DROP-HOVER-001")).thenReturn(running);

        MissionWaypointDTO drop = new MissionWaypointDTO();
        drop.setWaypointIndex(1);
        drop.setWaypointType("DROP");
        drop.setLat(34.667795);
        drop.setLng(109.326400);
        when(planner.listLatest(11L)).thenReturn(List.of(drop));

        DeliveryDeviceProperties properties = new DeliveryDeviceProperties();
        properties.setDeviceSn("FC100-SN-001");
        properties.setLatitude(34.667795);
        properties.setLongitude(109.326400);
        properties.setHorizontalSpeed(0.0);
        properties.setVerticalSpeed(0.0);
        when(adapter.getDeviceProperties("FC100-SN-001")).thenReturn(properties);

        DeliveryCommandRef releaseRef = new DeliveryCommandRef();
        releaseRef.setBid("BID-DROP-HOVER-HOOK");
        when(adapter.sendDeviceCommand(any(DeviceCommandRequest.class))).thenReturn(releaseRef);

        controller.pollInProgressMissionsForAutoRelease();

        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        verify(stateMachine).transit(any(TransitCommand.class));
    }

    @Test
    void statusDoesNotAutoReleaseHookAgainAfterMissionLeftInProgress() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        FireMissionEntity mission = new FireMissionEntity();
        mission.setMissionNo("M-AUTO-RELEASE-001");
        mission.setStatus("PAYLOAD_RELEASED");
        mission.setAircraftSn("FC100-SN-001");
        mission.setDjiTaskId("TASK-AUTO-RELEASE-001");
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);

        DeliveryTaskStatus completed = new DeliveryTaskStatus();
        completed.setTaskId("TASK-AUTO-RELEASE-001");
        completed.setPhase("completed");
        completed.setAccepted(true);
        when(adapter.queryTaskStatus("TASK-AUTO-RELEASE-001")).thenReturn(completed);

        controller.status("M-AUTO-RELEASE-001");

        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        verify(stateMachine, never()).transit(any(TransitCommand.class));
    }

    @Test
    void createTaskImportsLatestRouteKmlBeforeCreatingApifoxTask() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(11L);
        mission.setMissionNo("M-001");
        mission.setWorkspaceId("WS-001");
        mission.setAircraftSn("SN-001");
        RouteFileDTO route = new RouteFileDTO();
        route.setId(22L);
        route.setObjectKey("/tmp/M-001.kmz");
        route.setSign("sha256");

        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);
        when(routeService.getLatest("M-001")).thenReturn(route);
        when(routeService.downloadById(22L)).thenReturn(kmzWithTemplate("<kml><Document><name>M-001</name></Document></kml>"));
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-001", "2"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        DeliveryController.CreateTaskParam param = new DeliveryController.CreateTaskParam();
        param.setOperatorId("operator-1");
        controller.createTask("M-001", param, request);

        InOrder order = inOrder(adapter);
        order.verify(adapter).importWayline(any(WaylineImportRequest.class));
        order.verify(adapter).createTask(any(CreateTaskRequest.class));

        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        UUID.fromString(importReq.getValue().getMissionNo());
        assertEquals(null, importReq.getValue().getWaylineId());
        assertTrue(importReq.getValue().getFilename().endsWith(".kmz"));
        assertEquals("application/vnd.google-earth.kmz", importReq.getValue().getContentType());
        assertTrue(zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml").contains("<name>火情任务-M-001-"));

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals(importReq.getValue().getMissionNo(), taskReq.getValue().getMissionId());
        assertEquals("SN-001", taskReq.getValue().getDeviceSn());
    }

    @Test
    void importCreateDirectWaylineTaskUploadsOriginalKmzAndCreatesTaskForSelectedDevice() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-001");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-WAYLINE-UUID")
            .name("route")
            .key("object-key")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-DIRECT-001", "2"));

        byte[] originalKmz = kmzWithTemplate("<kml><Document><name>direct-route</name></Document></kml>");
        MockMultipartFile kmz = new MockMultipartFile("file", "route.kmz", "application/vnd.google-earth.kmz",
            originalKmz);

        ApiResult<DeliveryTaskRef> result = controller.importCreateDirectWaylineTask(
            kmz,
            "1581FAN4C257L0010RBE",
            "现场导入航线",
            "operator-1",
            "direct verify",
            "WAYLINE-001");

        DeliveryTaskRef ref = result.getData();
        assertEquals("TASK-DIRECT-001", ref.getTaskId());

        InOrder order = inOrder(adapter);
        order.verify(adapter).importWayline(any(WaylineImportRequest.class));
        order.verify(adapter).createTask(any(CreateTaskRequest.class));

        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        assertEquals("WAYLINE-001", importReq.getValue().getWaylineId());
        assertEquals("route-WAYLINE-001.kmz", importReq.getValue().getFilename());
        assertEquals("application/vnd.google-earth.kmz", importReq.getValue().getContentType());
        assertTrue(zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml")
            .contains("<name>route-WAYLINE-001</name>"));

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("WS-001", taskReq.getValue().getWorkspaceId());
        assertEquals("1581FAN4C257L0010RBE", taskReq.getValue().getDeviceSn());
        assertEquals("IMPORTED-WAYLINE-UUID", taskReq.getValue().getMissionNo());
        assertEquals("IMPORTED-WAYLINE-UUID", taskReq.getValue().getMissionId());
        assertEquals("现场导入航线", taskReq.getValue().getTaskName());
    }

    @Test
    void importCreateDirectWaylineTaskNormalizesFc100FinishActionToNoAction() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-001");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-WAYLINE-UUID")
            .name("route")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-DIRECT-001", "2"));

        byte[] kmzBytes = kmzWithTemplateAndWaylines(
            "<kml><Document><wpml:finishAction>goHome</wpml:finishAction></Document></kml>",
            "<kml><Document><wpml:finishAction>autoLand</wpml:finishAction></Document></kml>");
        MockMultipartFile kmz = new MockMultipartFile("file", "route.kmz", "application/vnd.google-earth.kmz",
            kmzBytes);

        controller.importCreateDirectWaylineTask(
            kmz,
            "1581FAN4C257L0010RBE",
            "现场导入航线",
            "operator-1",
            "direct verify",
            "WAYLINE-001");

        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        String template = zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml");
        String waylines = zipEntry(importReq.getValue().getFileBytes(), "wpmz/waylines.wpml");
        assertTrue(template.contains("<wpml:finishAction>noAction</wpml:finishAction>"));
        assertTrue(waylines.contains("<wpml:finishAction>noAction</wpml:finishAction>"));
        assertFalse(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"));
        assertFalse(waylines.contains("<wpml:finishAction>autoLand</wpml:finishAction>"));
    }

    @Test
    void importCreateDirectWaylineTaskGeneratesUuidWaylineIdWhenNotProvided() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-001");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-WAYLINE-UUID")
            .name("route")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-DIRECT-001", "2"));

        MockMultipartFile kmz = new MockMultipartFile("file", "route.kmz", "application/vnd.google-earth.kmz",
            kmzWithTemplate("<kml><Document><name>direct-route</name></Document></kml>"));

        controller.importCreateDirectWaylineTask(
            kmz,
            "1581FAN4C257L0010RBE",
            "现场导入航线",
            "operator-1",
            "direct verify",
            null);

        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        UUID.fromString(importReq.getValue().getMissionNo());
        assertEquals(null, importReq.getValue().getWaylineId());

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("IMPORTED-WAYLINE-UUID", taskReq.getValue().getMissionId());
    }

    @Test
    void importCreateDirectWaylineTaskReusesExistingWaylineWhenImportNameDuplicated() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-001");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        when(adapter.importWayline(any(WaylineImportRequest.class))).thenThrow(new Fc100BusinessException(
            Fc100ErrorCode.DELIVERY_SYNC_BUSINESS,
            "importWayline failed HTTP 400: {\"code\":203541,\"message\":\"航线名称重复\",\"data\":null}"));
        when(adapter.listWaylines(1, 100, "route-ROUTE-001")).thenReturn(List.of(DeliveryWaylineDTO.builder()
            .waylineId("EXISTING-WAYLINE-UUID")
            .name("route-ROUTE-001")
            .build()));
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-DIRECT-001", "2"));

        MockMultipartFile kmz = new MockMultipartFile("file", "route.kmz", "application/vnd.google-earth.kmz",
            kmzWithTemplate("<kml><Document><name>route</name></Document></kml>"));

        ApiResult<DeliveryTaskRef> result = controller.importCreateDirectWaylineTask(
            kmz,
            "1581FAN4C257L0010RBE",
            null,
            "operator-1",
            "direct verify",
            "ROUTE-001");

        assertEquals("TASK-DIRECT-001", result.getData().getTaskId());
        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("EXISTING-WAYLINE-UUID", taskReq.getValue().getMissionNo());
        assertEquals("EXISTING-WAYLINE-UUID", taskReq.getValue().getMissionId());
        assertEquals("FC100航线-route-ROUTE-001", taskReq.getValue().getTaskName());
    }

    @Test
    void importCreateGeneratedPlannedWaylineTaskReadsPublishedKmzOnServerSide() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-FC100");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        IPlannedWaylineService plannedWaylineService = mock(IPlannedWaylineService.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            plannedWaylineService, waylineFileService);

        PlannedWaylineDTO planned = PlannedWaylineDTO.builder()
            .plannedWaylineId("PW-001")
            .workspaceId("WS-FC100")
            .name("FC100 planned route")
            .publishedWaylineId("WAYLINE-FILE-001")
            .kmzUrl("http://minio-internal/wayline.kmz")
            .build();
        byte[] kmzBytes = kmzWithTemplate("<kml><Document><name>planned</name></Document></kml>");
        when(plannedWaylineService.getOne("WS-FC100", "PW-001")).thenReturn(java.util.Optional.of(planned));
        when(waylineFileService.downloadWaylineContent("WS-FC100", "WAYLINE-FILE-001")).thenReturn(kmzBytes);
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-PW-001")
            .name("FC100 planned route")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-PW-001", "2"));

        DeliveryController.ImportGeneratedPlannedWaylineTaskParam param =
            new DeliveryController.ImportGeneratedPlannedWaylineTaskParam();
        param.setWorkspaceId("WS-FC100");
        param.setPlannedWaylineId("PW-001");
        param.setDeviceSn("FC100-DRONE-001");
        param.setTaskName("FC100 planned route");
        param.setOperatorId("operator-1");

        ApiResult<DeliveryTaskRef> result = controller.importCreateGeneratedPlannedWaylineTask(param);

        assertEquals("TASK-PW-001", result.getData().getTaskId());
        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        assertEquals("PW-001", importReq.getValue().getMissionNo());
        assertEquals(null, importReq.getValue().getWaylineId());
        assertEquals("FC100 planned route-PW-001.kmz", importReq.getValue().getFilename());
        assertEquals("application/vnd.google-earth.kmz", importReq.getValue().getContentType());
        assertTrue(zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml")
            .contains("<name>FC100 planned route-PW-001</name>"));

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("WS-FC100", taskReq.getValue().getWorkspaceId());
        assertEquals("FC100-DRONE-001", taskReq.getValue().getDeviceSn());
        assertEquals("IMPORTED-PW-001", taskReq.getValue().getMissionId());
        assertEquals("FC100 planned route", taskReq.getValue().getTaskName());
    }

    @Test
    void importCreateGeneratedPlannedWaylineTaskNormalizesM30tKmzToFc100M4tBeforeImport() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-FC100");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        IPlannedWaylineService plannedWaylineService = mock(IPlannedWaylineService.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            plannedWaylineService, waylineFileService);

        PlannedWaylineDTO planned = PlannedWaylineDTO.builder()
            .plannedWaylineId("PW-M30T")
            .workspaceId("WS-FC100")
            .name("M30T planned route")
            .aircraftModelKey("M30T")
            .publishedWaylineId("WAYLINE-FILE-M30T")
            .build();
        byte[] m30tKmz = kmzWithTemplateAndWaylines(
            "<kml><Document><wpml:droneEnumValue>67</wpml:droneEnumValue><wpml:payloadEnumValue>53</wpml:payloadEnumValue></Document></kml>",
            "<kml><Document><wpml:droneEnumValue>67</wpml:droneEnumValue><wpml:payloadEnumValue>53</wpml:payloadEnumValue></Document></kml>");
        when(plannedWaylineService.getOne("WS-FC100", "PW-M30T")).thenReturn(java.util.Optional.of(planned));
        when(waylineFileService.downloadWaylineContent("WS-FC100", "WAYLINE-FILE-M30T")).thenReturn(m30tKmz);
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-M4T")
            .name("M30T planned route")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-M4T", "2"));

        DeliveryController.ImportGeneratedPlannedWaylineTaskParam param =
            new DeliveryController.ImportGeneratedPlannedWaylineTaskParam();
        param.setWorkspaceId("WS-FC100");
        param.setPlannedWaylineId("PW-M30T");
        param.setDeviceSn("FC100-DRONE-001");

        controller.importCreateGeneratedPlannedWaylineTask(param);

        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        assertEquals(null, importReq.getValue().getWaylineId());
        String template = zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml");
        String waylines = zipEntry(importReq.getValue().getFileBytes(), "wpmz/waylines.wpml");
        assertTrue(template.contains("<wpml:droneEnumValue>100</wpml:droneEnumValue>"));
        assertTrue(template.contains("<wpml:payloadEnumValue>99</wpml:payloadEnumValue>"));
        assertTrue(waylines.contains("<wpml:droneEnumValue>100</wpml:droneEnumValue>"));
        assertTrue(waylines.contains("<wpml:payloadEnumValue>99</wpml:payloadEnumValue>"));
        assertFalse(template.contains("<wpml:droneEnumValue>67</wpml:droneEnumValue>"));
        assertFalse(template.contains("<wpml:payloadEnumValue>53</wpml:payloadEnumValue>"));
    }

    @Test
    void importCreateGeneratedPlannedWaylineTaskNormalizesFinishActionToNoActionBeforeImport() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-FC100");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        IPlannedWaylineService plannedWaylineService = mock(IPlannedWaylineService.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            plannedWaylineService, waylineFileService);

        PlannedWaylineDTO planned = PlannedWaylineDTO.builder()
            .plannedWaylineId("PW-FINISH")
            .workspaceId("WS-FC100")
            .name("planned finish route")
            .aircraftModelKey("FC100")
            .publishedWaylineId("WAYLINE-FILE-FINISH")
            .build();
        byte[] kmzBytes = kmzWithTemplateAndWaylines(
            "<kml><Document><wpml:finishAction>goHome</wpml:finishAction></Document></kml>",
            "<kml><Document><wpml:finishAction>gotoFirstWaypoint</wpml:finishAction></Document></kml>");
        when(plannedWaylineService.getOne("WS-FC100", "PW-FINISH")).thenReturn(java.util.Optional.of(planned));
        when(waylineFileService.downloadWaylineContent("WS-FC100", "WAYLINE-FILE-FINISH")).thenReturn(kmzBytes);
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-FINISH")
            .name("planned finish route")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-FINISH", "2"));

        DeliveryController.ImportGeneratedPlannedWaylineTaskParam param =
            new DeliveryController.ImportGeneratedPlannedWaylineTaskParam();
        param.setWorkspaceId("WS-FC100");
        param.setPlannedWaylineId("PW-FINISH");
        param.setDeviceSn("FC100-DRONE-001");

        controller.importCreateGeneratedPlannedWaylineTask(param);

        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        String template = zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml");
        String waylines = zipEntry(importReq.getValue().getFileBytes(), "wpmz/waylines.wpml");
        assertTrue(template.contains("<wpml:finishAction>noAction</wpml:finishAction>"));
        assertTrue(waylines.contains("<wpml:finishAction>noAction</wpml:finishAction>"));
        assertFalse(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"));
        assertFalse(waylines.contains("<wpml:finishAction>gotoFirstWaypoint</wpml:finishAction>"));
    }

    @Test
    void importCreateGeneratedPlannedWaylineTaskReusesExistingFc100WaylineWhenGeneratedKmzMissing() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-FC100");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        IPlannedWaylineService plannedWaylineService = mock(IPlannedWaylineService.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            plannedWaylineService, waylineFileService);

        PlannedWaylineDTO planned = PlannedWaylineDTO.builder()
            .plannedWaylineId("PW-001")
            .workspaceId("WS-FC100")
            .name("2026-05-26 17_49")
            .publishedWaylineId("MISSING-WAYLINE-FILE")
            .build();
        when(plannedWaylineService.getOne("WS-FC100", "PW-001")).thenReturn(java.util.Optional.of(planned));
        when(waylineFileService.downloadWaylineContent("WS-FC100", "MISSING-WAYLINE-FILE"))
            .thenThrow(new SQLException("Failed to read wayline file content."));
        when(adapter.listWaylines(1, 100, "2026-05-26 17_49")).thenReturn(List.of(DeliveryWaylineDTO.builder()
            .waylineId("f7151cbd-5016-415f-8fde-6a19c2395987")
            .name("2026-05-26 17_49")
            .build()));
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-PW-EXISTING", "2"));

        DeliveryController.ImportGeneratedPlannedWaylineTaskParam param =
            new DeliveryController.ImportGeneratedPlannedWaylineTaskParam();
        param.setWorkspaceId("WS-FC100");
        param.setPlannedWaylineId("PW-001");
        param.setDeviceSn("FC100-DRONE-001");
        param.setTaskName("2026-05-26 17_49");

        ApiResult<DeliveryTaskRef> result = controller.importCreateGeneratedPlannedWaylineTask(param);

        assertEquals("TASK-PW-EXISTING", result.getData().getTaskId());
        verify(adapter, never()).importWayline(any(WaylineImportRequest.class));
        verify(plannedWaylineService, never()).generateFile(any(), any(), any());
        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("f7151cbd-5016-415f-8fde-6a19c2395987", taskReq.getValue().getMissionNo());
        assertEquals("f7151cbd-5016-415f-8fde-6a19c2395987", taskReq.getValue().getMissionId());
    }

    @Test
    void importCreateGeneratedPlannedWaylineTaskRegeneratesKmzWhenStoredContentIsMissingAndNoFc100WaylineExists() throws Exception {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-FC100");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        IPlannedWaylineService plannedWaylineService = mock(IPlannedWaylineService.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            plannedWaylineService, waylineFileService);

        PlannedWaylineDTO planned = PlannedWaylineDTO.builder()
            .plannedWaylineId("PW-002")
            .workspaceId("WS-FC100")
            .name("missing local file route")
            .publishedWaylineId("OLD-WAYLINE-FILE")
            .build();
        PlannedWaylineDTO regenerated = PlannedWaylineDTO.builder()
            .plannedWaylineId("PW-002")
            .workspaceId("WS-FC100")
            .name("missing local file route")
            .publishedWaylineId("NEW-WAYLINE-FILE")
            .build();
        byte[] regeneratedKmz = kmzWithTemplate("<kml><Document><name>missing local file route</name></Document></kml>");

        when(plannedWaylineService.getOne("WS-FC100", "PW-002")).thenReturn(java.util.Optional.of(planned));
        when(waylineFileService.downloadWaylineContent("WS-FC100", "OLD-WAYLINE-FILE"))
            .thenThrow(new SQLException("Failed to read wayline file content."));
        when(adapter.listWaylines(1, 100, "missing local file route")).thenReturn(List.of());
        when(plannedWaylineService.generateFile("WS-FC100", "PW-002", "operator-1")).thenReturn(regenerated);
        when(waylineFileService.downloadWaylineContent("WS-FC100", "NEW-WAYLINE-FILE")).thenReturn(regeneratedKmz);
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
            .waylineId("IMPORTED-REGENERATED")
            .name("missing local file route")
            .build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-PW-REGENERATED", "2"));

        DeliveryController.ImportGeneratedPlannedWaylineTaskParam param =
            new DeliveryController.ImportGeneratedPlannedWaylineTaskParam();
        param.setWorkspaceId("WS-FC100");
        param.setPlannedWaylineId("PW-002");
        param.setDeviceSn("FC100-DRONE-001");
        param.setOperatorId("operator-1");

        ApiResult<DeliveryTaskRef> result = controller.importCreateGeneratedPlannedWaylineTask(param);

        assertEquals("TASK-PW-REGENERATED", result.getData().getTaskId());
        verify(plannedWaylineService).generateFile("WS-FC100", "PW-002", "operator-1");
        ArgumentCaptor<WaylineImportRequest> importReq = ArgumentCaptor.forClass(WaylineImportRequest.class);
        verify(adapter).importWayline(importReq.capture());
        assertEquals(null, importReq.getValue().getWaylineId());
        assertEquals("missing local file route-PW-002.kmz", importReq.getValue().getFilename());
        assertTrue(zipEntry(importReq.getValue().getFileBytes(), "wpmz/template.kml")
            .contains("<name>missing local file route-PW-002</name>"));
    }

    @Test
    void listsFc100WaylinesForExistingCloudRoutes() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);

        DeliveryWaylineDTO route = DeliveryWaylineDTO.builder()
            .waylineId("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3")
            .name("2026-05-26 17:49")
            .waylineType("waypoint")
            .distance(141.55)
            .duration(14)
            .finishAction("goHome")
            .build();
        when(adapter.listWaylines(1, 10, null)).thenReturn(List.of(route));

        ApiResult<List<DeliveryWaylineDTO>> result = controller.waylines(1, 10, null);

        assertEquals("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3", result.getData().get(0).getWaylineId());
        verify(adapter).listWaylines(1, 10, null);
    }

    @Test
    void createDirectTaskFromExistingWaylineUsesWaylineIdAsMissionId() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setWorkspaceId("WS-001");
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-FC100-001", "2"));

        DeliveryController.CreateExistingWaylineTaskParam param = new DeliveryController.CreateExistingWaylineTaskParam();
        param.setDeviceSn("1581FAN4C257L0010RBE");
        param.setWaylineId("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3");
        param.setTaskName("FC100 已有航线测试");
        param.setRemark("use existing synced route");

        ApiResult<DeliveryTaskRef> result = controller.createTaskFromExistingWayline(param);

        assertEquals("TASK-FC100-001", result.getData().getTaskId());
        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("WS-001", taskReq.getValue().getWorkspaceId());
        assertEquals("1581FAN4C257L0010RBE", taskReq.getValue().getDeviceSn());
        assertEquals("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3", taskReq.getValue().getMissionNo());
        assertEquals("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3", taskReq.getValue().getMissionId());
        assertEquals("FC100 已有航线测试", taskReq.getValue().getTaskName());
    }

    @Test
    void startDirectWaylineTaskReturnsFc100DisplayMessage() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);
        when(adapter.startTask("TASK-001")).thenReturn(DeliveryTaskOperationResult.builder()
            .operation("startTask")
            .taskId("TASK-001")
            .accepted(false)
            .apiCode(204027)
            .apiMessage("设备网络检查失败")
            .displayMessage("设备网络检查失败")
            .build());

        ApiResult<DeliveryTaskOperationResult> result = controller.startDirectWaylineTask("TASK-001", null);

        assertEquals(false, result.getData().getAccepted());
        assertEquals(204027, result.getData().getApiCode());
        assertEquals("设备网络检查失败", result.getData().getDisplayMessage());
    }

    @Test
    void startDirectWaylineTaskBlocksBeforeFc100StartWhenPreflightFails() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine);
        DeliveryDeviceProperties properties = new DeliveryDeviceProperties();
        properties.setDeviceSn("DRONE-001");
        properties.setOnlineStatus(false);
        properties.setBatteryPercent(22);
        when(adapter.getDeviceProperties("DRONE-001")).thenReturn(properties);

        ApiResult<DeliveryTaskOperationResult> result = controller.startDirectWaylineTask("TASK-001", "DRONE-001");

        assertEquals(false, result.getData().getAccepted());
        assertEquals("preflight", result.getData().getStatus());
        assertTrue(result.getData().getDisplayMessage().contains("飞行器离线"));
        assertTrue(result.getData().getDisplayMessage().contains("电量低于 30%"));
        verify(adapter, never()).startTask("TASK-001");
    }

    @Test
    void releaseHookRejectsManualPolicyWithoutConfirmationAndAuditsAttempt() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, releasePolicyService(missionLogMapper));
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(11L);
        mission.setMissionNo("M-FC100-001");
        mission.setAircraftSn("FC100-SN-001");
        mission.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);

        DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
        param.setOperatorId("operator-1");

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> controller.releaseHook("M-FC100-001", param));

        assertEquals(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN, ex.getErrorCode());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        ArgumentCaptor<FireMissionLogEntity> logCaptor = ArgumentCaptor.forClass(FireMissionLogEntity.class);
        verify(missionLogMapper).insert(logCaptor.capture());
        assertEquals("PAYLOAD_RELEASE_STATUS_DENIED", logCaptor.getValue().getAction());
        assertEquals("operator-1", logCaptor.getValue().getOperatorId());
    }

    @Test
    void releaseHookAfterManualConfirmationEnqueuesCommandWithoutDirectAdapterSend() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        CommandQueueService commandQueueService = mock(CommandQueueService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, releasePolicyService(missionLogMapper), commandQueueService);
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(15L);
        mission.setMissionNo("M-FC100-QUEUE");
        mission.setAircraftSn("FC100-SN-001");
        mission.setStatus("PAYLOAD_RELEASE_PENDING");
        mission.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
        mission.setReleaseConfirmationToken("TOKEN-OK");
        mission.setReleaseTokenExpiresAt(1779163740000L);
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);
        OperationCommandEventEntity event = new OperationCommandEventEntity();
        event.setCommandId("CMD-QUEUE-001");
        event.setTargetSn("FC100-SN-001");
        event.setCommandType("hoist_hook_control");
        event.setStatus("PENDING");
        when(commandQueueService.enqueue(any(), any(), any(), any(), any())).thenReturn(event);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-QUEUE");
        when(request.getHeader("X-Idempotency-Key")).thenReturn("IDEMP-QUEUE");

        DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
        param.setOperatorId("operator-1");
        param.setConfirmedRelease(true);
        param.setConfirmationToken("TOKEN-OK");

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> controller.releaseHook("M-FC100-QUEUE", param, request));

        assertEquals(Fc100ErrorCode.RELEASE_CAPABILITY_UNCONFIRMED, ex.getErrorCode());
        verify(commandQueueService, never()).enqueue(any(), any(), any(), any(), any());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
    }

    @Test
    void releaseHookRejectsControlledTestAutoWhenSwitchIsDisabled() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, releasePolicyService(missionLogMapper));
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(12L);
        mission.setMissionNo("M-FC100-002");
        mission.setAircraftSn("FC100-SN-001");
        mission.setStatus("PAYLOAD_RELEASE_PENDING");
        mission.setReleasePolicy(ReleasePolicy.CONTROLLED_TEST_AUTO.name());
        mission.setReleaseConfirmationToken("TOKEN-OK");
        mission.setReleaseTokenExpiresAt(1779163740000L);
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);

        DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
        param.setOperatorId("operator-1");
        param.setConfirmedRelease(true);
        param.setConfirmationToken("TOKEN-OK");

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> controller.releaseHook("M-FC100-002", param));

        assertEquals(Fc100ErrorCode.CONTROLLED_TEST_AUTO_DISABLED, ex.getErrorCode());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        verify(missionLogMapper).insert(any(FireMissionLogEntity.class));
    }

    @Test
    void releaseHookDryRunAuditsWithoutSendingDeviceCommand() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, releasePolicyService(missionLogMapper));
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(14L);
        mission.setMissionNo("M-FC100-DRY");
        mission.setAircraftSn("FC100-SN-001");
        mission.setStatus("PAYLOAD_RELEASE_PENDING");
        mission.setReleasePolicy(ReleasePolicy.DRY_RUN.name());
        mission.setReleaseConfirmationToken("TOKEN-OK");
        mission.setReleaseTokenExpiresAt(1779163740000L);
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);

        DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
        param.setOperatorId("operator-1");
        param.setConfirmationToken("TOKEN-OK");

        ApiResult<DeliveryCommandRef> result = controller.releaseHook("M-FC100-DRY", param);

        assertEquals(null, result.getData());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        ArgumentCaptor<FireMissionLogEntity> logCaptor = ArgumentCaptor.forClass(FireMissionLogEntity.class);
        verify(missionLogMapper).insert(logCaptor.capture());
        assertEquals("PAYLOAD_RELEASE_DRY_RUN", logCaptor.getValue().getAction());
        assertEquals("operator-1", logCaptor.getValue().getOperatorId());
    }

    @Test
    void releaseHookRejectsDeliverySyncRemoteUntilCapabilityIsConfirmed() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        FireMissionLogMapper missionLogMapper = mock(FireMissionLogMapper.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, releasePolicyService(missionLogMapper));
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(13L);
        mission.setMissionNo("M-FC100-003");
        mission.setAircraftSn("FC100-SN-001");
        mission.setStatus("PAYLOAD_RELEASE_PENDING");
        mission.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
        mission.setReleaseExecutionMode(ReleaseExecutionMode.DELIVERY_SYNC_REMOTE.name());
        mission.setReleaseConfirmationToken("TOKEN-OK");
        mission.setReleaseTokenExpiresAt(1779163740000L);
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);

        DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
        param.setOperatorId("operator-1");
        param.setConfirmedRelease(true);
        param.setConfirmationToken("TOKEN-OK");

        Fc100BusinessException ex = assertThrows(
            Fc100BusinessException.class,
            () -> controller.releaseHook("M-FC100-003", param));

        assertEquals(Fc100ErrorCode.RELEASE_CAPABILITY_UNCONFIRMED, ex.getErrorCode());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
        verify(missionLogMapper).insert(any(FireMissionLogEntity.class));
    }

    @Test
    void releasePendingTimeoutEnqueuesReturnHomeAndMarksReturning() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        CommandQueueService commandQueue = mock(CommandQueueService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, null, commandQueue);
        FireMissionEntity mission = fireMission("M-RELEASE-TIMEOUT-001", FireMissionStatus.PAYLOAD_RELEASE_PENDING.name());
        mission.setReleaseTokenExpiresAt(1L);
        when(missionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mission));
        when(commandQueue.enqueue(any(), any(), any(), any(), any())).thenReturn(queuedCommand("CMD-RETURN-HOME"));

        controller.scanReleasePendingTimeouts();

        verify(commandQueue).enqueue(any(), any(), any(), any(), any());
        ArgumentCaptor<TransitCommand> transitCaptor = ArgumentCaptor.forClass(TransitCommand.class);
        verify(stateMachine).transit(transitCaptor.capture());
        assertEquals(FireMissionEvent.MARK_RETURNING, transitCaptor.getValue().getEvent());
        assertEquals("RELEASE_PENDING_TIMEOUT_AUTO_RETURN", transitCaptor.getValue().getRemark());
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
    }

    @Test
    void releasePendingTimeoutDisabledOnlyAlertsWithoutCommandOrStateChange() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncProperties props = new DeliverySyncProperties();
        FireMissionMapper missionMapper = mock(FireMissionMapper.class);
        RouteExportService routeService = mock(RouteExportService.class);
        MissionStateMachine stateMachine = mock(MissionStateMachine.class);
        CommandQueueService commandQueue = mock(CommandQueueService.class);
        DeliveryController controller = new DeliveryController(adapter, props, missionMapper, routeService, stateMachine,
            null, null, null, null, null, null, commandQueue);
        ReflectionTestUtils.setField(controller, "releasePendingTimeoutAutoReturnEnabled", false);
        FireMissionEntity mission = fireMission("M-RELEASE-TIMEOUT-002", FireMissionStatus.PAYLOAD_RELEASE_PENDING.name());
        mission.setReleaseTokenExpiresAt(1L);
        when(missionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mission));

        controller.scanReleasePendingTimeouts();

        verify(commandQueue, never()).enqueue(any(), any(), any(), any(), any());
        verify(stateMachine, never()).transit(any(TransitCommand.class));
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
    }

    private PayloadReleasePolicyService releasePolicyService(FireMissionLogMapper missionLogMapper) {
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1779163440000L);
        return new PayloadReleasePolicyService(missionLogMapper, clock);
    }

    private OperationCommandEventEntity queuedCommand(String commandId) {
        OperationCommandEventEntity event = new OperationCommandEventEntity();
        event.setCommandId(commandId);
        event.setTargetSn("FC100-SN-001");
        event.setCommandType("return_home");
        event.setStatus("QUEUED");
        event.setCreateTime(1779163440000L);
        event.setUpdateTime(1779163440000L);
        return event;
    }

    private byte[] kmzWithTemplate(String templateKml) throws Exception {
        return kmzWithTemplateAndWaylines(templateKml, null);
    }

    private FireMissionEntity fireMission(String missionNo, String status) {
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(11L);
        mission.setMissionNo(missionNo);
        mission.setWorkspaceId("WS-001");
        mission.setFireEventId(99L);
        mission.setStatus(status);
        mission.setAircraftSn("FC100-SN-001");
        mission.setTakeoffLat(30.01);
        mission.setTakeoffLng(120.01);
        mission.setTakeoffAlt(20.0);
        mission.setWindSpeedAtApproval(3.0);
        mission.setWindDirectionDeg(45.0);
        return mission;
    }

    private byte[] kmzWithTemplateAndWaylines(String templateKml, String waylinesWpml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zip.write(templateKml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (waylinesWpml != null) {
                zip.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
                zip.write(waylinesWpml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private String zipEntry(byte[] zipBytes, String entryName) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                entry = zip.getNextEntry();
            }
        }
        return "";
    }
}
