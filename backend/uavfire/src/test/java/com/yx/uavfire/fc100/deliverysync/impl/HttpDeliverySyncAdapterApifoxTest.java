package com.yx.uavfire.fc100.deliverysync.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.http.DeliverySyncHttpClient;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpDeliverySyncAdapterApifoxTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void importsWaylineThroughApifoxFormEndpoint() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        when(client.postForm(eq("/map/sdk/v1/groups/group-1/waylines/kml/import"),
                any(), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), anyString(), eq("M-001")))
            .thenReturn(new HttpDeliverySyncAdapter.DjiStandardResponse());

        adapter.importWayline(WaylineImportRequest.builder()
            .missionNo("M-001")
            .waylineId("M-001")
            .kml("<?xml version=\"1.0\"?><kml/>")
            .build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(client).postForm(eq("/map/sdk/v1/groups/group-1/waylines/kml/import"),
            form.capture(), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), anyString(), eq("M-001"));
        assertEquals("M-001", form.getValue().get("wayline_id"));
        assertTrue(form.getValue().get("file").contains("<kml/>"));
    }

    @Test
    void createsTaskWithApifoxTaskPayload() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiCreateTaskResponse response = new HttpDeliverySyncAdapter.DjiCreateTaskResponse();
        HttpDeliverySyncAdapter.DjiCreateTaskResponse.DjiCreateTaskData data =
            new HttpDeliverySyncAdapter.DjiCreateTaskResponse.DjiCreateTaskData();
        data.setId("TASK-001");
        data.setStatus(2);
        response.setData(data);
        when(client.post(eq("/task/sdk/v1/groups/group-1/tasks"), any(),
                eq(HttpDeliverySyncAdapter.DjiCreateTaskResponse.class), anyString(), eq("M-001")))
            .thenReturn(response);

        DeliveryTaskRef ref = adapter.createTask(CreateTaskRequest.builder()
            .deviceSn("SN-001")
            .missionNo("M-001")
            .taskName("火情任务-M-001")
            .remark("fire mission")
            .build());

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/task/sdk/v1/groups/group-1/tasks"),
            body.capture(), eq(HttpDeliverySyncAdapter.DjiCreateTaskResponse.class), anyString(), eq("M-001"));
        String json = objectMapper.writeValueAsString(body.getValue());
        assertTrue(json.contains("\"task_name\":\"火情任务-M-001\""));
        assertTrue(json.contains("\"device_sn\":\"SN-001\""));
        assertTrue(json.contains("\"mission_id\":\"M-001\""));
        assertEquals("TASK-001", ref.getTaskId());
        assertEquals("2", ref.getStatus());
    }

    @Test
    void startsTaskWithApifoxStartEndpointAndRadarQuery() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        when(client.post(eq("/task/sdk/v1/groups/group-1/tasks/TASK-001/start"),
                any(), any(), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), eq(null), eq(null)))
            .thenReturn(new HttpDeliverySyncAdapter.DjiStandardResponse());

        adapter.startTask("TASK-001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq("/task/sdk/v1/groups/group-1/tasks/TASK-001/start"),
            query.capture(), eq(null), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), eq(null), eq(null));
        assertEquals("false", query.getValue().get("ignore_radar_detection"));
    }

    @Test
    void sendsRemoteCommandThroughApifoxCmdEndpoint() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiCreateCmdResponse response = new HttpDeliverySyncAdapter.DjiCreateCmdResponse();
        HttpDeliverySyncAdapter.DjiCreateCmdResponse.DjiCreateCmdData data =
            new HttpDeliverySyncAdapter.DjiCreateCmdResponse.DjiCreateCmdData();
        data.setBid("BID-001");
        data.setDeviceSn("SN-001");
        data.setDeviceCmdMethod("return_home");
        data.setStatus("sent");
        response.setData(data);
        when(client.post(eq("/task/sdk/v1/groups/group-1/cmds"), any(),
                eq(HttpDeliverySyncAdapter.DjiCreateCmdResponse.class), anyString(), eq("M-001")))
            .thenReturn(response);

        DeliveryCommandRef ref = adapter.sendDeviceCommand(DeviceCommandRequest.builder()
            .missionNo("M-001")
            .deviceSn("SN-001")
            .deviceCmdMethod("return_home")
            .deviceCmdData(Map.of())
            .build());

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/task/sdk/v1/groups/group-1/cmds"),
            body.capture(), eq(HttpDeliverySyncAdapter.DjiCreateCmdResponse.class), anyString(), eq("M-001"));
        String json = objectMapper.writeValueAsString(body.getValue());
        assertTrue(json.contains("\"device_sn\":\"SN-001\""));
        assertTrue(json.contains("\"device_cmd_method\":\"return_home\""));
        assertTrue(json.contains("\"device_cmd_data\":{}"));
        assertEquals("BID-001", ref.getBid());
        assertEquals("sent", ref.getStatus());
    }

    private HttpDeliverySyncAdapter adapter(DeliverySyncHttpClient client) {
        DeliverySyncProperties props = new DeliverySyncProperties();
        props.setGroupId("group-1");
        return new HttpDeliverySyncAdapter(client, props);
    }
}
