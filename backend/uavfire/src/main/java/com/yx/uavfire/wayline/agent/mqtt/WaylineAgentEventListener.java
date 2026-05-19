package com.yx.uavfire.wayline.agent.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchResultDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineProgressDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineStateChangeDTO;
import com.yx.uavfire.wayline.agent.service.WaylineEventStore;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IPlannedWaylineMapper plannedWaylineMapper;

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

        // P2.b: 把 progress / state_change 事件同步持久化到 planned_wayline (flightId = missionId)
        if (plannedWaylineMapper != null && missionId != null && !missionId.isEmpty()) {
            try {
                if (decoded instanceof WaylineProgressDTO) {
                    persistProgress(missionId, (WaylineProgressDTO) decoded);
                } else if (decoded instanceof WaylineStateChangeDTO) {
                    persistStateChange(missionId, (WaylineStateChangeDTO) decoded);
                }
            } catch (RuntimeException persistErr) {
                log.warn("wayline-agent persist to planned_wayline failed mission={}: {}", missionId, persistErr.getMessage());
            }
        }
    }

    private void persistProgress(String missionId, WaylineProgressDTO pr) {
        LambdaUpdateWrapper<PlannedWaylineEntity> update = new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getFlightId, missionId)
                .set(PlannedWaylineEntity::getLastProgressTime, System.currentTimeMillis());
        if (pr.getPercent() != null) {
            update.set(PlannedWaylineEntity::getTaskProgress, pr.getPercent());
        }
        if (pr.getCurrentWaypointIndex() != null) {
            update.set(PlannedWaylineEntity::getCurrentWaypointIndex, pr.getCurrentWaypointIndex());
        }
        if (pr.getTotalWaypoints() != null) {
            update.set(PlannedWaylineEntity::getTotalWaypoints, pr.getTotalWaypoints());
        }
        plannedWaylineMapper.update(null, update);
    }

    private void persistStateChange(String missionId, WaylineStateChangeDTO sc) {
        String mappedStatus = mapBusinessState(sc.getBusinessState());
        LambdaUpdateWrapper<PlannedWaylineEntity> update = new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getFlightId, missionId)
                .set(PlannedWaylineEntity::getLastProgressTime, System.currentTimeMillis());
        if (mappedStatus != null) {
            update.set(PlannedWaylineEntity::getTaskStatus, mappedStatus);
        }
        if (sc.getError() != null && !sc.getError().isEmpty()) {
            update.set(PlannedWaylineEntity::getTaskStatusReason, sc.getError());
        }
        plannedWaylineMapper.update(null, update);
    }

    /** Agent businessState → planned_wayline.task_status (contract 6.1). */
    private static String mapBusinessState(String businessState) {
        if (businessState == null) return null;
        switch (businessState) {
            case "Dispatching": return "publishing";
            case "Ready":       return "ready";
            case "Executing":   return "executing";
            case "Paused":      return "paused";
            case "Stopped":     return "stopped";
            case "Completed":   return "finished";
            case "Error":       return "failed";
            default:            return null;
        }
    }
}
