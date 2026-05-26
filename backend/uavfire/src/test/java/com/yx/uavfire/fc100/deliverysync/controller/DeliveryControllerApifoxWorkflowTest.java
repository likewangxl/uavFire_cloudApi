package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
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

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
        assertTrue(importReq.getValue().getKml().contains("<name>M-001</name>"));

        ArgumentCaptor<CreateTaskRequest> taskReq = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(adapter).createTask(taskReq.capture());
        assertEquals("M-001", taskReq.getValue().getMissionId());
        assertEquals("SN-001", taskReq.getValue().getDeviceSn());
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
