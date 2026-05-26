package com.yx.uavfire.fc100.deliverysync.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.http.DeliverySyncHttpClient;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;

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
    void importsWaylineThroughApifoxBinaryFileEndpoint() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiImportWaylineResponse response =
            new HttpDeliverySyncAdapter.DjiImportWaylineResponse();
        HttpDeliverySyncAdapter.DjiImportWaylineResponse.DjiImportWaylineData data =
            new HttpDeliverySyncAdapter.DjiImportWaylineResponse.DjiImportWaylineData();
        data.setUuid("IMPORTED-WAYLINE-UUID");
        data.setName("template");
        data.setKey("object-key");
        response.setData(data);
        when(client.postMultipartFile(eq("/map/sdk/v1/groups/group-1/waylines/kml/import"),
                eq("file"), eq("template.kml"), any(), eq("application/vnd.google-earth.kml+xml"),
                any(), eq(HttpDeliverySyncAdapter.DjiImportWaylineResponse.class), anyString(), eq("M-001")))
            .thenReturn(response);

        byte[] kml = "<?xml version=\"1.0\"?><kml/>".getBytes();
        DeliveryWaylineImportResult result = adapter.importWayline(WaylineImportRequest.builder()
            .missionNo("M-001")
            .waylineId("M-001")
            .filename("template.kml")
            .contentType("application/vnd.google-earth.kml+xml")
            .fileBytes(kml)
            .build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(client).postMultipartFile(eq("/map/sdk/v1/groups/group-1/waylines/kml/import"),
            eq("file"), eq("template.kml"), eq(kml), eq("application/vnd.google-earth.kml+xml"),
            form.capture(), eq(HttpDeliverySyncAdapter.DjiImportWaylineResponse.class), anyString(), eq("M-001"));
        assertEquals("M-001", form.getValue().get("wayline_id"));
        assertEquals("IMPORTED-WAYLINE-UUID", result.getWaylineId());
        assertEquals("template", result.getName());
        assertEquals("object-key", result.getKey());
    }

    @Test
    void importsNewWaylineWithoutEditWaylineIdWhenNotProvided() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiImportWaylineResponse response =
            new HttpDeliverySyncAdapter.DjiImportWaylineResponse();
        HttpDeliverySyncAdapter.DjiImportWaylineResponse.DjiImportWaylineData data =
            new HttpDeliverySyncAdapter.DjiImportWaylineResponse.DjiImportWaylineData();
        data.setUuid("IMPORTED-WAYLINE-UUID");
        response.setData(data);
        when(client.postMultipartFile(eq("/map/sdk/v1/groups/group-1/waylines/kml/import"),
                eq("file"), eq("template.kml"), any(), eq("application/vnd.google-earth.kml+xml"),
                any(), eq(HttpDeliverySyncAdapter.DjiImportWaylineResponse.class), anyString(), eq("M-001")))
            .thenReturn(response);

        byte[] kml = "<?xml version=\"1.0\"?><kml/>".getBytes();
        DeliveryWaylineImportResult result = adapter.importWayline(WaylineImportRequest.builder()
            .missionNo("M-001")
            .filename("template.kml")
            .contentType("application/vnd.google-earth.kml+xml")
            .fileBytes(kml)
            .build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(client).postMultipartFile(eq("/map/sdk/v1/groups/group-1/waylines/kml/import"),
            eq("file"), eq("template.kml"), eq(kml), eq("application/vnd.google-earth.kml+xml"),
            form.capture(), eq(HttpDeliverySyncAdapter.DjiImportWaylineResponse.class), anyString(), eq("M-001"));
        assertEquals(false, form.getValue().containsKey("wayline_id"));
        assertEquals("IMPORTED-WAYLINE-UUID", result.getWaylineId());
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
        HttpDeliverySyncAdapter.DjiStandardResponse response = new HttpDeliverySyncAdapter.DjiStandardResponse();
        response.setCode(0);
        response.setMessage("OK");
        when(client.post(eq("/task/sdk/v1/groups/group-1/tasks/TASK-001/start"),
                any(), any(), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), eq(null), eq(null)))
            .thenReturn(response);

        DeliveryTaskOperationResult result = adapter.startTask("TASK-001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq("/task/sdk/v1/groups/group-1/tasks/TASK-001/start"),
            query.capture(), eq(null), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), eq(null), eq(null));
        assertEquals("false", query.getValue().get("ignore_radar_detection"));
        assertEquals(true, result.getAccepted());
        assertEquals("OK", result.getApiMessage());
        assertTrue(result.getDisplayMessage().contains("TASK-001"));
    }

    @Test
    void startTaskReturnsFc100BusinessMessageWhenNetworkCheckFails() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiStandardResponse response = new HttpDeliverySyncAdapter.DjiStandardResponse();
        response.setCode(204027);
        response.setMessage("设备网络检查失败");
        when(client.post(eq("/task/sdk/v1/groups/group-1/tasks/TASK-001/start"),
                any(), any(), eq(HttpDeliverySyncAdapter.DjiStandardResponse.class), eq(null), eq(null)))
            .thenReturn(response);

        DeliveryTaskOperationResult result = adapter.startTask("TASK-001");

        assertEquals(false, result.getAccepted());
        assertEquals(204027, result.getApiCode());
        assertEquals("设备网络检查失败", result.getApiMessage());
        assertEquals("设备网络检查失败", result.getDisplayMessage());
    }

    @Test
    void taskStatusDisplaysAbnormalEndedTaskCode() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiTaskListResponse response = new HttpDeliverySyncAdapter.DjiTaskListResponse();
        response.setCode(0);
        response.setMessage("OK");
        HttpDeliverySyncAdapter.DjiTaskListResponse.DjiTaskListData data =
            new HttpDeliverySyncAdapter.DjiTaskListResponse.DjiTaskListData();
        HttpDeliverySyncAdapter.DjiTaskListResponse.DjiTaskItem item =
            new HttpDeliverySyncAdapter.DjiTaskListResponse.DjiTaskItem();
        item.setId("TASK-001");
        item.setStatus(6);
        item.setCode(620179);
        item.setReason("");
        item.setStartTime(1779810928747L);
        item.setEndTime(1779811040304L);
        data.setList(new HttpDeliverySyncAdapter.DjiTaskListResponse.DjiTaskItem[] { item });
        response.setData(data);
        when(client.get(eq("/task/sdk/v1/groups/group-1/tasks"), any(),
                eq(HttpDeliverySyncAdapter.DjiTaskListResponse.class), eq(null), eq(null)))
            .thenReturn(response);

        DeliveryTaskStatus status = adapter.queryTaskStatus("TASK-001");

        assertEquals("abnormal", status.getPhase());
        assertEquals(620179, status.getTaskCode());
        assertTrue(status.getDisplayMessage().contains("异常结束"));
        assertTrue(status.getDisplayMessage().contains("620179"));
        assertTrue(status.getDisplayMessage().contains("右前机臂没有在位"));
    }

    @Test
    void listsWaylinesThroughApifoxWaylineEndpoint() throws Exception {
        DeliverySyncHttpClient client = mock(DeliverySyncHttpClient.class);
        HttpDeliverySyncAdapter adapter = adapter(client);
        HttpDeliverySyncAdapter.DjiWaylineListResponse response = new HttpDeliverySyncAdapter.DjiWaylineListResponse();
        HttpDeliverySyncAdapter.DjiWaylineListResponse.DjiWaylineListData data =
            new HttpDeliverySyncAdapter.DjiWaylineListResponse.DjiWaylineListData();
        HttpDeliverySyncAdapter.DjiWaylineListResponse.DjiWaylineItem item =
            new HttpDeliverySyncAdapter.DjiWaylineListResponse.DjiWaylineItem();
        item.setWaylineId("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3");
        item.setName("2026-05-26 17:49");
        item.setWaylineType("waypoint");
        item.setDistance(141.55);
        item.setDuration(14);
        item.setFinishAction("goHome");
        data.setList(new HttpDeliverySyncAdapter.DjiWaylineListResponse.DjiWaylineItem[] { item });
        response.setData(data);
        when(client.get(eq("/map/sdk/v1/groups/group-1/waylines"), any(),
                eq(HttpDeliverySyncAdapter.DjiWaylineListResponse.class), eq(null), eq(null)))
            .thenReturn(response);

        List<DeliveryWaylineDTO> waylines = adapter.listWaylines(1, 10, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> query = ArgumentCaptor.forClass(Map.class);
        verify(client).get(eq("/map/sdk/v1/groups/group-1/waylines"),
            query.capture(), eq(HttpDeliverySyncAdapter.DjiWaylineListResponse.class), eq(null), eq(null));
        assertEquals("1", query.getValue().get("page"));
        assertEquals("10", query.getValue().get("page_size"));
        assertEquals("5d0bbfcb-6043-45c4-9b29-80f1e59aa4e3", waylines.get(0).getWaylineId());
        assertEquals("waypoint", waylines.get(0).getWaylineType());
        assertEquals(141.55, waylines.get(0).getDistance());
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
