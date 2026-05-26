package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineDTO;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryControllerApifoxWorkflowTest {

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
        assertEquals("M-001", importReq.getValue().getWaylineId());
        assertEquals("template.kml", importReq.getValue().getFilename());
        assertEquals("application/vnd.google-earth.kml+xml", importReq.getValue().getContentType());
        assertTrue(new String(importReq.getValue().getFileBytes(), StandardCharsets.UTF_8).contains("<name>M-001</name>"));

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("M-001", taskReq.getValue().getMissionId());
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
        assertEquals("route.kmz", importReq.getValue().getFilename());
        assertEquals("application/vnd.google-earth.kmz", importReq.getValue().getContentType());
        assertEquals(originalKmz.length, importReq.getValue().getFileBytes().length);

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("WS-001", taskReq.getValue().getWorkspaceId());
        assertEquals("1581FAN4C257L0010RBE", taskReq.getValue().getDeviceSn());
        assertEquals("IMPORTED-WAYLINE-UUID", taskReq.getValue().getMissionNo());
        assertEquals("IMPORTED-WAYLINE-UUID", taskReq.getValue().getMissionId());
        assertEquals("现场导入航线", taskReq.getValue().getTaskName());
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
        when(adapter.listWaylines(1, 100, "route")).thenReturn(List.of(DeliveryWaylineDTO.builder()
            .waylineId("EXISTING-WAYLINE-UUID")
            .name("route")
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
            null);

        assertEquals("TASK-DIRECT-001", result.getData().getTaskId());
        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("EXISTING-WAYLINE-UUID", taskReq.getValue().getMissionNo());
        assertEquals("EXISTING-WAYLINE-UUID", taskReq.getValue().getMissionId());
        assertEquals("FC100航线-route", taskReq.getValue().getTaskName());
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

    private byte[] kmzWithTemplate(String templateKml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zip.write(templateKml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
