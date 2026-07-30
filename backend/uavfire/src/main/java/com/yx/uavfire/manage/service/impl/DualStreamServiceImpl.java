package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.model.param.FireLaserLocationParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.model.dto.VisibleRoiSnapshotDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
@Slf4j
public class DualStreamServiceImpl implements IDualStreamService {

    private static final double FIRE_DETECTION_FLOOR = 0.01;
    private static final double HIGH_TEMPERATURE_VISIBLE_CONFIRMATION_FLOOR = 0.1;
    private static final double THERMAL_WEAK_IMAGE_FLOOR = 0.006;
    private static final double THERMAL_MEDIUM_TEMPERATURE_C = 60.0;
    private static final double THERMAL_HIGH_TEMPERATURE_C = 80.0;
    private static final long CONFIRMED_FIRE_EVENT_DEBOUNCE_MS = 60_000L;
    // 切换期间 group 报 DUAL 模式会骗过 shouldIssueFocus 去重，每帧补发 focus-visible
    // 会让 agent 反复 restartLiveStream；确认流程内按时间节流。
    private static final long VISIBLE_FOCUS_REISSUE_MIN_INTERVAL_MS = 10_000L;
    private static final long VISIBLE_ATTACHMENT_WINDOW_MS = 60_000L;
    private static final long VISIBLE_ROI_FRESHNESS_MS = 1_500L;
    private static final long VISIBLE_ROI_REACQUIRE_TIMEOUT_MS = 3_000L;
    private static final String COMMAND_STATUS_PENDING = "pending";
    private static final String COMMAND_STATUS_DISPATCHED = "dispatched";
    private static final String COMMAND_STATUS_EXPIRED = "expired";
    private static final String REVIEW_STATUS_THERMAL_MEASURING = "THERMAL_MEASURING";
    private static final String REVIEW_STATUS_THERMAL_MEASUREMENT_TIMEOUT = "THERMAL_MEASUREMENT_TIMEOUT";
    private static final String REVIEW_STATUS_THERMAL_IMAGE_MISSING = "THERMAL_IMAGE_MISSING";
    private static final String REVIEW_STATUS_THERMAL_REJECTED = "THERMAL_REJECTED";
    private static final String REVIEW_STATUS_VISIBLE_PENDING = "VISIBLE_PENDING";
    private static final String REVIEW_STATUS_VISIBLE_CONFIRMED = "VISIBLE_CONFIRMED";
    private static final String REVIEW_STATUS_VISIBLE_REJECTED = "VISIBLE_REJECTED";
    private static final String REVIEW_STATUS_VISIBLE_SKIPPED_THERMAL_FIRST = "VISIBLE_SKIPPED_THERMAL_FIRST";

    private static final String GROUP_KEY_PREFIX = "dual-stream:group:";
    private static final String TASK_EVENTS_KEY_PREFIX = "dual-stream:task-events:";
    private static final String DEFAULT_VISIBLE_STREAM_SUFFIX = "-0";
    private static final List<String> URGENT_ACTIONS = List.of(
            "thermal-monitor-on",
            "focus-thermal",
            "focus-visible",
            "measure-thermal-region",
            "fire-confirmation-mission",
            "visible-fire-hold",
            "visible-fire-laser-measure"
    );

    private final Map<String, DualStreamLiveGroupDTO> groups = new ConcurrentHashMap<>();
    private final Map<String, List<DualStreamEventDTO>> taskEvents = new ConcurrentHashMap<>();
    private final Map<String, DualStreamCommandDTO> commandByDrone = new ConcurrentHashMap<>();
    private final Map<String, Deque<DualStreamCommandDTO>> commandQueueByDrone = new ConcurrentHashMap<>();
    private final Map<String, DualStreamEventDTO> visibleTriggerByTask = new ConcurrentHashMap<>();
    private final Map<String, Long> confirmedFireEventByTask = new ConcurrentHashMap<>();
    private final Map<String, Long> lastThermalMeasurementCompletedAtByDrone = new ConcurrentHashMap<>();
    private final Map<String, Long> lastVisibleFocusIssuedAtByDrone = new ConcurrentHashMap<>();
    private final Map<String, DualStreamEventDTO> confirmedThermalEventByTask = new ConcurrentHashMap<>();
    private final Map<String, String> confirmedThermalFireEventIdByTask = new ConcurrentHashMap<>();
    private final Map<String, VisibleRoiSnapshotDTO> latestVisibleRoiByTask = new ConcurrentHashMap<>();
    private final Map<String, VisibleLaserSession> visibleLaserSessionByEvent = new ConcurrentHashMap<>();
    private final Map<String, String> visibleLaserEventByDrone = new ConcurrentHashMap<>();

    @Value("${livestream.playback.webrtc-host:}")
    private String webrtcPlaybackHost;

    @Value("${livestream.playback.webrtc-port:#{null}}")
    private Integer webrtcPlaybackPort;

    @Value("${ai-service.base-url:http://127.0.0.1:9000}")
    private String aiServiceBaseUrl;

    @Value("${dual-stream.thermal-measurement-timeout-ms:20000}")
    private long thermalMeasurementTimeoutMs = 20_000L;

    // 火情确认线：串行链唯一裁决口径——红外 YOLO 命中后对检出框实测温度，达线才确认。
    // 夏季日晒地面实测 33~48°C，测试盆火基准 57.6°C；默认 57，可按季节在 application.yml 调整。
    @Value("${dual-stream.thermal-warm-floor-c:57}")
    private double thermalWarmFloorC = 57.0;

    // 可见光佐证标注线：确认后证据照上可见光 YOLO 达线记 VISIBLE_CONFIRMED、
    // 未达线记 VISIBLE_REJECTED，只影响标注不影响事件存废。
    // 字段初始化兜底，保证单测 new 实例与 Spring 注入默认一致。
    @Value("${dual-stream.visible-confirm-floor:0.2}")
    private double visibleConfirmFloor = 0.2;

    @Value("${dual-stream.thermal-measurement-cooldown-ms:5000}")
    private long thermalMeasurementCooldownMs = 5_000L;

    // 实测温度确认后切可见光补证据照；宽限期内压制自动 focus-thermal 与测温指令，
    // 避免抢在证据照（ai-service 可见光帧快照）落地前把镜头切回红外。
    // 证据照挂接成功或 agent 报终态即提前解除。
    @Value("${dual-stream.visible-confirmation-grace-ms:30000}")
    private long visibleConfirmationGraceMs = 30_000L;

    private final Map<String, Long> visibleConfirmationGraceUntilByDrone = new ConcurrentHashMap<>();
    // 宽限期内我们主动下发过 thermal-monitor-off 的机器：解除时要对称地 thermal-monitor-on 恢复探针
    private final Set<String> confirmationMonitorPausedByDrone = ConcurrentHashMap.newKeySet();

    private final HttpClient aiHttpClient = HttpClient.newHttpClient();

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private StreamSplitterService streamSplitterService;

    @Autowired(required = false)
    private FireEventService fireEventService;

    @Autowired(required = false)
    private com.yx.uavfire.firedetection.FireDetectionActivityTracker fireDetectionActivityTracker;

    @Override
    public void acceptHeartbeat(String droneSn, DualStreamAgentHeartbeatDTO heartbeat) {
        String resolvedDroneSn = resolveDroneSn(droneSn, heartbeat == null ? null : heartbeat.getDroneSn());
        if (!StringUtils.hasText(resolvedDroneSn) || heartbeat == null) {
            return;
        }
        mergeGroup(resolvedDroneSn, group -> {
            group.setConnectionState(heartbeat.getConnectionState());
            group.setSessionState(heartbeat.getSessionState());
        });
    }

    @Override
    public void acceptStatus(String droneSn, DualStreamAgentStatusDTO status) {
        String resolvedDroneSn = resolveDroneSn(droneSn, status == null ? null : status.getDroneSn());
        if (!StringUtils.hasText(resolvedDroneSn) || status == null) {
            return;
        }
        mergeGroup(resolvedDroneSn, group -> {
            if (StringUtils.hasText(status.getConnectionState())) {
                group.setConnectionState(status.getConnectionState());
            }
            if (StringUtils.hasText(status.getMessage())) {
                group.setStatusMessage(status.getMessage());
            }
            if (StringUtils.hasText(status.getLiveStatus())) {
                group.setLiveStatus(status.getLiveStatus());
            }
            if (StringUtils.hasText(status.getCurrentMode())) {
                group.setCurrentMode(status.getCurrentMode());
            }
            if (StringUtils.hasText(status.getVisibleState())) {
                group.setVisibleState(status.getVisibleState());
            }
            if (StringUtils.hasText(status.getThermalState())) {
                group.setThermalState(status.getThermalState());
            }
            if (StringUtils.hasText(status.getStatusReason())) {
                group.setStatusReason(status.getStatusReason());
            }
            if (StringUtils.hasText(status.getPlaybackStatus())) {
                group.setPlaybackStatus(status.getPlaybackStatus());
            }
            if (StringUtils.hasText(status.getVisiblePlayUrl())) {
                group.setVisiblePlayUrl(status.getVisiblePlayUrl());
            }
            if (StringUtils.hasText(status.getThermalPlayUrl())) {
                group.setThermalPlayUrl(status.getThermalPlayUrl());
            }
            if (status.getThermalCenterTemperatureC() != null) {
                group.setThermalCenterTemperatureC(status.getThermalCenterTemperatureC());
            }
        });
    }

    @Override
    public void acceptCapability(String droneSn, DualStreamAgentCapabilityDTO capability) {
        String resolvedDroneSn = resolveDroneSn(droneSn, capability == null ? null : capability.getDroneSn());
        if (!StringUtils.hasText(resolvedDroneSn) || capability == null) {
            return;
        }
        mergeGroup(resolvedDroneSn, group -> {
            group.setVisibleSupported(capability.getVisibleSupported());
            group.setThermalSupported(capability.getThermalSupported());
        });
    }

    @Override
    public void acceptEvent(String taskId, DualStreamEventDTO event) {
        if (!StringUtils.hasText(taskId) || event == null) {
            return;
        }
        recordLatestVisibleRoi(taskId, event);
        taskEvents.compute(taskId, (key, existing) -> {
            List<DualStreamEventDTO> events = existing != null ? copyEvents(existing) : restoreEventsFromRedis(taskId);
            if (events == null) {
                events = new CopyOnWriteArrayList<>();
            }
            expireTimedOutThermalMeasurements(taskId, events);
            DualStreamEventDTO reviewedEvent = applySingleStreamReview(copyEvent(event).setTaskId(taskId), events);
            events.add(reviewedEvent);
            log.info(
                    "dual-stream event accepted task={} drone={} ts={} channel={} visible={} thermal={} fusion={} risk={} review={}",
                    taskId,
                    reviewedEvent.getDroneSn(),
                    reviewedEvent.getSourceTs(),
                    reviewedEvent.getAnalysisChannel(),
                    reviewedEvent.getVisibleScore(),
                    reviewedEvent.getThermalScore(),
                    reviewedEvent.getFusionScore(),
                    reviewedEvent.getRiskLevel(),
                    reviewedEvent.getReviewStatus());
            persistEvents(taskId, events);
            return events;
        });
        dispatchLaserMeasureIfReady(taskId);
    }

    @Override
    public synchronized void startVisibleLaserLocalization(
            String eventId,
            String taskId,
            String droneSn,
            long sourceTs,
            Map<String, Double> visibleRoi) {
        if (!StringUtils.hasText(eventId)
                || !StringUtils.hasText(taskId)
                || !StringUtils.hasText(droneSn)
                || !isValidVisibleRoi(visibleRoi)) {
            return;
        }
        if (visibleLaserSessionByEvent.containsKey(eventId)
                || visibleLaserEventByDrone.containsKey(droneSn)) {
            return;
        }
        VisibleLaserSession session = new VisibleLaserSession(
                eventId, taskId, droneSn, sourceTs, new LinkedHashMap<>(visibleRoi));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("eventId", eventId);
        params.put("taskId", taskId);
        params.put("sourceTs", sourceTs);
        params.put("visibleRoi", new LinkedHashMap<>(visibleRoi));
        DualStreamCommandDTO hold = issueCommand(droneSn, "visible-fire-hold", params);
        if (hold == null) {
            return;
        }
        session.holdIssuedAt = hold.getIssuedAt();
        visibleLaserSessionByEvent.put(eventId, session);
        visibleLaserEventByDrone.put(droneSn, eventId);
    }

    @Override
    public VisibleRoiSnapshotDTO latestVisibleRoi(String taskId, long afterSourceTs) {
        VisibleRoiSnapshotDTO snapshot = latestVisibleRoiByTask.get(taskId);
        if (snapshot == null
                || snapshot.getSourceTs() == null
                || snapshot.getSourceTs() <= afterSourceTs
                || System.currentTimeMillis() - snapshot.getSourceTs() > VISIBLE_ROI_FRESHNESS_MS) {
            return null;
        }
        return copyVisibleRoiSnapshot(snapshot);
    }

    @Override
    public synchronized void expireLocalizationSessions() {
        long now = System.currentTimeMillis();
        for (VisibleLaserSession session : List.copyOf(visibleLaserSessionByEvent.values())) {
            if ("REACQUIRING".equals(session.phase) && now > session.reacquireDeadlineAt) {
                failVisibleLaserSession(session, "target-not-reacquired", now);
            }
        }
    }

    @Override
    public List<DualStreamEventDTO> listEvents(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }

        List<DualStreamEventDTO> cached = taskEvents.get(taskId);
        if (cached != null) {
            expireTimedOutThermalMeasurements(taskId, cached);
            return copyEvents(cached);
        }

        List<DualStreamEventDTO> restored = restoreEventsFromRedis(taskId);
        if (restored == null) {
            return List.of();
        }
        expireTimedOutThermalMeasurements(taskId, restored);
        taskEvents.put(taskId, restored);
        return copyEvents(restored);
    }

    @Override
    public DualStreamCommandDTO issueCommand(String droneSn, String action) {
        return issueCommand(droneSn, action, null);
    }

    @Override
    public DualStreamCommandDTO issueCommand(String droneSn, String action, Map<String, Object> params) {
        if (!StringUtils.hasText(droneSn) || !StringUtils.hasText(action)) {
            return null;
        }
        DualStreamCommandDTO command = new DualStreamCommandDTO()
                .setCommandId(UUID.randomUUID().toString())
                .setDroneSn(droneSn)
                .setAction(action)
                .setUrgent(isUrgentAction(action) ? Boolean.TRUE : null)
                .setParams(params == null || params.isEmpty() ? null : new LinkedHashMap<>(params))
                .setStatus("pending")
                .setIssuedAt(System.currentTimeMillis());
        enqueueCommand(command);
        log.info("dual-stream command issued drone={} action={} command={}", droneSn, action, command.getCommandId());
        mergeGroup(droneSn, group -> group
                .setLastCommandAction(action)
                .setLastCommandStatus(COMMAND_STATUS_PENDING));
        return copyCommand(command);
    }

    @Override
    public DualStreamCommandDTO pollCommand(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return null;
        }
        expireTimedOutCommands(droneSn);
        DualStreamCommandDTO active = commandByDrone.get(droneSn);
        if (active == null || !COMMAND_STATUS_PENDING.equals(normalize(active.getStatus()))) {
            Deque<DualStreamCommandDTO> queue = commandQueueByDrone.get(droneSn);
            if (queue != null && !queue.isEmpty()) {
                active = queue.pollFirst();
                commandByDrone.put(droneSn, active);
            }
        }
        if (active != null) {
            log.info(
                    "dual-stream command polled drone={} command={} action={} status={}",
                    droneSn,
                    active.getCommandId(),
                    active.getAction(),
                    active.getStatus());
        }
        return copyCommand(active);
    }

    @Override
    public void acknowledgeCommand(String droneSn, DualStreamCommandAckDTO ack) {
        if (!StringUtils.hasText(droneSn) || ack == null || !StringUtils.hasText(ack.getCommandId())) {
            return;
        }
        AtomicReference<DualStreamCommandDTO> matchedCommand = new AtomicReference<>();
        commandByDrone.computeIfPresent(droneSn, (sn, existing) -> {
            if (!Objects.equals(existing.getCommandId(), ack.getCommandId())) {
                return existing;
            }
            existing.setStatus(ack.getStatus())
                    .setMessage(ack.getMessage())
                    .setAckedAt(System.currentTimeMillis());
            matchedCommand.set(copyCommand(existing));
            log.info(
                    "dual-stream command ack drone={} command={} action={} status={} message={}",
                    sn,
                    existing.getCommandId(),
                    existing.getAction(),
                    existing.getStatus(),
                    existing.getMessage());
            mergeGroup(sn, group -> group
                    .setLastCommandAction(existing.getAction())
                    .setLastCommandStatus(existing.getStatus()));
            return existing;
        });
        handleThermalMeasurementAck(matchedCommand.get(), ack);
        handleVisibleLaserAck(matchedCommand.get(), ack);
        if (matchedCommand.get() != null && isTerminalCommandStatus(ack.getStatus())) {
            commandByDrone.remove(droneSn, matchedCommand.get());
        }
    }

    @Override
    public DualStreamLiveGroupDTO getGroup(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return null;
        }

        DualStreamLiveGroupDTO cached = groups.get(droneSn);
        if (cached != null) {
            return normalizeGroup(copyGroup(cached));
        }

        DualStreamLiveGroupDTO restored = restoreGroupFromRedis(droneSn);
        if (restored == null) {
            return null;
        }
        DualStreamLiveGroupDTO normalized = normalizeGroup(restored);
        groups.put(droneSn, normalized);
        return copyGroup(normalized);
    }

    @Override
    public DualStreamCommandDTO buildCommand(String droneSn, String action) {
        if (!StringUtils.hasText(droneSn)) {
            return null;
        }
        return new DualStreamCommandDTO()
                .setDroneSn(droneSn)
                .setAction(action)
                .setUrgent(isUrgentAction(action) ? Boolean.TRUE : null)
                .setIssuedAt(System.currentTimeMillis());
    }

    private void enqueueCommand(DualStreamCommandDTO command) {
        if (command == null || !StringUtils.hasText(command.getDroneSn())) {
            return;
        }
        String droneSn = command.getDroneSn();
        expireTimedOutCommands(droneSn);
        DualStreamCommandDTO active = commandByDrone.get(droneSn);
        if (active == null || !COMMAND_STATUS_PENDING.equals(normalize(active.getStatus()))) {
            commandByDrone.put(droneSn, command);
            return;
        }
        commandQueueByDrone.computeIfAbsent(droneSn, ignored -> new ArrayDeque<>()).addLast(command);
    }

    private void mergeGroup(String droneSn, Consumer<DualStreamLiveGroupDTO> updater) {
        groups.compute(droneSn, (sn, existing) -> {
            DualStreamLiveGroupDTO group = existing != null ? copyGroup(existing) : restoreGroupFromRedis(sn);
            if (group == null) {
                group = new DualStreamLiveGroupDTO().setDroneSn(sn);
            }
            updater.accept(group);
            group = normalizeGroup(group);
            persistSnapshot(sn, group);
            return group;
        });
    }

    private DualStreamLiveGroupDTO normalizeGroup(DualStreamLiveGroupDTO group) {
        if (group == null) {
            return null;
        }

        expireOrphanedPendingGroupCommand(group);

        if ("visible-live-ready".equalsIgnoreCase(group.getPlaybackStatus())
                && "running".equalsIgnoreCase(group.getVisibleState())) {
            String visibleOnlyUrl = buildPlaybackUrl(group.getDroneSn() + DEFAULT_VISIBLE_STREAM_SUFFIX);
            if (StringUtils.hasText(visibleOnlyUrl)) {
                group.setVisiblePlayUrl(visibleOnlyUrl);
            }
            group.setThermalPlayUrl(null);
        }

        if (!StringUtils.hasText(group.getVisiblePlayUrl()) && "running".equalsIgnoreCase(group.getVisibleState())) {
            String fallbackUrl = buildPlaybackUrl(group.getDroneSn() + DEFAULT_VISIBLE_STREAM_SUFFIX);
            if (StringUtils.hasText(fallbackUrl)) {
                group.setVisiblePlayUrl(fallbackUrl);
                if (!StringUtils.hasText(group.getPlaybackStatus())
                        || "awaiting-media-url".equalsIgnoreCase(group.getPlaybackStatus())) {
                    group.setPlaybackStatus("visible-playback-ready");
                }
            }
        }

        if (StringUtils.hasText(group.getVisiblePlayUrl())) {
            String droneSn = group.getDroneSn();
            String sourceStreamId = droneSn + DEFAULT_VISIBLE_STREAM_SUFFIX;
            boolean isSharedSbs = "shared-side-by-side-preview".equalsIgnoreCase(group.getPlaybackStatus())
                    && "running".equalsIgnoreCase(group.getThermalState());

            if (isSharedSbs) {
                if (streamSplitterService != null) {
                    streamSplitterService.stopSplit(droneSn);
                }
                group.setVisiblePlayUrl(buildPlaybackUrl(sourceStreamId));
                group.setThermalPlayUrl(group.getVisiblePlayUrl());
            } else {
                if (streamSplitterService != null) {
                    streamSplitterService.stopSplit(droneSn);
                }
            }
        }

        return group;
    }

    private void expireOrphanedPendingGroupCommand(DualStreamLiveGroupDTO group) {
        if (group == null || !StringUtils.hasText(group.getDroneSn())) {
            return;
        }
        String status = normalize(group.getLastCommandStatus());
        if (!COMMAND_STATUS_PENDING.equals(status) && !COMMAND_STATUS_DISPATCHED.equals(status)) {
            return;
        }
        expireTimedOutCommands(group.getDroneSn(), false);
        if (hasInMemoryInFlightCommand(group.getDroneSn())) {
            return;
        }
        group.setLastCommandStatus(COMMAND_STATUS_EXPIRED);
        log.warn(
                "dual-stream orphan pending command expired drone={} action={} status={}",
                group.getDroneSn(),
                group.getLastCommandAction(),
                status);
    }

    private boolean hasInMemoryInFlightCommand(String droneSn) {
        DualStreamCommandDTO active = commandByDrone.get(droneSn);
        if (isInFlightCommand(active)) {
            return true;
        }
        Deque<DualStreamCommandDTO> queue = commandQueueByDrone.get(droneSn);
        return queue != null && queue.stream().anyMatch(this::isInFlightCommand);
    }

    private boolean isInFlightCommand(DualStreamCommandDTO command) {
        if (command == null) {
            return false;
        }
        String status = normalize(command.getStatus());
        return COMMAND_STATUS_PENDING.equals(status) || COMMAND_STATUS_DISPATCHED.equals(status);
    }

    private boolean isTerminalCommandStatus(String status) {
        String normalized = normalize(status);
        return StringUtils.hasText(normalized)
                && !COMMAND_STATUS_PENDING.equals(normalized)
                && !COMMAND_STATUS_DISPATCHED.equals(normalized);
    }

    private void expireTimedOutCommands(String droneSn) {
        expireTimedOutCommands(droneSn, true);
    }

    private void expireTimedOutCommands(String droneSn, boolean updateGroupSnapshot) {
        if (!StringUtils.hasText(droneSn)) {
            return;
        }
        DualStreamCommandDTO active = commandByDrone.get(droneSn);
        if (isTimedOutCommand(active)) {
            markCommandExpired(active);
            if (updateGroupSnapshot) {
                mergeGroup(droneSn, group -> group
                        .setLastCommandAction(active.getAction())
                        .setLastCommandStatus(COMMAND_STATUS_EXPIRED));
            }
        }
        Deque<DualStreamCommandDTO> queue = commandQueueByDrone.get(droneSn);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        queue.removeIf(command -> {
            if (!isTimedOutCommand(command)) {
                return false;
            }
            markCommandExpired(command);
            return true;
        });
    }

    private boolean isTimedOutCommand(DualStreamCommandDTO command) {
        if (!isInFlightCommand(command) || command.getIssuedAt() == null) {
            return false;
        }
        long timeoutMs = Math.max(1L, thermalMeasurementTimeoutMs);
        return System.currentTimeMillis() - command.getIssuedAt() > timeoutMs;
    }

    private void markCommandExpired(DualStreamCommandDTO command) {
        command.setStatus(COMMAND_STATUS_EXPIRED)
                .setMessage("command-timeout")
                .setAckedAt(System.currentTimeMillis());
        log.warn(
                "dual-stream command expired drone={} command={} action={} issuedAt={}",
                command.getDroneSn(),
                command.getCommandId(),
                command.getAction(),
                command.getIssuedAt());
    }

    private void expireTimedOutThermalMeasurements(String taskId, List<DualStreamEventDTO> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        boolean changed = false;
        int timedOutCount = 0;
        Long firstTimedOutSourceTs = null;
        Long lastTimedOutSourceTs = null;
        long now = System.currentTimeMillis();
        long timeoutMs = Math.max(1L, thermalMeasurementTimeoutMs);
        for (DualStreamEventDTO event : events) {
            if (event == null
                    || !REVIEW_STATUS_THERMAL_MEASURING.equals(event.getReviewStatus())
                    || event.getSourceTs() == null
                    || now - event.getSourceTs() <= timeoutMs
                    || hasInFlightThermalMeasurement(event.getDroneSn())) {
                continue;
            }
            event.setReviewStatus(REVIEW_STATUS_THERMAL_MEASUREMENT_TIMEOUT);
            changed = true;
            timedOutCount++;
            if (firstTimedOutSourceTs == null) {
                firstTimedOutSourceTs = event.getSourceTs();
            }
            lastTimedOutSourceTs = event.getSourceTs();
        }
        if (changed) {
            log.warn(
                    "dual-stream thermal measurements timed out task={} count={} firstTs={} lastTs={}",
                    taskId,
                    timedOutCount,
                    firstTimedOutSourceTs,
                    lastTimedOutSourceTs);
            persistEvents(taskId, events);
        }
    }

    private String buildPlaybackUrl(String streamId) {
        if (!StringUtils.hasText(streamId)) {
            return null;
        }

        String host = StringUtils.hasText(webrtcPlaybackHost) ? webrtcPlaybackHost : null;
        if (!StringUtils.hasText(host)) {
            return null;
        }

        StringBuilder playbackUrl = new StringBuilder()
                .append("webrtc://")
                .append(host);

        if (webrtcPlaybackPort != null && webrtcPlaybackPort > 0) {
            playbackUrl.append(":").append(webrtcPlaybackPort);
        }

        playbackUrl.append("/live/").append(streamId);
        return playbackUrl.toString();
    }

    private DualStreamLiveGroupDTO restoreGroupFromRedis(String droneSn) {
        if (stringRedisTemplate == null || objectMapper == null) {
            return null;
        }

        String raw = stringRedisTemplate.opsForValue().get(GROUP_KEY_PREFIX + droneSn);
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        try {
            return objectMapper.readValue(raw, DualStreamLiveGroupDTO.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private void persistSnapshot(String droneSn, DualStreamLiveGroupDTO group) {
        if (stringRedisTemplate == null || objectMapper == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(GROUP_KEY_PREFIX + droneSn, objectMapper.writeValueAsString(group));
        } catch (JsonProcessingException ignored) {
            // Keep the in-memory snapshot as fallback when Redis serialization is unavailable.
        }
    }

    private List<DualStreamEventDTO> restoreEventsFromRedis(String taskId) {
        if (stringRedisTemplate == null || objectMapper == null) {
            return null;
        }

        String raw = stringRedisTemplate.opsForValue().get(TASK_EVENTS_KEY_PREFIX + taskId);
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        try {
            List<DualStreamEventDTO> events = objectMapper.readerForListOf(DualStreamEventDTO.class).readValue(raw);
            return new CopyOnWriteArrayList<>(events);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private void persistEvents(String taskId, List<DualStreamEventDTO> events) {
        if (stringRedisTemplate == null || objectMapper == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(TASK_EVENTS_KEY_PREFIX + taskId, objectMapper.writeValueAsString(events));
        } catch (JsonProcessingException ignored) {
            // Keep the in-memory snapshot as fallback when Redis serialization is unavailable.
        }
    }

    private String resolveDroneSn(String primary, String fallback) {
        if (StringUtils.hasText(primary) && StringUtils.hasText(fallback) && !primary.equals(fallback)) {
            return null;
        }
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private DualStreamLiveGroupDTO copyGroup(DualStreamLiveGroupDTO group) {
        if (group == null) {
            return null;
        }
        return new DualStreamLiveGroupDTO()
                .setDroneSn(group.getDroneSn())
                .setConnectionState(group.getConnectionState())
                .setSessionState(group.getSessionState())
                .setLiveStatus(group.getLiveStatus())
                .setCurrentMode(group.getCurrentMode())
                .setStatusMessage(group.getStatusMessage())
                .setVisibleState(group.getVisibleState())
                .setThermalState(group.getThermalState())
                .setStatusReason(group.getStatusReason())
                .setPlaybackStatus(group.getPlaybackStatus())
                .setVisiblePlayUrl(group.getVisiblePlayUrl())
                .setThermalPlayUrl(group.getThermalPlayUrl())
                .setLastCommandAction(group.getLastCommandAction())
                .setLastCommandStatus(group.getLastCommandStatus())
                .setVisibleSupported(group.getVisibleSupported())
                .setThermalSupported(group.getThermalSupported())
                .setThermalCenterTemperatureC(group.getThermalCenterTemperatureC());
    }

    private DualStreamEventDTO copyEvent(DualStreamEventDTO event) {
        return new DualStreamEventDTO()
                .setTaskId(event.getTaskId())
                .setDroneSn(event.getDroneSn())
                .setSourceTs(event.getSourceTs())
                .setVisibleScore(event.getVisibleScore())
                .setThermalScore(event.getThermalScore())
                .setFusionScore(event.getFusionScore())
                .setRiskLevel(event.getRiskLevel())
                .setAnalysisChannel(event.getAnalysisChannel())
                .setReviewStatus(event.getReviewStatus())
                .setVisibleImageUrl(event.getVisibleImageUrl())
                .setThermalImageUrl(event.getThermalImageUrl())
                .setThermalSourceEventId(event.getThermalSourceEventId())
                .setThermalTemperature(event.getThermalTemperature())
                .setThermalMeasureRoi(event.getThermalMeasureRoi())
                .setVisibleRoi(event.getVisibleRoi())
                .setThermalMeasurements(event.getThermalMeasurements())
                .setGeoSnapshot(event.getGeoSnapshot())
                .setFireLat(event.getFireLat())
                .setFireLng(event.getFireLng())
                .setFireAlt(event.getFireAlt())
                .setGeoQuality(event.getGeoQuality())
                .setGeoErrorRadiusM(event.getGeoErrorRadiusM())
                .setGeoMethod(event.getGeoMethod());
    }

    private void recordLatestVisibleRoi(String taskId, DualStreamEventDTO event) {
        if (event.getSourceTs() == null
                || !"visible".equals(normalize(event.getAnalysisChannel()))
                || !isValidVisibleRoi(event.getVisibleRoi())) {
            return;
        }
        latestVisibleRoiByTask.compute(taskId, (ignored, existing) -> {
            if (existing != null
                    && existing.getSourceTs() != null
                    && existing.getSourceTs() >= event.getSourceTs()) {
                return existing;
            }
            return new VisibleRoiSnapshotDTO()
                    .setSourceTs(event.getSourceTs())
                    .setVisibleRoi(new LinkedHashMap<>(event.getVisibleRoi()));
        });
    }

    private synchronized void dispatchLaserMeasureIfReady(String taskId) {
        expireLocalizationSessions();
        for (VisibleLaserSession session : visibleLaserSessionByEvent.values()) {
            if (!Objects.equals(taskId, session.taskId) || !"REACQUIRING".equals(session.phase)) {
                continue;
            }
            VisibleRoiSnapshotDTO snapshot = latestVisibleRoi(taskId, session.holdIssuedAt);
            if (snapshot == null) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("eventId", session.eventId);
            params.put("taskId", session.taskId);
            params.put("sourceTs", snapshot.getSourceTs());
            params.put("visibleRoi", new LinkedHashMap<>(snapshot.getVisibleRoi()));
            DualStreamCommandDTO command =
                    issueCommand(session.droneSn, "visible-fire-laser-measure", params);
            if (command != null) {
                session.phase = "MEASURING";
            }
        }
    }

    private synchronized void handleVisibleLaserAck(
            DualStreamCommandDTO command,
            DualStreamCommandAckDTO ack) {
        if (command == null || ack == null) {
            return;
        }
        String action = normalize(command.getAction());
        if (!"visible-fire-hold".equals(action) && !"visible-fire-laser-measure".equals(action)) {
            return;
        }
        String eventId = StringUtils.hasText(ack.getEventId())
                ? ack.getEventId()
                : commandParamString(command, "eventId");
        VisibleLaserSession session = visibleLaserSessionByEvent.get(eventId);
        if (session == null
                || !Objects.equals(session.droneSn, command.getDroneSn())
                || !Objects.equals(session.eventId, eventId)) {
            return;
        }
        if ("visible-fire-hold".equals(action)) {
            if ("applied".equals(normalize(ack.getStatus()))
                    && "hover_stable".equals(normalize(ack.getMessage()))) {
                session.phase = "REACQUIRING";
                session.reacquireDeadlineAt =
                        System.currentTimeMillis() + VISIBLE_ROI_REACQUIRE_TIMEOUT_MS;
                dispatchLaserMeasureIfReady(session.taskId);
            } else if (isTerminalCommandStatus(ack.getStatus())) {
                failVisibleLaserSession(
                        session,
                        failureReason(ack.getMessage(), "hover-stability-failed"),
                        ack.getSourceTs() != null ? ack.getSourceTs() : System.currentTimeMillis());
            }
            return;
        }
        if (!isTerminalCommandStatus(ack.getStatus())) {
            return;
        }
        if ("applied".equals(normalize(ack.getStatus()))
                && validLaserAck(ack)
                && fireEventService != null) {
            FireLaserLocationParam param = new FireLaserLocationParam()
                    .setFireLat(ack.getFireLat())
                    .setFireLng(ack.getFireLng())
                    .setFireAlt(ack.getFireAlt())
                    .setSourceTs(ack.getSourceTs())
                    .setGeoErrorRadiusM(ack.getGeoErrorRadiusM());
            fireEventService.applyLaserLocation(session.eventId, param);
            completeVisibleLaserSession(session);
            return;
        }
        failVisibleLaserSession(
                session,
                failureReason(ack.getMessage(), "laser-fix-unavailable"),
                ack.getSourceTs() != null ? ack.getSourceTs() : System.currentTimeMillis());
    }

    private boolean validLaserAck(DualStreamCommandAckDTO ack) {
        return ack.getFireLat() != null
                && ack.getFireLat() >= -90.0
                && ack.getFireLat() <= 90.0
                && ack.getFireLng() != null
                && ack.getFireLng() >= -180.0
                && ack.getFireLng() <= 180.0
                && ack.getSourceTs() != null
                && "laser_rangefinder".equals(normalize(ack.getGeoMethod()))
                && "precise".equals(normalize(ack.getGeoQuality()));
    }

    private void failVisibleLaserSession(VisibleLaserSession session, String reason, long sourceTs) {
        if (fireEventService != null) {
            fireEventService.markLaserLocationFailed(session.eventId, reason, sourceTs);
        }
        completeVisibleLaserSession(session);
    }

    private void completeVisibleLaserSession(VisibleLaserSession session) {
        visibleLaserSessionByEvent.remove(session.eventId, session);
        visibleLaserEventByDrone.remove(session.droneSn, session.eventId);
    }

    private String failureReason(String message, String fallback) {
        if (!StringUtils.hasText(message)) {
            return fallback;
        }
        String trimmed = message.trim();
        int colon = trimmed.indexOf(':');
        return colon >= 0 && colon + 1 < trimmed.length()
                ? trimmed.substring(colon + 1)
                : trimmed;
    }

    private String commandParamString(DualStreamCommandDTO command, String key) {
        if (command.getParams() == null) {
            return null;
        }
        Object value = command.getParams().get(key);
        return value == null ? null : value.toString();
    }

    private boolean isValidVisibleRoi(Map<String, Double> roi) {
        if (roi == null) {
            return false;
        }
        Double x = roi.get("x");
        Double y = roi.get("y");
        Double width = roi.get("width");
        Double height = roi.get("height");
        return x != null && y != null && width != null && height != null
                && x >= 0.0 && x <= 1.0
                && y >= 0.0 && y <= 1.0
                && width > 0.0 && height > 0.0
                && x + width <= 1.000001
                && y + height <= 1.000001;
    }

    private VisibleRoiSnapshotDTO copyVisibleRoiSnapshot(VisibleRoiSnapshotDTO snapshot) {
        return new VisibleRoiSnapshotDTO()
                .setSourceTs(snapshot.getSourceTs())
                .setVisibleRoi(snapshot.getVisibleRoi() == null
                        ? null
                        : new LinkedHashMap<>(snapshot.getVisibleRoi()));
    }

    private static final class VisibleLaserSession {
        private final String eventId;
        private final String taskId;
        private final String droneSn;
        private final long sourceTs;
        private final Map<String, Double> originalRoi;
        private long holdIssuedAt;
        private long reacquireDeadlineAt;
        private String phase = "HOLDING";

        private VisibleLaserSession(
                String eventId,
                String taskId,
                String droneSn,
                long sourceTs,
                Map<String, Double> originalRoi) {
            this.eventId = eventId;
            this.taskId = taskId;
            this.droneSn = droneSn;
            this.sourceTs = sourceTs;
            this.originalRoi = originalRoi;
        }
    }

    private DualStreamEventDTO applySingleStreamReview(DualStreamEventDTO event, List<DualStreamEventDTO> priorEvents) {
        DualStreamEventDTO reviewed = copyEvent(event);
        String droneSn = reviewed.getDroneSn();
        String channel = normalize(reviewed.getAnalysisChannel());
        if (!StringUtils.hasText(channel)) {
            String commandChannel = inferChannelFromAppliedFocusCommand(droneSn);
            if (StringUtils.hasText(commandChannel)) {
                channel = commandChannel;
                reviewed.setAnalysisChannel(channel);
            }
        }
        if (!StringUtils.hasText(channel)) {
            channel = inferChannelFromGroup(droneSn);
            if (StringUtils.hasText(channel)) {
                reviewed.setAnalysisChannel(channel);
            }
        }
        if (!StringUtils.hasText(droneSn) || !StringUtils.hasText(channel)) {
            return reviewed;
        }

        if ("visible".equals(channel)
                && StringUtils.hasText(reviewed.getVisibleImageUrl())
                && StringUtils.hasText(reviewed.getThermalSourceEventId())) {
            // agent 确认照（唯一携带 thermalSourceEventId 的可见光事件）：先挂证据再走复核，
            // 复核结论（CONFIRMED/REJECTED/PENDING）不该决定"照片存不存"。
            attachVisibleConfirmationEvidence(reviewed);
            // 照片已到手，确认宽限期结束——恢复探针，后续复核可立即恢复"拍完切回红外"
            liftVisibleConfirmationGrace(droneSn);
        }

        if ("visible".equals(channel) && REVIEW_STATUS_VISIBLE_PENDING.equals(reviewed.getReviewStatus())) {
            recordVisibleStatusForRecentThermalConfirmation(reviewed, reviewed.getReviewStatus());
            return reviewed;
        }

        if ("visible".equals(channel) && isVisibleTerminalStatus(reviewed.getReviewStatus())) {
            recordVisibleStatusForRecentThermalConfirmation(reviewed, reviewed.getReviewStatus());
            // agent 已报确认终态（成功/失败都算结束），解除宽限让镜头回红外
            liftVisibleConfirmationGrace(droneSn);
            requestThermalFocusAfterVisibleReview(droneSn);
            return reviewed;
        }

        if ("visible".equals(channel) && confirmedThermalEventByTask.containsKey(reviewed.getTaskId())) {
            // 证据照挂接：事件存废已由实测温度裁决。达佐证线的照片挂为事件主图，
            // 未达线的照片连同 VISIBLE_REJECTED 标注记入事件履历——只标注不拦截。
            if (hasVisibleFireDetection(reviewed, confirmedThermalEventByTask.get(reviewed.getTaskId()))) {
                if (attachVisibleImageToRecentThermalConfirmation(reviewed)) {
                    reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_CONFIRMED);
                    liftVisibleConfirmationGrace(droneSn);
                    requestThermalFocusAfterVisibleReview(droneSn);
                    return reviewed;
                }
            } else if (StringUtils.hasText(reviewed.getVisibleImageUrl())) {
                reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_REJECTED);
                recordVisibleStatusForRecentThermalConfirmation(reviewed, REVIEW_STATUS_VISIBLE_REJECTED);
                liftVisibleConfirmationGrace(droneSn);
                requestThermalFocusAfterVisibleReview(droneSn);
                return reviewed;
            }
        }

        if ("visible".equals(channel)) {
            // 纯可见光模式：火情事件由 ai-service 上报器直接创建，这里不再切红外做串行复核。
            reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_SKIPPED_THERMAL_FIRST);
            rememberVisibleTrigger(reviewed, droneSn);
            return reviewed;
        }

        if ("thermal".equals(channel)) {
            if (shouldMeasureThermalRegion(reviewed) && isFireDetectionActiveForAutoFocus(droneSn)) {
                if (isWithinVisibleConfirmationGrace(droneSn)) {
                    // 宽限期内测温指令也要安静——measure-thermal-region 会切镜头打断确认照拍摄
                    reviewed.setReviewStatus(REVIEW_STATUS_THERMAL_REJECTED);
                    log.info(
                            "dual-stream thermal measurement skipped by visible-confirmation grace task={} drone={} ts={}",
                            reviewed.getTaskId(),
                            droneSn,
                            reviewed.getSourceTs());
                    return reviewed;
                }
                if (isInThermalMeasurementCooldown(droneSn)) {
                    reviewed.setReviewStatus(REVIEW_STATUS_THERMAL_REJECTED);
                    log.info(
                            "dual-stream thermal measurement skipped by cooldown task={} drone={} ts={}",
                            reviewed.getTaskId(),
                            droneSn,
                            reviewed.getSourceTs());
                    return reviewed;
                }
                if (hasInFlightThermalMeasurement(droneSn)) {
                    reviewed.setReviewStatus(REVIEW_STATUS_THERMAL_REJECTED);
                    log.info(
                            "dual-stream thermal measurement skipped by in-flight command task={} drone={} ts={}",
                            reviewed.getTaskId(),
                            droneSn,
                            reviewed.getSourceTs());
                    return reviewed;
                }
                reviewed.setReviewStatus("THERMAL_MEASURING");
                issueThermalMeasurementCommand(reviewed);
                return reviewed;
            }
            // 串行链：只有真实测温（后端测温 ack 回填或任务确认流程随事件携带）达线才确认；
            // YOLO 分数只负责触发测温，不再单独建事件，HUD 中心温度不参与判定。
            Double measuredTemperature = reviewed.getThermalTemperature();
            if (isTemperatureAtLeast(measuredTemperature, thermalWarmFloorC)) {
                if (!hasThermalImage(reviewed)) {
                    reviewed.setReviewStatus(REVIEW_STATUS_THERMAL_IMAGE_MISSING);
                    confirmedThermalEventByTask.put(reviewed.getTaskId(), copyEvent(reviewed));
                    confirmedThermalFireEventIdByTask.remove(reviewed.getTaskId());
                    log.warn(
                            "dual-stream thermal confirmation skipped without thermal image task={} drone={} ts={}",
                            reviewed.getTaskId(),
                            droneSn,
                            reviewed.getSourceTs());
                    return reviewed;
                }
                reviewed.setReviewStatus("THERMAL_CONFIRMED");
                beginVisibleConfirmationGrace(droneSn);
                createConfirmedFireEvent(reviewed, priorEvents);
                if (shouldIssueVisibleFocusForThermalEvent(reviewed)) {
                    issueVisibleFocusThrottled(droneSn);
                }
                return reviewed;
            }
            reviewed.setReviewStatus(REVIEW_STATUS_THERMAL_REJECTED);
        }
        return reviewed;
    }

    private boolean isInThermalMeasurementCooldown(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        Long completedAt = lastThermalMeasurementCompletedAtByDrone.get(droneSn);
        if (completedAt == null) {
            return false;
        }
        long cooldownMs = Math.max(0L, thermalMeasurementCooldownMs);
        return cooldownMs > 0L && System.currentTimeMillis() - completedAt < cooldownMs;
    }

    private boolean shouldMeasureThermalRegion(DualStreamEventDTO event) {
        // 串行链唯一触发口径：红外 YOLO 有检出框（弱分即可，保小火灵敏度）就实测一次温度；
        // 测温不切镜头，误报由温度裁决兜住，5s 冷却限频。
        return event != null
                && event.getThermalTemperature() == null
                && event.getThermalMeasureRoi() != null
                && resolveThermalImageScore(event) >= THERMAL_WEAK_IMAGE_FLOOR;
    }

    private boolean hasInFlightThermalMeasurement(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        expireTimedOutCommands(droneSn);
        DualStreamCommandDTO active = commandByDrone.get(droneSn);
        if (isInFlightThermalMeasurement(active)) {
            return true;
        }
        Deque<DualStreamCommandDTO> queue = commandQueueByDrone.get(droneSn);
        if (queue == null || queue.isEmpty()) {
            return false;
        }
        return queue.stream().anyMatch(this::isInFlightThermalMeasurement);
    }

    private boolean isInFlightThermalMeasurement(DualStreamCommandDTO command) {
        if (command == null || !"measure-thermal-region".equals(normalize(command.getAction()))) {
            return false;
        }
        String status = normalize(command.getStatus());
        return COMMAND_STATUS_PENDING.equals(status) || COMMAND_STATUS_DISPATCHED.equals(status);
    }

    private DualStreamCommandDTO issueThermalMeasurementCommand(DualStreamEventDTO event) {
        if (event == null || !StringUtils.hasText(event.getDroneSn())) {
            return null;
        }
        DualStreamCommandDTO command = new DualStreamCommandDTO()
                .setCommandId(UUID.randomUUID().toString())
                .setDroneSn(event.getDroneSn())
                .setAction("measure-thermal-region")
                .setUrgent(Boolean.TRUE)
                .setStatus("pending")
                .setTaskId(event.getTaskId())
                .setSourceTs(event.getSourceTs())
                .setThermalMeasureRoi(event.getThermalMeasureRoi())
                .setThermalImageUrl(event.getThermalImageUrl())
                .setIssuedAt(System.currentTimeMillis());
        enqueueCommand(command);
        log.info(
                "dual-stream thermal measurement command issued task={} drone={} ts={} command={} roi={}",
                event.getTaskId(),
                event.getDroneSn(),
                event.getSourceTs(),
                command.getCommandId(),
                command.getThermalMeasureRoi());
        mergeGroup(event.getDroneSn(), group -> group
                .setLastCommandAction(command.getAction())
                .setLastCommandStatus(COMMAND_STATUS_PENDING));
        return copyCommand(command);
    }

    private void handleThermalMeasurementAck(DualStreamCommandDTO command, DualStreamCommandAckDTO ack) {
        if (command == null || ack == null || !"measure-thermal-region".equals(normalize(command.getAction()))) {
            return;
        }
        if (!"applied".equals(normalize(ack.getStatus())) || ack.getThermalTemperature() == null) {
            return;
        }
        String taskId = StringUtils.hasText(ack.getTaskId()) ? ack.getTaskId() : command.getTaskId();
        Long sourceTs = ack.getSourceTs() != null ? ack.getSourceTs() : command.getSourceTs();
        if (!StringUtils.hasText(taskId) || sourceTs == null) {
            return;
        }
        if (StringUtils.hasText(command.getDroneSn())) {
            lastThermalMeasurementCompletedAtByDrone.put(command.getDroneSn(), System.currentTimeMillis());
        }
        taskEvents.compute(taskId, (key, existing) -> {
            List<DualStreamEventDTO> events = existing != null ? copyEvents(existing) : restoreEventsFromRedis(taskId);
            if (events == null) {
                return existing;
            }
            for (DualStreamEventDTO event : events) {
                if (!Objects.equals(sourceTs, event.getSourceTs())) {
                    continue;
                }
                event.setThermalTemperature(ack.getThermalTemperature());
                if (event.getThermalMeasureRoi() == null) {
                    if (command.getThermalMeasureRoi() != null) {
                        event.setThermalMeasureRoi(command.getThermalMeasureRoi());
                    } else if (ack.getThermalMeasureRoi() != null) {
                        event.setThermalMeasureRoi(ack.getThermalMeasureRoi());
                    }
                }
                event.setReviewStatus(resolveThermalPostMeasurementReviewStatus(event));
                log.info(
                        "dual-stream thermal measurement ack applied task={} drone={} ts={} temp={} roi={} review={}",
                        taskId,
                        event.getDroneSn(),
                        sourceTs,
                        ack.getThermalTemperature(),
                        event.getThermalMeasureRoi(),
                        event.getReviewStatus());
                refreshThermalSnapshotAnnotation(event);
                if ("THERMAL_CONFIRMED".equals(event.getReviewStatus())) {
                    beginVisibleConfirmationGrace(event.getDroneSn());
                    createConfirmedFireEvent(event, events);
                    if (!thermalMeasurementAckRestoredVisible(ack)
                            && shouldIssueVisibleFocusForThermalEvent(event)) {
                        issueVisibleFocusThrottled(event.getDroneSn());
                    }
                }
                break;
            }
            persistEvents(taskId, events);
            return events;
        });
    }

    private boolean thermalMeasurementAckRestoredVisible(DualStreamCommandAckDTO ack) {
        return ack != null && normalize(ack.getMessage()).contains("visible-restored");
    }

    private boolean shouldIssueVisibleFocusForThermalEvent(DualStreamEventDTO event) {
        return event != null
                && StringUtils.hasText(event.getDroneSn())
                && shouldIssueFocus(event.getDroneSn(), "focus-visible");
    }

    private void issueVisibleFocusThrottled(String droneSn) {
        long now = System.currentTimeMillis();
        Long last = lastVisibleFocusIssuedAtByDrone.get(droneSn);
        boolean focusThrottled = last != null && now - last < VISIBLE_FOCUS_REISSUE_MIN_INTERVAL_MS;
        if (!focusThrottled) {
            lastVisibleFocusIssuedAtByDrone.put(droneSn, now);
            issueCommand(droneSn, "focus-visible");
        }
        // 确认宽限期内暂停 agent 自主测温探针（它每 2s 会 focusThermal 抢镜头）；
        // 排在 focus-visible 之后下发，保持"确认后队首命令是切可见光"的既有契约
        if (visibleConfirmationGraceUntilByDrone.containsKey(droneSn)
                && confirmationMonitorPausedByDrone.add(droneSn)) {
            issueCommand(droneSn, "thermal-monitor-off");
        }
    }

    private void createConfirmedFireEvent(DualStreamEventDTO event, List<DualStreamEventDTO> priorEvents) {
        if (fireEventService == null || event == null || !StringUtils.hasText(event.getTaskId())) {
            return;
        }
        Long sourceTs = event.getSourceTs() != null ? event.getSourceTs() : System.currentTimeMillis();
        refreshThermalSnapshotAnnotation(event);
        Long lastConfirmedTs = confirmedFireEventByTask.get(event.getTaskId());
        if (lastConfirmedTs != null && sourceTs - lastConfirmedTs < CONFIRMED_FIRE_EVENT_DEBOUNCE_MS) {
            if ("THERMAL_CONFIRMED".equals(event.getReviewStatus())) {
                confirmedThermalEventByTask.put(event.getTaskId(), copyEvent(event));
            }
            return;
        }
        confirmedFireEventByTask.put(event.getTaskId(), sourceTs);
        DualStreamEventDTO visibleEvent = visibleTriggerByTask.get(event.getTaskId());
        FireEventCreateParam param = new FireEventCreateParam();
        param.setEventId(event.getTaskId() + "-" + sourceTs);
        param.setSource("M4T");
        param.setDeviceSn(event.getDroneSn());
        param.setConfidence(BigDecimal.valueOf(resolveConfirmedConfidence(event)));
        param.setFireLevel(resolveConfirmedFireLevel(event));
        param.setVisibleImageUrl(resolveConfirmedVisibleImageUrl(event, visibleEvent));
        param.setThermalImageUrl(resolveConfirmedThermalImageUrl(event));
        Double thermalTemperature = resolveThermalTemperature(event);
        if (thermalTemperature != null) {
            param.setThermalTemperature(thermalTemperature);
            param.setTemperatureUnit("C");
        }
        param.setThermalMeasureRoi(event.getThermalMeasureRoi());
        param.setGeoSnapshot(event.getGeoSnapshot());
        param.setLat(event.getFireLat());
        param.setLng(event.getFireLng());
        param.setAlt(event.getFireAlt());
        param.setGeoQuality(event.getGeoQuality());
        param.setGeoErrorRadiusM(event.getGeoErrorRadiusM());
        param.setGeoMethod(event.getGeoMethod());
        param.setTimestamp(Instant.ofEpochMilli(sourceTs).toString());
        log.info(
                "dual-stream confirmed fire event creating eventId={} drone={} confidence={} level={} temp={} visibleImage={} thermalImage={}",
                param.getEventId(),
                param.getDeviceSn(),
                param.getConfidence(),
                param.getFireLevel(),
                param.getThermalTemperature(),
                param.getVisibleImageUrl(),
                param.getThermalImageUrl());
        FireEventCreateResponse response = fireEventService.create(param);
        if ("THERMAL_CONFIRMED".equals(event.getReviewStatus())) {
            confirmedThermalEventByTask.put(event.getTaskId(), copyEvent(event));
            confirmedThermalFireEventIdByTask.put(
                    event.getTaskId(),
                    response != null && StringUtils.hasText(response.getEventId())
                            ? response.getEventId()
                            : param.getEventId());
        }
    }

    /**
     * agent 可见光确认照证据挂接：确认照携带 thermalSourceEventId（= fire_event.event_id 精确外键），
     * 不依赖内存关联与 60s 时间窗（镜头拉锯常让确认照晚 74s+ 才上传成功，旧路径全部拒挂）。
     * 精确 id 对应行可能已被空间合并吞并，失败时回退到合并后的 fire event id。
     */
    private void attachVisibleConfirmationEvidence(DualStreamEventDTO visibleEvent) {
        if (fireEventService == null || visibleEvent.getSourceTs() == null) {
            return;
        }
        String preciseEventId = visibleEvent.getThermalSourceEventId();
        String sourceEventId = visibleEvent.getTaskId() + "-" + visibleEvent.getSourceTs();
        String timestamp = Instant.ofEpochMilli(visibleEvent.getSourceTs()).toString();
        String thermalImageUrl = visibleEvent.getThermalImageUrl();
        if (!StringUtils.hasText(thermalImageUrl)) {
            DualStreamEventDTO thermalEvent = confirmedThermalEventByTask.get(visibleEvent.getTaskId());
            thermalImageUrl = thermalEvent != null ? thermalEvent.getThermalImageUrl() : null;
        }
        try {
            boolean attached = fireEventService.attachVisibleImage(
                    preciseEventId,
                    sourceEventId,
                    visibleEvent.getVisibleImageUrl(),
                    timestamp,
                    preciseEventId,
                    thermalImageUrl);
            if (!attached) {
                String mergedEventId = confirmedThermalFireEventIdByTask.get(visibleEvent.getTaskId());
                if (StringUtils.hasText(mergedEventId) && !mergedEventId.equals(preciseEventId)) {
                    attached = fireEventService.attachVisibleImage(
                            mergedEventId,
                            sourceEventId,
                            visibleEvent.getVisibleImageUrl(),
                            timestamp,
                            preciseEventId,
                            thermalImageUrl);
                }
            }
            if (!attached) {
                log.warn(
                        "visible confirmation evidence attach failed task={} thermalSourceEventId={}",
                        visibleEvent.getTaskId(),
                        preciseEventId);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "visible confirmation evidence attach error task={} thermalSourceEventId={}",
                    visibleEvent.getTaskId(),
                    preciseEventId,
                    ex);
        }
    }

    private boolean attachVisibleImageToRecentThermalConfirmation(DualStreamEventDTO visibleEvent) {
        if (fireEventService == null
                || visibleEvent == null
                || !StringUtils.hasText(visibleEvent.getTaskId())
                || !StringUtils.hasText(visibleEvent.getVisibleImageUrl())) {
            return false;
        }
        DualStreamEventDTO thermalEvent = confirmedThermalEventByTask.get(visibleEvent.getTaskId());
        if (thermalEvent == null || thermalEvent.getSourceTs() == null || visibleEvent.getSourceTs() == null) {
            return false;
        }
        long delta = visibleEvent.getSourceTs() - thermalEvent.getSourceTs();
        if (delta < 0 || delta > VISIBLE_ATTACHMENT_WINDOW_MS) {
            confirmedThermalEventByTask.remove(visibleEvent.getTaskId());
            confirmedThermalFireEventIdByTask.remove(visibleEvent.getTaskId());
            return false;
        }
        String eventId = confirmedThermalFireEventIdByTask.get(visibleEvent.getTaskId());
        if (!StringUtils.hasText(eventId)) {
            eventId = visibleEvent.getTaskId() + "-" + thermalEvent.getSourceTs();
        }
        String sourceEventId = visibleEvent.getTaskId() + "-" + visibleEvent.getSourceTs();
        String thermalSourceEventId = StringUtils.hasText(visibleEvent.getThermalSourceEventId())
                ? visibleEvent.getThermalSourceEventId()
                : visibleEvent.getTaskId() + "-" + thermalEvent.getSourceTs();
        String thermalImageUrl = StringUtils.hasText(visibleEvent.getThermalImageUrl())
                ? visibleEvent.getThermalImageUrl()
                : thermalEvent.getThermalImageUrl();
        if (!StringUtils.hasText(thermalImageUrl)) {
            log.warn(
                    "dual-stream visible confirmation skipped without associated thermal image task={} visibleTs={} thermalTs={}",
                    visibleEvent.getTaskId(),
                    visibleEvent.getSourceTs(),
                    thermalEvent.getSourceTs());
            return false;
        }
        boolean attached = fireEventService.attachVisibleImage(
                eventId,
                sourceEventId,
                visibleEvent.getVisibleImageUrl(),
                Instant.ofEpochMilli(visibleEvent.getSourceTs()).toString(),
                thermalSourceEventId,
                thermalImageUrl);
        if (attached) {
            visibleTriggerByTask.put(visibleEvent.getTaskId(), copyEvent(visibleEvent));
            confirmedThermalEventByTask.remove(visibleEvent.getTaskId());
            confirmedThermalFireEventIdByTask.remove(visibleEvent.getTaskId());
        }
        return attached;
    }

    private boolean recordVisibleStatusForRecentThermalConfirmation(DualStreamEventDTO visibleEvent, String action) {
        if (fireEventService == null
                || visibleEvent == null
                || !StringUtils.hasText(action)
                || !StringUtils.hasText(visibleEvent.getTaskId())) {
            return false;
        }
        DualStreamEventDTO thermalEvent = confirmedThermalEventByTask.get(visibleEvent.getTaskId());
        if (thermalEvent == null || thermalEvent.getSourceTs() == null || visibleEvent.getSourceTs() == null) {
            return false;
        }
        long delta = visibleEvent.getSourceTs() - thermalEvent.getSourceTs();
        if (delta < 0 || delta > VISIBLE_ATTACHMENT_WINDOW_MS) {
            confirmedThermalEventByTask.remove(visibleEvent.getTaskId());
            confirmedThermalFireEventIdByTask.remove(visibleEvent.getTaskId());
            return false;
        }
        String eventId = confirmedThermalFireEventIdByTask.get(visibleEvent.getTaskId());
        if (!StringUtils.hasText(eventId)) {
            eventId = visibleEvent.getTaskId() + "-" + thermalEvent.getSourceTs();
        }
        String sourceEventId = visibleEvent.getTaskId() + "-" + visibleEvent.getSourceTs();
        String thermalSourceEventId = StringUtils.hasText(visibleEvent.getThermalSourceEventId())
                ? visibleEvent.getThermalSourceEventId()
                : visibleEvent.getTaskId() + "-" + thermalEvent.getSourceTs();
        String thermalImageUrl = StringUtils.hasText(visibleEvent.getThermalImageUrl())
                ? visibleEvent.getThermalImageUrl()
                : thermalEvent.getThermalImageUrl();
        if (!StringUtils.hasText(thermalImageUrl)) {
            log.warn(
                    "dual-stream visible status skipped without associated thermal image task={} action={} visibleTs={} thermalTs={}",
                    visibleEvent.getTaskId(),
                    action,
                    visibleEvent.getSourceTs(),
                    thermalEvent.getSourceTs());
            return false;
        }
        boolean recorded = fireEventService.recordVisibleConfirmationStatus(
                eventId,
                sourceEventId,
                action,
                visibleEvent.getVisibleImageUrl(),
                Instant.ofEpochMilli(visibleEvent.getSourceTs()).toString(),
                thermalSourceEventId,
                thermalImageUrl);
        if (recorded && !REVIEW_STATUS_VISIBLE_PENDING.equals(action)) {
            confirmedThermalEventByTask.remove(visibleEvent.getTaskId());
            confirmedThermalFireEventIdByTask.remove(visibleEvent.getTaskId());
        }
        return recorded;
    }

    private boolean isVisibleTerminalStatus(String status) {
        return StringUtils.hasText(status)
                && status.startsWith("VISIBLE_")
                && !REVIEW_STATUS_VISIBLE_PENDING.equals(status)
                && !REVIEW_STATUS_VISIBLE_CONFIRMED.equals(status);
    }

    private Double resolveThermalTemperature(DualStreamEventDTO event) {
        // 只认随事件携带的实测温度。group 的 HUD 中心温度是探针对"画面最热点"的瞬时读数，
        // 日晒金属可到 80°C+，曾把 YOLO 零检出帧连环确认成火情（2026-07-25 实测），不再参与判定。
        return event == null ? null : event.getThermalTemperature();
    }

    private void refreshThermalSnapshotAnnotation(DualStreamEventDTO event) {
        Double thermalTemperature = event == null ? null : resolveThermalTemperature(event);
        if (event == null
                || thermalTemperature == null
                || !StringUtils.hasText(event.getTaskId())
                || event.getSourceTs() == null
                || !StringUtils.hasText(event.getThermalImageUrl())
                || !StringUtils.hasText(aiServiceBaseUrl)) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("thermal_temperature", thermalTemperature);
            payload.put("thermal_measure_roi", event.getThermalMeasureRoi());
            payload.put("thermal_detect_roi", event.getThermalMeasureRoi());
            payload.put("thermal_measurements", event.getThermalMeasurements());
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            String eventId = event.getTaskId() + "-" + event.getSourceTs();
            String encodedEventId = URLEncoder.encode(eventId, StandardCharsets.UTF_8);
            String url = aiServiceBaseUrl.replaceAll("/+$", "")
                    + "/api/v1/snapshots/"
                    + encodedEventId
                    + "/thermal-annotation";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = aiHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                event.setThermalImageUrl(withThermalAnnotationVersion(
                        event.getThermalImageUrl(),
                        event.getSourceTs()));
            }
        } catch (Exception ignored) {
            // Snapshot annotation refresh is best-effort; event temperature persistence must not fail with AI IO.
        }
    }

    private String withThermalAnnotationVersion(String imageUrl, Long sourceTs) {
        if (!StringUtils.hasText(imageUrl) || sourceTs == null) {
            return imageUrl;
        }
        String fragment = "";
        String base = imageUrl;
        int fragmentIndex = imageUrl.indexOf('#');
        if (fragmentIndex >= 0) {
            base = imageUrl.substring(0, fragmentIndex);
            fragment = imageUrl.substring(fragmentIndex);
        }
        String version = "thermal_v=" + sourceTs;
        int queryIndex = base.indexOf('?');
        if (queryIndex < 0) {
            return base + "?" + version + fragment;
        }
        String path = base.substring(0, queryIndex);
        String query = base.substring(queryIndex + 1);
        StringBuilder rebuilt = new StringBuilder(path).append('?').append(version);
        for (String param : query.split("&")) {
            if (param.isBlank() || param.startsWith("thermal_v=")) {
                continue;
            }
            rebuilt.append('&').append(param);
        }
        return rebuilt.append(fragment).toString();
    }

    private String resolveConfirmedVisibleImageUrl(DualStreamEventDTO event, DualStreamEventDTO visibleEvent) {
        if (visibleEvent != null
                && REVIEW_STATUS_VISIBLE_CONFIRMED.equals(visibleEvent.getReviewStatus())
                && StringUtils.hasText(visibleEvent.getVisibleImageUrl())) {
            return visibleEvent.getVisibleImageUrl();
        }
        if (isThermalConfirmation(event)
                && StringUtils.hasText(event.getVisibleImageUrl())
                && !StringUtils.hasText(event.getThermalImageUrl())) {
            return null;
        }
        return "visible".equals(normalize(event.getAnalysisChannel())) ? event.getVisibleImageUrl() : null;
    }

    private void rememberVisibleTrigger(DualStreamEventDTO event, String droneSn) {
        if (event == null || !StringUtils.hasText(event.getTaskId()) || !StringUtils.hasText(event.getVisibleImageUrl())) {
            return;
        }
        if (!isVisibleCaptureTrusted(droneSn)) {
            return;
        }
        DualStreamEventDTO current = visibleTriggerByTask.get(event.getTaskId());
        if (current != null && event.getSourceTs() == null) {
            return;
        }
        if (current != null
                && current.getSourceTs() != null
                && event.getSourceTs() != null
                && event.getSourceTs() < current.getSourceTs()) {
            return;
        }
        visibleTriggerByTask.put(event.getTaskId(), copyEvent(event));
    }

    private boolean isVisibleCaptureTrusted(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        if ("thermal".equals(inferChannelFromAppliedFocusCommand(droneSn))) {
            return false;
        }
        DualStreamLiveGroupDTO group = groups.get(droneSn);
        if (group == null) {
            group = restoreGroupFromRedis(droneSn);
        }
        if (group == null) {
            return false;
        }
        String currentMode = normalize(group.getCurrentMode());
        String visibleState = normalize(group.getVisibleState());
        String thermalState = normalize(group.getThermalState());
        return currentMode.contains("visible")
                || ("running".equals(visibleState) && !"running".equals(thermalState));
    }

    private String resolveConfirmedThermalImageUrl(DualStreamEventDTO event) {
        if (StringUtils.hasText(event.getThermalImageUrl())) {
            return event.getThermalImageUrl();
        }
        return null;
    }

    private boolean isThermalConfirmation(DualStreamEventDTO event) {
        return event != null && "thermal".equals(normalize(event.getAnalysisChannel()));
    }

    private double resolveConfirmedConfidence(DualStreamEventDTO event) {
        double thermalImageScore = resolveThermalImageScore(event);
        double temperatureConfidence = temperatureConfidence(resolveThermalTemperature(event));
        return clampConfidence(Math.max(thermalImageScore, temperatureConfidence));
    }

    private double clampConfidence(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String inferChannelFromGroup(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return "";
        }
        DualStreamLiveGroupDTO group = groups.get(droneSn);
        if (group == null) {
            group = restoreGroupFromRedis(droneSn);
        }
        if (group == null) {
            return "";
        }
        String currentMode = normalize(group.getCurrentMode());
        if (currentMode.contains("thermal")) {
            return "thermal";
        }
        if (currentMode.contains("visible")) {
            return "visible";
        }
        return "";
    }

    private String inferChannelFromAppliedFocusCommand(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return "";
        }
        DualStreamCommandDTO command = commandByDrone.get(droneSn);
        String status = command == null ? "" : normalize(command.getStatus());
        String action = command == null ? "" : normalize(command.getAction());
        if (!"applied".equals(status)) {
            DualStreamLiveGroupDTO group = groups.get(droneSn);
            if (group == null) {
                group = restoreGroupFromRedis(droneSn);
            }
            status = group == null ? "" : normalize(group.getLastCommandStatus());
            action = group == null ? "" : normalize(group.getLastCommandAction());
        }
        if (!"applied".equals(status)) {
            return "";
        }
        if ("focus-thermal".equals(action)) {
            return "thermal";
        }
        if ("focus-visible".equals(action)) {
            return "visible";
        }
        return "";
    }

    private boolean isFireDetectionActiveForAutoFocus(String droneSn) {
        // tracker 缺席（单测直接 new 实例）时保持旧行为；生产环境必须监测中才允许自动切红外。
        return fireDetectionActivityTracker == null || fireDetectionActivityTracker.isActive(droneSn);
    }

    private boolean shouldIssueFocus(String droneSn, String action) {
        DualStreamCommandDTO existing = commandByDrone.get(droneSn);
        if (existing == null) {
            return true;
        }
        String status = normalize(existing.getStatus());
        if ("pending".equals(status)) {
            return false;
        }
        if (action.equals(existing.getAction()) && "applied".equals(status)) {
            return !isFocusActionCurrentlyActive(droneSn, action);
        }
        return true;
    }

    private void requestThermalFocusAfterVisibleReview(String droneSn) {
        if (!StringUtils.hasText(droneSn) || !isFireDetectionActiveForAutoFocus(droneSn)) {
            return;
        }
        if (isWithinVisibleConfirmationGrace(droneSn)) {
            return;
        }
        DualStreamCommandDTO existing = commandByDrone.get(droneSn);
        if (existing != null
                && "focus-thermal".equals(existing.getAction())
                && COMMAND_STATUS_PENDING.equals(normalize(existing.getStatus()))) {
            return;
        }
        if (!isFocusActionCurrentlyActive(droneSn, "focus-thermal")) {
            issueCommand(droneSn, "focus-thermal");
        }
    }

    private boolean isWithinVisibleConfirmationGrace(String droneSn) {
        Long until = visibleConfirmationGraceUntilByDrone.get(droneSn);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            liftVisibleConfirmationGrace(droneSn);
            return false;
        }
        return true;
    }

    private void beginVisibleConfirmationGrace(String droneSn) {
        if (StringUtils.hasText(droneSn) && visibleConfirmationGraceMs > 0) {
            visibleConfirmationGraceUntilByDrone.put(
                    droneSn, System.currentTimeMillis() + visibleConfirmationGraceMs);
        }
    }

    /** 解除确认宽限期并恢复 agent 测温探针（若是我们暂停的且火情监测仍激活）。 */
    private void liftVisibleConfirmationGrace(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return;
        }
        visibleConfirmationGraceUntilByDrone.remove(droneSn);
        if (confirmationMonitorPausedByDrone.remove(droneSn) && isFireDetectionActiveForAutoFocus(droneSn)) {
            issueCommand(droneSn, "thermal-monitor-on");
        }
    }

    private boolean isFocusActionCurrentlyActive(String droneSn, String action) {
        if (!StringUtils.hasText(droneSn) || !StringUtils.hasText(action)) {
            return false;
        }
        DualStreamLiveGroupDTO group = groups.get(droneSn);
        if (group == null) {
            group = restoreGroupFromRedis(droneSn);
        }
        if (group == null) {
            return false;
        }
        String currentMode = normalize(group.getCurrentMode());
        String visibleState = normalize(group.getVisibleState());
        String thermalState = normalize(group.getThermalState());
        if ("focus-thermal".equals(action)) {
            return currentMode.contains("thermal") || "running".equals(thermalState);
        }
        if ("focus-visible".equals(action)) {
            return currentMode.contains("visible")
                    || ("running".equals(visibleState) && !"running".equals(thermalState));
        }
        return true;
    }

    private String resolveThermalPostMeasurementReviewStatus(DualStreamEventDTO event) {
        // 串行链裁决：实测温度达确认线即确认，未达线即拒绝，无中间态。
        if (!isTemperatureAtLeast(event.getThermalTemperature(), thermalWarmFloorC)) {
            return REVIEW_STATUS_THERMAL_REJECTED;
        }
        if (!hasThermalImage(event)) {
            return REVIEW_STATUS_THERMAL_IMAGE_MISSING;
        }
        return "THERMAL_CONFIRMED";
    }

    private boolean hasThermalImage(DualStreamEventDTO event) {
        return event != null && StringUtils.hasText(event.getThermalImageUrl());
    }

    private boolean hasFireDetection(DualStreamEventDTO event) {
        return resolveFireDetectionScore(event) >= FIRE_DETECTION_FLOOR;
    }

    private boolean hasVisibleFireDetection(DualStreamEventDTO event) {
        return hasVisibleFireDetection(event, null);
    }

    private boolean hasVisibleFireDetection(DualStreamEventDTO event, DualStreamEventDTO thermalContext) {
        double floor = isTemperatureAtLeast(resolveThermalTemperature(thermalContext), THERMAL_HIGH_TEMPERATURE_C)
                ? HIGH_TEMPERATURE_VISIBLE_CONFIRMATION_FLOOR
                : visibleConfirmFloor;
        return event != null
                && event.getVisibleScore() != null
                && clampConfidence(event.getVisibleScore()) >= floor;
    }

    private String resolveConfirmedFireLevel(DualStreamEventDTO event) {
        double thermalImageScore = resolveThermalImageScore(event);
        Double temperature = resolveThermalTemperature(event);
        if (isTemperatureAtLeast(temperature, THERMAL_HIGH_TEMPERATURE_C) || thermalImageScore >= 0.7) {
            return "HIGH";
        }
        if (isTemperatureAtLeast(temperature, THERMAL_MEDIUM_TEMPERATURE_C)
                || thermalImageScore >= 0.4
                || (thermalImageScore >= FIRE_DETECTION_FLOOR
                    && isTemperatureAtLeast(temperature, thermalWarmFloorC))) {
            return "MEDIUM";
        }
        return StringUtils.hasText(event.getRiskLevel()) ? event.getRiskLevel() : "UNKNOWN";
    }

    private double resolveThermalImageScore(DualStreamEventDTO event) {
        double score = 0.0;
        if (event.getThermalScore() != null) {
            score = Math.max(score, event.getThermalScore());
        }
        if (isThermalConfirmation(event) && event.getFusionScore() != null) {
            score = Math.max(score, event.getFusionScore());
        }
        return clampConfidence(score);
    }

    private double temperatureConfidence(Double temperature) {
        if (temperature == null || temperature < thermalWarmFloorC) {
            return 0.0;
        }
        if (temperature >= THERMAL_HIGH_TEMPERATURE_C) {
            return 1.0;
        }
        if (temperature >= THERMAL_MEDIUM_TEMPERATURE_C) {
            return 0.7;
        }
        return 0.3;
    }

    private boolean isTemperatureAtLeast(Double temperature, double threshold) {
        return temperature != null && temperature >= threshold;
    }

    private double resolveFireDetectionScore(DualStreamEventDTO event) {
        double score = 0.0;
        if (event.getThermalScore() != null) {
            score = Math.max(score, event.getThermalScore());
        }
        if (event.getFusionScore() != null) {
            score = Math.max(score, event.getFusionScore());
        }
        if (event.getVisibleScore() != null) {
            score = Math.max(score, event.getVisibleScore());
        }
        return clampConfidence(score);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "";
    }

    private boolean isUrgentAction(String action) {
        return URGENT_ACTIONS.contains(normalize(action));
    }

    private List<DualStreamEventDTO> copyEvents(List<DualStreamEventDTO> events) {
        List<DualStreamEventDTO> copies = new CopyOnWriteArrayList<>();
        for (DualStreamEventDTO event : events) {
            copies.add(copyEvent(event));
        }
        return copies;
    }

    private DualStreamCommandDTO copyCommand(DualStreamCommandDTO command) {
        if (command == null) {
            return null;
        }
        return new DualStreamCommandDTO()
                .setCommandId(command.getCommandId())
                .setDroneSn(command.getDroneSn())
                .setAction(command.getAction())
                .setStatus(command.getStatus())
                .setMessage(command.getMessage())
                .setUrgent(command.getUrgent())
                .setTaskId(command.getTaskId())
                .setSourceTs(command.getSourceTs())
                .setThermalMeasureRoi(command.getThermalMeasureRoi())
                .setThermalImageUrl(command.getThermalImageUrl())
                .setParams(command.getParams() == null ? null : new LinkedHashMap<>(command.getParams()))
                .setIssuedAt(command.getIssuedAt())
                .setAckedAt(command.getAckedAt());
    }
}
