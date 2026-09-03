package com.yx.uavfire.wayline.agent.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchResultDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineProgressDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineStateChangeDTO;
import com.yx.uavfire.wayline.agent.service.WaylineEventStore;
import com.yx.uavfire.firedetection.FireDetectionService;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaylineAgentEventListenerTest {

    private WaylineEventStore store;
    private WaylineAgentEventListener listener;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @BeforeEach
    void setUp() {
        store = new WaylineEventStore();
        listener = new WaylineAgentEventListener(objectMapper, store);
    }

    private Message<byte[]> messageFor(String topic, String payload) {
        return MessageBuilder.withPayload(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MqttHeaders.RECEIVED_TOPIC, topic)
                .build();
    }

    @Test
    void onEvent_decodesStateChangeAndPersists() {
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-1\",\"msdk_state\":\"EXECUTING\",\"business_state\":\"EXECUTING\","
                + "\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        List<WaylineEventRecord> events = store.getByMission("m-1");
        assertEquals(1, events.size());
        WaylineEventRecord rec = events.get(0);
        assertEquals("SN-A", rec.getDroneSn());
        assertEquals("wayline_state_change", rec.getMethod());
        assertEquals("m-1", rec.getMissionId());
        WaylineStateChangeDTO sc = assertInstanceOf(WaylineStateChangeDTO.class, rec.getData());
        assertEquals("EXECUTING", sc.getMsdkState());
        assertEquals("ENTER_WAYLINE", sc.getPreviousMsdkState());
    }

    @Test
    void onEvent_persistsErrorStateAsFailedTaskStatus() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-error\",\"msdk_state\":\"ERROR\","
                + "\"error\":\"startMission:GPS_INVALID:GPS信号弱，任务暂停\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("failed"));
        assertTrue(params.containsValue("startMission:GPS_INVALID:GPS信号弱，任务暂停"));
    }

    @Test
    void onEvent_decodesProgressIncludingNestedAircraft() {
        String payload = "{\"method\":\"wayline_progress\",\"timestamp\":2000,"
                + "\"data\":{\"mission_id\":\"m-1\",\"mission_file_name\":\"f.kmz\",\"wayline_id\":0,"
                + "\"current_waypoint_index\":5,\"total_waypoints\":12,\"percent\":41,"
                + "\"aircraft\":{\"lat\":22.5,\"lng\":113.9,\"alt_relative\":30.0,\"speed\":5.2,\"yaw\":90.0},"
                + "\"battery_percent\":73,\"rtk_status\":\"FIX\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));

        WaylineProgressDTO p = assertInstanceOf(WaylineProgressDTO.class, store.getByMission("m-1").get(0).getData());
        assertEquals(Integer.valueOf(5), p.getCurrentWaypointIndex());
        assertEquals(Integer.valueOf(12), p.getTotalWaypoints());
        assertEquals(Double.valueOf(22.5), p.getAircraft().getLat());
        assertEquals(Double.valueOf(5.2), p.getAircraft().getSpeed());
        assertEquals(Integer.valueOf(73), p.getBatteryPercent());
        assertEquals("FIX", p.getRtkStatus());
    }

    @Test
    void onEvent_persistsProgressPercentFromStoredWaypointCountWhenAgentOmitsPercent() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        when(mapper.selectOne(any())).thenReturn(PlannedWaylineEntity.builder()
                .flightId("m-progress")
                .waypointsJson("[{\"order\":1},{\"order\":2},{\"order\":3},{\"order\":4},{\"order\":5}]")
                .build());
        String payload = "{\"method\":\"wayline_progress\",\"timestamp\":2000,"
                + "\"data\":{\"mission_id\":\"m-progress\",\"mission_file_name\":\"f.kmz\",\"wayline_id\":0,"
                + "\"current_waypoint_index\":2}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue(2));
        assertTrue(params.containsValue(5));
        assertTrue(params.containsValue(40));
        assertFalse(params.containsValue("executing"));
    }

    @Test
    void onEvent_autoStartsFireDetectionAtFirstWaypointOnlyAfterExecutingState() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        setField(listener, "fireDetectionService", fireDetectionService);
        String executing = "{\"method\":\"wayline_state_change\",\"timestamp\":1900,"
                + "\"data\":{\"mission_id\":\"m-first\",\"msdk_state\":\"EXECUTING\","
                + "\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";
        String payload = "{\"method\":\"wayline_progress\",\"timestamp\":2000,"
                + "\"data\":{\"mission_id\":\"m-first\",\"mission_file_name\":\"f.kmz\",\"wayline_id\":0,"
                + "\"current_waypoint_index\":0,\"total_waypoints\":5}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", executing));
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));

        verify(fireDetectionService).startForDrone(eq("SN-A"));
    }

    @Test
    void onEvent_doesNotAutoStartFireDetectionForEnterWaylineProgress() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        setField(listener, "fireDetectionService", fireDetectionService);
        String payload = "{\"method\":\"wayline_progress\",\"timestamp\":2000,"
                + "\"data\":{\"mission_id\":\"m-entering\",\"mission_file_name\":\"f.kmz\",\"wayline_id\":0,"
                + "\"current_waypoint_index\":0,\"total_waypoints\":5}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));

        verify(fireDetectionService, never()).startForDrone(any());
    }

    @Test
    void onEvent_autoStartsFireDetectionOnlyOnceForSameMissionAndDrone() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        setField(listener, "fireDetectionService", fireDetectionService);
        String executing = "{\"method\":\"wayline_state_change\",\"timestamp\":1900,"
                + "\"data\":{\"mission_id\":\"m-repeat\",\"msdk_state\":\"EXECUTING\","
                + "\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";
        String payload = "{\"method\":\"wayline_progress\",\"timestamp\":2000,"
                + "\"data\":{\"mission_id\":\"m-repeat\",\"mission_file_name\":\"f.kmz\",\"wayline_id\":0,"
                + "\"current_waypoint_index\":0,\"total_waypoints\":5}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", executing));
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));

        verify(fireDetectionService).startForDrone(eq("SN-A"));
    }

    @Test
    void onEvent_doesNotAutoStartFireDetectionForNonFirstWaypoint() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        setField(listener, "fireDetectionService", fireDetectionService);
        String payload = "{\"method\":\"wayline_progress\",\"timestamp\":2000,"
                + "\"data\":{\"mission_id\":\"m-second\",\"mission_file_name\":\"f.kmz\",\"wayline_id\":0,"
                + "\"current_waypoint_index\":1,\"total_waypoints\":5}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", payload));

        verify(fireDetectionService, never()).startForDrone(any());
    }

    @Test
    void onEvent_autoStopsFireDetectionWhenWaylineFinished() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        org.mockito.Mockito.when(fireDetectionService.isActiveForDrone(eq("SN-A"))).thenReturn(true);
        setField(listener, "fireDetectionService", fireDetectionService);
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":3000,"
                + "\"data\":{\"mission_id\":\"m-done\",\"msdk_state\":\"FINISHED\","
                + "\"previous_msdk_state\":\"EXECUTING\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        verify(fireDetectionService).stopForDrone(eq("SN-A"));
    }

    @Test
    void onEvent_autoStopsFireDetectionOnlyOnceForSameMission() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        org.mockito.Mockito.when(fireDetectionService.isActiveForDrone(eq("SN-A"))).thenReturn(true);
        setField(listener, "fireDetectionService", fireDetectionService);
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":3000,"
                + "\"data\":{\"mission_id\":\"m-done-once\",\"msdk_state\":\"FINISHED\","
                + "\"previous_msdk_state\":\"EXECUTING\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        verify(fireDetectionService).stopForDrone(eq("SN-A"));
    }

    @Test
    void onEvent_doesNotAutoStopFireDetectionWhenNotActive() throws Exception {
        FireDetectionService fireDetectionService = mock(FireDetectionService.class);
        org.mockito.Mockito.when(fireDetectionService.isActiveForDrone(eq("SN-A"))).thenReturn(false);
        setField(listener, "fireDetectionService", fireDetectionService);
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":3000,"
                + "\"data\":{\"mission_id\":\"m-done-inactive\",\"msdk_state\":\"FINISHED\","
                + "\"previous_msdk_state\":\"EXECUTING\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        verify(fireDetectionService, never()).stopForDrone(any());
    }

    @Test
    void onEvent_persistsReadyAfterExecutingAsFinishedWithFullProgress() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-finished\",\"msdk_state\":\"READY\","
                + "\"previous_msdk_state\":\"EXECUTING\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("finished"));
        assertTrue(params.containsValue(100));
    }

    @Test
    void onEvent_persistsFinishedWithoutExecutingAsFailedWithReason() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        // 进入航线后直接 FINISHED（从未经历 EXECUTING）→ 飞机实际没飞起来（如电量不足）。
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-noexec\",\"msdk_state\":\"FINISHED\","
                + "\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("failed"));
        assertTrue(params.containsValue(WaylineAgentEventListener.NO_EXECUTE_FINISH_REASON));
        assertFalse(params.containsValue("finished"));
    }

    @Test
    void onEvent_progressAtWaypointZeroDoesNotTurnEnterWaylineFinishedIntoSuccess() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        when(mapper.selectOne(any())).thenReturn(PlannedWaylineEntity.builder()
                .flightId("m-incident").taskProgress(33).currentWaypointIndex(0).totalWaypoints(3).build());
        String progress = "{\"method\":\"wayline_progress\",\"timestamp\":900,"
                + "\"data\":{\"mission_id\":\"m-incident\",\"current_waypoint_index\":0,"
                + "\"total_waypoints\":3}}";
        String finished = "{\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-incident\",\"msdk_state\":\"FINISHED\","
                + "\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_progress", progress));
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", finished));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        Map<String, Object> progressParams = captor.getAllValues().get(0).getParamNameValuePairs();
        Map<String, Object> terminalParams = captor.getAllValues().get(1).getParamNameValuePairs();
        assertTrue(progressParams.containsValue(0));
        assertFalse(progressParams.containsValue(33));
        assertTrue(terminalParams.containsValue("failed"));
        assertTrue(terminalParams.containsValue(WaylineAgentEventListener.NO_EXECUTE_FINISH_REASON));
        assertFalse(terminalParams.containsValue("finished"));
    }

    @Test
    void onEvent_trustsBusinessCompletedDespiteEnterWaylineFinished() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        // MQTT 乱序：state_change(FINISHED) 早于 EXECUTING 到达，但 agent 已明确报 completed → 信任，不误判 failed。
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-ooo\",\"business_state\":\"completed\","
                + "\"msdk_state\":\"FINISHED\",\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("finished"));
        assertFalse(params.containsValue("failed"));
        assertFalse(params.containsValue(WaylineAgentEventListener.NO_EXECUTE_FINISH_REASON));
    }

    @Test
    void onEvent_doesNotFlagFinishedAsFailedWhenProgressAlreadyRecorded() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        // 该任务此前已落过执行进度 → 确实飞过；即便 FINISHED 的 previous 看似未执行也不误判 failed。
        when(mapper.selectOne(any())).thenReturn(PlannedWaylineEntity.builder()
                .flightId("m-flew").taskProgress(50).currentWaypointIndex(1).build());
        String payload = "{\"tid\":\"t-1\",\"method\":\"wayline_state_change\",\"timestamp\":1000,"
                + "\"data\":{\"mission_id\":\"m-flew\",\"msdk_state\":\"FINISHED\","
                + "\"previous_msdk_state\":\"ENTER_WAYLINE\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertFalse(params.containsValue("failed"));
        assertFalse(params.containsValue(WaylineAgentEventListener.NO_EXECUTE_FINISH_REASON));
    }

    @Test
    void onEvent_decodesDispatchResult() {
        String payload = "{\"method\":\"wayline_dispatch_result\","
                + "\"data\":{\"mission_id\":\"m-1\",\"result\":0,\"reason\":\"kmz-md5:abc\","
                + "\"msdk_mission_file_name\":\"f.kmz\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_dispatch_result", payload));

        WaylineDispatchResultDTO dr = assertInstanceOf(WaylineDispatchResultDTO.class, store.getByMission("m-1").get(0).getData());
        assertEquals(Integer.valueOf(0), dr.getResult());
        assertEquals("kmz-md5:abc", dr.getReason());
        assertEquals("f.kmz", dr.getMsdkMissionFileName());
    }

    @Test
    void onEvent_persistsSuccessfulDispatchResultAsExecutingTaskStatus() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        String payload = "{\"method\":\"wayline_dispatch_result\","
                + "\"data\":{\"mission_id\":\"m-dispatch\",\"result\":0,\"reason\":\"kmz-md5:abc\","
                + "\"msdk_mission_file_name\":\"m-dispatch.kmz\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_dispatch_result", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("executing"));
        assertTrue(params.containsValue(0));
    }

    @Test
    void onEvent_persistsFailedDispatchResultAsFailedTaskStatus() throws Exception {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        setField(listener, "plannedWaylineMapper", mapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
        String payload = "{\"method\":\"wayline_dispatch_result\","
                + "\"data\":{\"mission_id\":\"m-dispatch-failed\",\"result\":2004,"
                + "\"msdk_error_msg\":\"pushKmz:GPS_INVALID:GPS信号弱\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_dispatch_result", payload));

        ArgumentCaptor<LambdaUpdateWrapper<PlannedWaylineEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("failed"));
        assertTrue(params.containsValue("pushKmz:GPS_INVALID:GPS信号弱"));
    }

    @Test
    void onEvent_storesUnknownMethodAsRawJsonNode() {
        String payload = "{\"method\":\"wayline_action\","
                + "\"data\":{\"mission_id\":\"m-1\",\"action_type\":\"takePhoto\",\"phase\":\"START\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_action", payload));

        Object data = store.getByMission("m-1").get(0).getData();
        JsonNode node = assertInstanceOf(JsonNode.class, data);
        assertEquals("takePhoto", node.path("action_type").asText());
        assertEquals("START", node.path("phase").asText());
    }

    @Test
    void onEvent_dropsPayloadWhenTopicDoesNotMatch() {
        listener.onEvent(messageFor("thing/product/SN-A/events/foo",
                "{\"method\":\"wayline_state_change\",\"data\":{\"mission_id\":\"m-1\"}}"));

        assertTrue(store.getByMission("m-1").isEmpty());
    }

    @Test
    void onEvent_dropsPayloadWhenMissingData() {
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change",
                "{\"method\":\"wayline_state_change\"}"));

        assertTrue(store.getByMission("any").isEmpty());
    }

    @Test
    void onEvent_dropsPayloadWhenJsonInvalid() {
        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_state_change",
                "not-json"));

        assertTrue(store.getByMission("any").isEmpty());
    }

    @Test
    void onEvent_dropsPayloadWhenTopicHeaderMissing() {
        Message<byte[]> message = MessageBuilder
                .withPayload("{\"method\":\"wayline_state_change\",\"data\":{}}".getBytes(StandardCharsets.UTF_8))
                .build();

        listener.onEvent(message);

        assertTrue(store.getByMission("any").isEmpty());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
