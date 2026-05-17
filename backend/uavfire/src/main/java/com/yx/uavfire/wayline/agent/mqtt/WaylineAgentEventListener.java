package com.yx.uavfire.wayline.agent.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchResultDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineProgressDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineStateChangeDTO;
import com.yx.uavfire.wayline.agent.service.WaylineEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes inbound wayline agent events arriving on
 * {@code uavfire/agent/{droneSn}/events/{method}} into typed DTOs and
 * appends them to {@link WaylineEventStore} for replay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaylineAgentEventListener {

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^uavfire/agent/([^/]+)/events/([^/]+)$");

    public static final String METHOD_STATE_CHANGE = "wayline_state_change";
    public static final String METHOD_PROGRESS = "wayline_progress";
    public static final String METHOD_ACTION = "wayline_action";
    public static final String METHOD_DISPATCH_RESULT = "wayline_dispatch_result";

    private final ObjectMapper objectMapper;
    private final WaylineEventStore eventStore;

    @ServiceActivator(inputChannel = WaylineAgentMqttChannel.INBOUND)
    public void onEvent(Message<byte[]> message) {
        String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        if (topic == null) {
            log.warn("wayline-agent event missing topic, dropping");
            return;
        }
        Matcher m = TOPIC_PATTERN.matcher(topic);
        if (!m.matches()) {
            log.warn("wayline-agent event topic does not match expected pattern: {}", topic);
            return;
        }
        String droneSn = m.group(1);
        String methodFromTopic = m.group(2);

        JsonNode root;
        try {
            root = objectMapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            log.warn("wayline-agent event payload parse failed topic={}: {}", topic, e.getMessage());
            return;
        }

        String method = root.path("method").asText(methodFromTopic);
        JsonNode dataNode = root.get("data");
        if (dataNode == null) {
            log.warn("wayline-agent event missing data block method={} drone={}", method, droneSn);
            return;
        }

        Object decoded;
        String missionId;
        try {
            switch (method) {
                case METHOD_STATE_CHANGE:
                    WaylineStateChangeDTO sc = objectMapper.treeToValue(dataNode, WaylineStateChangeDTO.class);
                    decoded = sc;
                    missionId = sc.getMissionId();
                    break;
                case METHOD_PROGRESS:
                    WaylineProgressDTO pr = objectMapper.treeToValue(dataNode, WaylineProgressDTO.class);
                    decoded = pr;
                    missionId = pr.getMissionId();
                    break;
                case METHOD_DISPATCH_RESULT:
                    WaylineDispatchResultDTO dr = objectMapper.treeToValue(dataNode, WaylineDispatchResultDTO.class);
                    decoded = dr;
                    missionId = dr.getMissionId();
                    break;
                case METHOD_ACTION:
                default:
                    // Action and unknown methods stored as raw JSON for forward-compat
                    decoded = dataNode;
                    missionId = dataNode.path("missionId").asText(dataNode.path("mission_id").asText(null));
                    break;
            }
        } catch (JsonProcessingException e) {
            log.warn("wayline-agent event decode failed method={} drone={}: {}", method, droneSn, e.getMessage());
            return;
        }

        WaylineEventRecord record = new WaylineEventRecord()
                .setDroneSn(droneSn)
                .setMissionId(missionId)
                .setMethod(method)
                .setReceivedAt(System.currentTimeMillis())
                .setData(decoded);
        eventStore.append(record);
        log.debug("wayline-agent event stored drone={} method={} mission={}", droneSn, method, missionId);
    }
}
