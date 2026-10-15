package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryTaskIntentTest {

    private final DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
    private final FireMissionMapper missionMapper = mock(FireMissionMapper.class);
    private final RouteExportService routeService = mock(RouteExportService.class);
    private final MissionStateMachine stateMachine = mock(MissionStateMachine.class);
    private final DeliveryController controller = new DeliveryController(
            adapter, new DeliverySyncProperties(), missionMapper, routeService, stateMachine);

    @Test
    void localIntentIsWrittenBeforeTheExternalCreate() throws Exception {
        stubRoute(mission("M-1"));
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-1", "2"));

        DeliveryController.CreateTaskParam param = new DeliveryController.CreateTaskParam();
        param.setOperatorId("commander-1");
        assertEquals("TASK-1", controller.createTask("M-1", param, null).getData().getTaskId());

        InOrder order = inOrder(adapter, missionMapper);
        order.verify(adapter).importWayline(any(WaylineImportRequest.class));
        order.verify(missionMapper).update(any(), any());
        order.verify(adapter).createTask(any(CreateTaskRequest.class));
        verify(stateMachine).transit(any());
        assertTrue(statuses().contains("PENDING"));
        assertTrue(statuses().contains("EXTERNAL_CREATED"));
    }

    @Test
    void externalFailureDoesNotTransit() throws Exception {
        stubRoute(mission("M-1"));
        when(adapter.createTask(any(CreateTaskRequest.class))).thenThrow(new RuntimeException("vendor down"));

        DeliveryController.CreateTaskParam param = new DeliveryController.CreateTaskParam();
        param.setOperatorId("commander-1");
        assertThrows(RuntimeException.class, () -> controller.createTask("M-1", param, null));

        verify(stateMachine, never()).transit(any());
        assertTrue(statuses().contains("PENDING"));
        assertTrue(statuses().contains("FAILED"));
        assertTrue(!statuses().contains("EXTERNAL_CREATED"));
    }

    @Test
    void localCommitFailureRetriesTransitWithoutCreatingAgain() throws Exception {
        FireMissionEntity first = mission("M-1");
        FireMissionEntity retry = mission("M-1");
        retry.setDeliveryIntentId("intent-kept");
        retry.setDeliveryIntentStatus("LOCAL_COMMIT_FAILED");
        retry.setDjiTaskId("TASK-1");
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(first, retry);
        RouteFileDTO route = route();
        when(routeService.getLatest("M-1")).thenReturn(route);
        when(routeService.downloadById(22L)).thenReturn(kmz());
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(
                DeliveryWaylineImportResult.builder().waylineId("WAYLINE-1").name("route").build());
        when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-1", "2"));
        when(stateMachine.transit(any()))
                .thenThrow(new RuntimeException("local commit"))
                .thenReturn(retry);

        DeliveryController.CreateTaskParam param = new DeliveryController.CreateTaskParam();
        param.setOperatorId("commander-1");
        assertThrows(RuntimeException.class, () -> controller.createTask("M-1", param, null));
        assertEquals("TASK-1", controller.createTask("M-1", param, null).getData().getTaskId());

        verify(adapter, times(1)).createTask(any(CreateTaskRequest.class));
        verify(stateMachine, times(2)).transit(any());
        assertTrue(statuses().contains("LOCAL_COMMIT_FAILED"));
    }

    private void stubRoute(FireMissionEntity mission) throws Exception {
        when(missionMapper.selectOne(any(Wrapper.class))).thenReturn(mission);
        when(routeService.getLatest(mission.getMissionNo())).thenReturn(route());
        when(routeService.downloadById(22L)).thenReturn(kmz());
        when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(
                DeliveryWaylineImportResult.builder().waylineId("WAYLINE-1").name("route").build());
    }

    private String statuses() {
        org.mockito.ArgumentCaptor<UpdateWrapper<FireMissionEntity>> updates =
                org.mockito.ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(missionMapper, org.mockito.Mockito.atLeastOnce()).update(any(), updates.capture());
        StringBuilder values = new StringBuilder();
        for (UpdateWrapper<FireMissionEntity> update : updates.getAllValues()) {
            update.getParamNameValuePairs().values().forEach(value -> values.append(value).append(','));
        }
        return values.toString();
    }

    private static FireMissionEntity mission(String missionNo) {
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(11L);
        mission.setMissionNo(missionNo);
        mission.setWorkspaceId("WS-001");
        mission.setAircraftSn("FC100-SN-001");
        mission.setStatus("ROUTE_EXPORTED");
        return mission;
    }

    private static RouteFileDTO route() {
        RouteFileDTO route = new RouteFileDTO();
        route.setId(22L);
        route.setObjectKey("/tmp/M-1.kmz");
        route.setSign("sha256");
        return route;
    }

    private static byte[] kmz() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zip.write("<kml><Document><name>M-1</name></Document></kml>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
