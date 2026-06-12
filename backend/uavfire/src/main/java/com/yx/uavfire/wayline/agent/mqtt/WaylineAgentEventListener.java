package com.yx.uavfire.wayline.agent.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yx.uavfire.firedetection.FireDetectionService;
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
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<String> fireDetectionTriggeredMissions = ConcurrentHashMap.newKeySet();
    private final Set<String> fireDetectionStoppedMissions = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IPlannedWaylineMapper plannedWaylineMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FireDetectionService fireDetectionService;

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
                } else if (decoded instanceof WaylineDispatchResultDTO) {
                    persistDispatchResult(missionId, (WaylineDispatchResultDTO) decoded);
                }
            } catch (RuntimeException persistErr) {
                log.warn("wayline-agent persist to planned_wayline failed mission={}: {}", missionId, persistErr.getMessage());
            }
        }
        if (decoded instanceof WaylineProgressDTO) {
            autoStartFireDetectionAtFirstWaypoint(droneSn, missionId, (WaylineProgressDTO) decoded);
        } else if (decoded instanceof WaylineStateChangeDTO) {
            autoStopFireDetectionOnTerminal(droneSn, missionId, (WaylineStateChangeDTO) decoded);
        }
    }

    private void autoStartFireDetectionAtFirstWaypoint(String droneSn, String missionId, WaylineProgressDTO progress) {
        if (fireDetectionService == null
                || !StringUtils.hasText(droneSn)
                || !StringUtils.hasText(missionId)
                || progress.getCurrentWaypointIndex() == null
                || progress.getCurrentWaypointIndex() != 0) {
            return;
        }
        String triggerKey = missionId + ":" + droneSn;
        if (!fireDetectionTriggeredMissions.add(triggerKey)) {
            return;
        }
        try {
            boolean ok = fireDetectionService.startForDrone(droneSn);
            if (ok) {
                log.info("wayline first waypoint auto-started fire detection mission={} drone={}", missionId, droneSn);
            } else {
                log.warn("wayline first waypoint fire detection auto-start failed mission={} drone={}", missionId, droneSn);
            }
        } catch (RuntimeException ex) {
            log.warn("wayline first waypoint fire detection auto-start exception mission={} drone={}: {}",
                    missionId, droneSn, ex.getMessage());
        }
    }

    // 航线终态（执行完成/返航完成→finished，或失败/停止）后自动关闭火情监测，并把相机切回可见光。
    // 与 autoStart 对称，按 mission 去重，避免同一任务多次 state_change 事件重复下发停止。
    private void autoStopFireDetectionOnTerminal(String droneSn, String missionId, WaylineStateChangeDTO sc) {
        if (fireDetectionService == null
                || !StringUtils.hasText(droneSn)
                || !StringUtils.hasText(missionId)) {
            return;
        }
        String status = mapBusinessState(sc.getBusinessState());
        if (status == null) {
            status = mapMsdkState(sc.getMsdkState(), sc.getPreviousMsdkState());
        }
        if (!isTerminalStatus(status)) {
            return;
        }
        String triggerKey = missionId + ":" + droneSn;
        if (!fireDetectionStoppedMissions.add(triggerKey)) {
            return;
        }
        fireDetectionTriggeredMissions.remove(triggerKey);
        if (!fireDetectionService.isActiveForDrone(droneSn)) {
            return;
        }
        try {
            boolean ok = fireDetectionService.stopForDrone(droneSn);
            if (ok) {
                log.info("wayline terminal auto-stopped fire detection mission={} drone={} status={}", missionId, droneSn, status);
            } else {
                log.warn("wayline terminal fire detection auto-stop failed mission={} drone={}", missionId, droneSn);
            }
        } catch (RuntimeException ex) {
            log.warn("wayline terminal fire detection auto-stop exception mission={} drone={}: {}",
                    missionId, droneSn, ex.getMessage());
        }
    }

    private void persistProgress(String missionId, WaylineProgressDTO pr) {
        Optional<PlannedWaylineEntity> existing = findPlannedWaylineByFlightId(missionId);
        Integer totalWaypoints = firstPositive(pr.getTotalWaypoints(),
                existing.map(PlannedWaylineEntity::getTotalWaypoints).orElse(null),
                existing.map(entity -> countWaypoints(entity.getWaypointsJson())).orElse(null));
        Integer percent = pr.getPercent();
        if (percent == null) {
            percent = deriveProgressPercent(pr.getCurrentWaypointIndex(), totalWaypoints);
        }

        LambdaUpdateWrapper<PlannedWaylineEntity> update = new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getFlightId, missionId)
                .set(PlannedWaylineEntity::getLastProgressTime, System.currentTimeMillis());
        if (percent != null) {
            update.set(PlannedWaylineEntity::getTaskProgress, percent);
        }
        if (pr.getCurrentWaypointIndex() != null) {
            update.set(PlannedWaylineEntity::getCurrentWaypointIndex, pr.getCurrentWaypointIndex());
        }
        if (totalWaypoints != null) {
            update.set(PlannedWaylineEntity::getTotalWaypoints, totalWaypoints);
        }
        if (!isTerminalStatus(existing.map(PlannedWaylineEntity::getTaskStatus).orElse(null))) {
            update.set(PlannedWaylineEntity::getStatus, "executing");
            update.set(PlannedWaylineEntity::getTaskStatus, "executing");
        }
        plannedWaylineMapper.update(null, update);
    }

    private void persistDispatchResult(String missionId, WaylineDispatchResultDTO dr) {
        long now = System.currentTimeMillis();
        Integer result = dr.getResult();
        boolean ok = result != null && result == 0;
        LambdaUpdateWrapper<PlannedWaylineEntity> update = new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getFlightId, missionId)
                .set(PlannedWaylineEntity::getLastProgressTime, now)
                .set(PlannedWaylineEntity::getUpdateTime, now);
        if (ok) {
            update.set(PlannedWaylineEntity::getStatus, "executing");
            update.set(PlannedWaylineEntity::getTaskStatus, "executing");
            update.set(PlannedWaylineEntity::getTaskProgress, 0);
            update.set(PlannedWaylineEntity::getTaskStatusReason, null);
        } else {
            update.set(PlannedWaylineEntity::getStatus, "failed");
            update.set(PlannedWaylineEntity::getTaskStatus, "failed");
            update.set(PlannedWaylineEntity::getTaskStatusReason, dispatchFailureReason(dr));
        }
        plannedWaylineMapper.update(null, update);
    }

    /** 航线“真正飞过”才经历的 MSDK 状态（ENTER_WAYLINE 只是进入、尚未执行，不算）。 */
    static final String NO_EXECUTE_FINISH_REASON = "航线未进入执行，可能电量不足或未起飞";

    private void persistStateChange(String missionId, WaylineStateChangeDTO sc) {
        String mappedStatus = mapBusinessState(sc.getBusinessState());
        if (mappedStatus == null) {
            mappedStatus = mapMsdkState(sc.getMsdkState(), sc.getPreviousMsdkState());
        }
        // 任务“完成”但 previous 从未经历真正执行态（如 ENTER_WAYLINE 直接 FINISHED）→ 飞机
        // 实际没飞起来（常见：电量不足/未起飞）。判为 failed 并给原因，避免误显示“完成”或
        // 永远卡在“执行中”。
        // 仅凭单条事件的 previousMsdkState 推断会被 MQTT 乱序坑到（FINISHED 早于 progress/EXECUTING
        // 到达时把真实跑过的任务误判 failed）。两道兜底：agent 明确报 completed 则信任；或该任务此前
        // 已落过执行进度（说明确实飞过）→ 都不翻转。
        String noExecuteReason = null;
        boolean businessSaysCompleted = "finished".equals(mapBusinessState(sc.getBusinessState()));
        if (isFinishedMsdkState(sc.getMsdkState()) && !hadFlownState(sc.getPreviousMsdkState())
                && !businessSaysCompleted && !hasEverExecuted(missionId)) {
            mappedStatus = "failed";
            noExecuteReason = NO_EXECUTE_FINISH_REASON;
        }
        if ((sc.getError() != null && !sc.getError().isEmpty())
                || "failed".equals(mappedStatus)) {
            mappedStatus = "failed";
        }
        long now = System.currentTimeMillis();
        LambdaUpdateWrapper<PlannedWaylineEntity> update = new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getFlightId, missionId)
                .set(PlannedWaylineEntity::getLastProgressTime, now)
                .set(PlannedWaylineEntity::getUpdateTime, now);
        if (mappedStatus != null) {
            update.set(PlannedWaylineEntity::getStatus, mappedStatus);
            update.set(PlannedWaylineEntity::getTaskStatus, mappedStatus);
            if ("finished".equals(mappedStatus)) {
                update.set(PlannedWaylineEntity::getTaskProgress, 100);
                update.set(PlannedWaylineEntity::getTaskStatusReason, null);
            }
        }
        if (sc.getError() != null && !sc.getError().isEmpty()) {
            update.set(PlannedWaylineEntity::getTaskStatusReason, sc.getError());
        } else if (noExecuteReason != null) {
            update.set(PlannedWaylineEntity::getTaskStatusReason, noExecuteReason);
        }
        plannedWaylineMapper.update(null, update);
    }

    /** Agent businessState → planned_wayline.task_status (contract 6.1). */
    private static String mapBusinessState(String businessState) {
        if (businessState == null) return null;
        switch (businessState.toLowerCase()) {
            case "dispatching": return "publishing";
            case "ready":       return "ready";
            case "executing":   return "executing";
            case "paused":      return "paused";
            case "stopped":     return "stopped";
            case "completed":   return "finished";
            case "error":       return "failed";
            default:            return null;
        }
    }

    private static String mapMsdkState(String msdkState, String previousMsdkState) {
        if (msdkState == null) return null;
        switch (msdkState.toUpperCase()) {
            case "ERROR":
            case "FAILED":
                return "failed";
            case "EXECUTING":
            case "ENTER_WAYLINE":
            case "RECOVERING":
            case "RETURN_TO_START_POINT":
                return "executing";
            case "INTERRUPTED":
                return "broken";
            case "PAUSED":
                return "paused";
            case "STOPPED":
                return "stopped";
            case "FINISHED":
            case "COMPLETED":
                return "finished";
            case "READY":
            case "IDLE":
                return wasActiveMsdkState(previousMsdkState) ? "finished" : null;
            default:
                return null;
        }
    }

    private Optional<PlannedWaylineEntity> findPlannedWaylineByFlightId(String missionId) {
        try {
            return Optional.ofNullable(plannedWaylineMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlannedWaylineEntity>()
                            .eq(PlannedWaylineEntity::getFlightId, missionId)));
        } catch (RuntimeException e) {
            log.debug("wayline-agent lookup planned_wayline by flightId failed mission={}: {}", missionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 该任务此前是否落过执行进度（taskProgress>0 或越过首航点）→ 说明飞机确实飞过。 */
    private boolean hasEverExecuted(String missionId) {
        return findPlannedWaylineByFlightId(missionId)
                .map(e -> (e.getTaskProgress() != null && e.getTaskProgress() > 0)
                        || (e.getCurrentWaypointIndex() != null && e.getCurrentWaypointIndex() > 0))
                .orElse(false);
    }

    private static Integer deriveProgressPercent(Integer currentWaypointIndex, Integer totalWaypoints) {
        if (currentWaypointIndex == null || totalWaypoints == null || totalWaypoints <= 0) {
            return null;
        }
        int completed = Math.max(0, currentWaypointIndex + 1);
        int percent = (int) Math.round(Math.min(completed, totalWaypoints) * 100.0 / totalWaypoints);
        return Math.max(0, Math.min(100, percent));
    }

    private Integer countWaypoints(String waypointsJson) {
        if (!StringUtils.hasText(waypointsJson)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(waypointsJson);
            return node.isArray() ? node.size() : null;
        } catch (JsonProcessingException e) {
            log.debug("wayline-agent count waypoints failed: {}", e.getMessage());
            return null;
        }
    }

    private static Integer firstPositive(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static boolean isTerminalStatus(String status) {
        if (status == null) {
            return false;
        }
        switch (status.toLowerCase()) {
            case "finished":
            case "completed":
            case "failed":
            case "stopped":
            case "canceled":
                return true;
            default:
                return false;
        }
    }

    private static boolean isFinishedMsdkState(String msdkState) {
        if (msdkState == null) {
            return false;
        }
        switch (msdkState.toUpperCase()) {
            case "FINISHED":
            case "COMPLETED":
                return true;
            default:
                return false;
        }
    }

    /** previous 是否经历了“真正在飞”的执行态（ENTER_WAYLINE 只是进入、还没执行，不算）。 */
    private static boolean hadFlownState(String previousMsdkState) {
        if (previousMsdkState == null) {
            return false;
        }
        switch (previousMsdkState.toUpperCase()) {
            case "EXECUTING":
            case "RECOVERING":
            case "RETURN_TO_START_POINT":
            case "PAUSED":
            case "INTERRUPTED":
                return true;
            default:
                return false;
        }
    }

    private static boolean wasActiveMsdkState(String previousMsdkState) {
        if (previousMsdkState == null) {
            return false;
        }
        switch (previousMsdkState.toUpperCase()) {
            case "EXECUTING":
            case "ENTER_WAYLINE":
            case "RETURN_TO_START_POINT":
            case "RECOVERING":
            case "FINISHED":
                return true;
            default:
                return false;
        }
    }

    private static String dispatchFailureReason(WaylineDispatchResultDTO dr) {
        if (StringUtils.hasText(dr.getMsdkErrorMsg())) {
            return dr.getMsdkErrorMsg();
        }
        if (StringUtils.hasText(dr.getReason())) {
            return dr.getReason();
        }
        if (dr.getMsdkErrorCode() != null) {
            return "MSDK error " + dr.getMsdkErrorCode();
        }
        return "dispatch result " + dr.getResult();
    }
}
