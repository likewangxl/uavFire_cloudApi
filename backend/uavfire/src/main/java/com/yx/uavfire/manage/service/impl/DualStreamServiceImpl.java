package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import com.yx.uavfire.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.yx.uavfire.manage.model.dto.DualStreamAgentStatusDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
@Slf4j
public class DualStreamServiceImpl implements IDualStreamService {

    private static final double FIRE_DETECTION_FLOOR = 0.01;
    private static final double VISIBLE_CONFIRMATION_FLOOR = 0.5;
    private static final double HIGH_TEMPERATURE_VISIBLE_CONFIRMATION_FLOOR = 0.1;
    private static final double THERMAL_WEAK_IMAGE_FLOOR = 0.006;
    private static final double THERMAL_WARM_TEMPERATURE_C = 45.0;
    private static final double THERMAL_MEDIUM_TEMPERATURE_C = 60.0;
    private static final double THERMAL_HIGH_TEMPERATURE_C = 80.0;
    private static final long CONFIRMED_FIRE_EVENT_DEBOUNCE_MS = 60_000L;
    private static final long VISIBLE_ATTACHMENT_WINDOW_MS = 60_000L;
    private static final String COMMAND_STATUS_PENDING = "pending";
    private static final String COMMAND_STATUS_DISPATCHED = "dispatched";
    private static final String COMMAND_STATUS_EXPIRED = "expired";
    private static final String REVIEW_STATUS_THERMAL_MEASURING = "THERMAL_MEASURING";
    private static final String REVIEW_STATUS_THERMAL_MEASUREMENT_TIMEOUT = "THERMAL_MEASUREMENT_TIMEOUT";
    private static final String REVIEW_STATUS_THERMAL_IMAGE_MISSING = "THERMAL_IMAGE_MISSING";
    private static final String REVIEW_STATUS_THERMAL_REJECTED = "THERMAL_REJECTED";
    private static final String REVIEW_STATUS_THERMAL_NEEDS_VISIBLE_CONFIRM = "THERMAL_NEEDS_VISIBLE_CONFIRM";
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
            "measure-thermal-region"
    );

    private final Map<String, DualStreamLiveGroupDTO> groups = new ConcurrentHashMap<>();
    private final Map<String, List<DualStreamEventDTO>> taskEvents = new ConcurrentHashMap<>();
    private final Map<String, DualStreamCommandDTO> commandByDrone = new ConcurrentHashMap<>();
    private final Map<String, Deque<DualStreamCommandDTO>> commandQueueByDrone = new ConcurrentHashMap<>();
    private final Map<String, DualStreamEventDTO> visibleTriggerByTask = new ConcurrentHashMap<>();
    private final Map<String, Long> confirmedFireEventByTask = new ConcurrentHashMap<>();
    private final Map<String, Long> lastThermalMeasurementCompletedAtByDrone = new ConcurrentHashMap<>();
    private final Map<String, DualStreamEventDTO> thermalTriggerByTask = new ConcurrentHashMap<>();
    private final Map<String, DualStreamEventDTO> confirmedThermalEventByTask = new ConcurrentHashMap<>();
    private final Map<String, String> confirmedThermalFireEventIdByTask = new ConcurrentHashMap<>();

    @Value("${livestream.playback.webrtc-host:}")
    private String webrtcPlaybackHost;

    @Value("${livestream.playback.webrtc-port:#{null}}")
    private Integer webrtcPlaybackPort;

    @Value("${ai-service.base-url:http://127.0.0.1:9000}")
    private String aiServiceBaseUrl;

    @Value("${dual-stream.thermal-measurement-timeout-ms:20000}")
    private long thermalMeasurementTimeoutMs = 20_000L;

    @Value("${dual-stream.thermal-measurement-cooldown-ms:5000}")
    private long thermalMeasurementCooldownMs = 5_000L;

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
        if (!StringUtils.hasText(droneSn) || !StringUtils.hasText(action)) {
            return null;
        }
        DualStreamCommandDTO command = new DualStreamCommandDTO()
                .setCommandId(UUID.randomUUID().toString())
                .setDroneSn(droneSn)
                .setAction(action)
                .setUrgent(isUrgentAction(action) ? Boolean.TRUE : null)
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
                .setThermalMeasurements(event.getThermalMeasurements())
                .setGeoSnapshot(event.getGeoSnapshot())
                .setGeoQuality(event.getGeoQuality())
                .setGeoErrorRadiusM(event.getGeoErrorRadiusM())
                .setGeoMethod(event.getGeoMethod());
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

        if ("visible".equals(channel) && thermalTriggerByTask.containsKey(reviewed.getTaskId())) {
            DualStreamEventDTO thermalTrigger = thermalTriggerByTask.remove(reviewed.getTaskId());
            if (!hasThermalImage(thermalTrigger)) {
                reviewed.setReviewStatus(REVIEW_STATUS_THERMAL_IMAGE_MISSING);
                requestThermalFocusAfterVisibleReview(droneSn);
                return reviewed;
            }
            if (hasVisibleFireDetection(reviewed, thermalTrigger)) {
                reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_CONFIRMED);
                visibleTriggerByTask.put(reviewed.getTaskId(), copyEvent(reviewed));
                createConfirmedFireEvent(thermalTrigger, priorEvents);
            } else {
                reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_REJECTED);
            }
            requestThermalFocusAfterVisibleReview(droneSn);
            return reviewed;
        }

        if ("visible".equals(channel) && REVIEW_STATUS_VISIBLE_PENDING.equals(reviewed.getReviewStatus())) {
            recordVisibleStatusForRecentThermalConfirmation(reviewed, reviewed.getReviewStatus());
            return reviewed;
        }

        if ("visible".equals(channel) && isVisibleTerminalStatus(reviewed.getReviewStatus())) {
            recordVisibleStatusForRecentThermalConfirmation(reviewed, reviewed.getReviewStatus());
            requestThermalFocusAfterVisibleReview(droneSn);
            return reviewed;
        }

        if ("visible".equals(channel) && confirmedThermalEventByTask.containsKey(reviewed.getTaskId())) {
            if (hasVisibleFireDetection(reviewed, confirmedThermalEventByTask.get(reviewed.getTaskId()))) {
                if (attachVisibleImageToRecentThermalConfirmation(reviewed)) {
                    reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_CONFIRMED);
                    requestThermalFocusAfterVisibleReview(droneSn);
                    return reviewed;
                }
            } else if (StringUtils.hasText(reviewed.getVisibleImageUrl())) {
                reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_REJECTED);
                recordVisibleStatusForRecentThermalConfirmation(reviewed, REVIEW_STATUS_VISIBLE_REJECTED);
                requestThermalFocusAfterVisibleReview(droneSn);
                return reviewed;
            }
        }

        if ("visible".equals(channel)) {
            reviewed.setReviewStatus(REVIEW_STATUS_VISIBLE_SKIPPED_THERMAL_FIRST);
            rememberVisibleTrigger(reviewed, droneSn);
            if (isFireDetectionActiveForAutoFocus(droneSn) && shouldIssueFocus(droneSn, "focus-thermal")) {
                issueCommand(droneSn, "focus-thermal");
            }
            return reviewed;
        }

        if ("thermal".equals(channel)) {
            if (shouldMeasureThermalRegion(reviewed) && isFireDetectionActiveForAutoFocus(droneSn)) {
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
            boolean thermalConfirmed = hasThermalConfirmation(reviewed);
            if (thermalConfirmed && !hasThermalImage(reviewed)) {
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
            reviewed.setReviewStatus(thermalConfirmed ? "THERMAL_CONFIRMED" : "THERMAL_REJECTED");
            if ("THERMAL_CONFIRMED".equals(reviewed.getReviewStatus())) {
                createConfirmedFireEvent(reviewed, priorEvents);
            }
            if (shouldIssueVisibleFocusForThermalEvent(reviewed)) {
                issueCommand(droneSn, "focus-visible");
            }
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
                    createConfirmedFireEvent(event, events);
                    if (!thermalMeasurementAckRestoredVisible(ack)
                            && shouldIssueVisibleFocusForThermalEvent(event)) {
                        issueCommand(event.getDroneSn(), "focus-visible");
                    }
                } else if (REVIEW_STATUS_THERMAL_NEEDS_VISIBLE_CONFIRM.equals(event.getReviewStatus())) {
                    thermalTriggerByTask.put(taskId, copyEvent(event));
                }
                if (REVIEW_STATUS_THERMAL_NEEDS_VISIBLE_CONFIRM.equals(event.getReviewStatus())
                        && !thermalMeasurementAckRestoredVisible(ack)
                        && shouldIssueVisibleFocusForThermalEvent(event)) {
                    issueCommand(event.getDroneSn(), "focus-visible");
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
                && !isMsdkLocalVisibleSnapshotThermalEvent(event)
                && shouldIssueFocus(event.getDroneSn(), "focus-visible");
    }

    private boolean isMsdkLocalVisibleSnapshotThermalEvent(DualStreamEventDTO event) {
        if (event == null
                || !"thermal".equals(normalize(event.getAnalysisChannel()))
                || !hasThermalImage(event)
                || !StringUtils.hasText(event.getTaskId())
                || !StringUtils.hasText(event.getDroneSn())
                || !event.getTaskId().equals("fire-" + event.getDroneSn())) {
            return false;
        }
        DualStreamLiveGroupDTO group = groups.get(event.getDroneSn());
        if (group == null) {
            group = restoreGroupFromRedis(event.getDroneSn());
        }
        if (group == null) {
            return false;
        }
        String playbackStatus = normalize(group.getPlaybackStatus());
        String statusReason = normalize(group.getStatusReason());
        return "shared-side-by-side-preview".equals(playbackStatus)
                || statusReason.contains("single-liveview-source-shared-side-by-side-preview");
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
        if (event.getThermalTemperature() != null) {
            return event.getThermalTemperature();
        }
        if (!StringUtils.hasText(event.getDroneSn())) {
            return null;
        }
        DualStreamLiveGroupDTO group = groups.get(event.getDroneSn());
        if (group == null) {
            group = restoreGroupFromRedis(event.getDroneSn());
        }
        return group == null ? null : group.getThermalCenterTemperatureC();
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

    private boolean isRiskAtLeastMedium(DualStreamEventDTO event) {
        String riskLevel = normalize(event.getRiskLevel());
        if ("high".equals(riskLevel) || "medium".equals(riskLevel)) {
            return true;
        }
        Double fusionScore = event.getFusionScore();
        return fusionScore != null && fusionScore >= 0.4;
    }

    private boolean hasThermalConfirmation(DualStreamEventDTO event) {
        double thermalImageScore = resolveThermalImageScore(event);
        Double temperature = resolveThermalTemperature(event);
        if (temperature != null) {
            return isTemperatureAtLeast(temperature, THERMAL_MEDIUM_TEMPERATURE_C)
                    || (thermalImageScore >= THERMAL_WEAK_IMAGE_FLOOR
                        && isTemperatureAtLeast(temperature, THERMAL_WARM_TEMPERATURE_C));
        }
        return thermalImageScore >= FIRE_DETECTION_FLOOR;
    }

    private String resolveThermalPostMeasurementReviewStatus(DualStreamEventDTO event) {
        if (!hasThermalConfirmation(event)) {
            return REVIEW_STATUS_THERMAL_REJECTED;
        }
        if (!hasThermalImage(event)) {
            return REVIEW_STATUS_THERMAL_IMAGE_MISSING;
        }
        if (isTemperatureAtLeast(resolveThermalTemperature(event), THERMAL_HIGH_TEMPERATURE_C)) {
            return "THERMAL_CONFIRMED";
        }
        return REVIEW_STATUS_THERMAL_NEEDS_VISIBLE_CONFIRM;
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
                : VISIBLE_CONFIRMATION_FLOOR;
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
                    && isTemperatureAtLeast(temperature, THERMAL_WARM_TEMPERATURE_C))) {
            return "MEDIUM";
        }
        if (hasThermalConfirmation(event)) {
            return "LOW";
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
        if (temperature == null || temperature < THERMAL_WARM_TEMPERATURE_C) {
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
                .setIssuedAt(command.getIssuedAt())
                .setAckedAt(command.getAckedAt());
    }
}
