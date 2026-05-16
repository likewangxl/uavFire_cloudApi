package com.yx.uavfire.wayline.agent.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchResultDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineProgressDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineStateChangeDTO;
import com.yx.uavfire.wayline.agent.service.WaylineEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void onEvent_decodesDispatchResult() {
        String payload = "{\"method\":\"wayline_dispatch_result\","
                + "\"data\":{\"mission_id\":\"m-1\",\"result\":0,\"msdk_mission_file_name\":\"f.kmz\"}}";

        listener.onEvent(messageFor("uavfire/agent/SN-A/events/wayline_dispatch_result", payload));

        WaylineDispatchResultDTO dr = assertInstanceOf(WaylineDispatchResultDTO.class, store.getByMission("m-1").get(0).getData());
        assertEquals(Integer.valueOf(0), dr.getResult());
        assertEquals("f.kmz", dr.getMsdkMissionFileName());
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
}
