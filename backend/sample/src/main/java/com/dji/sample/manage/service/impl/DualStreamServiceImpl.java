package com.dji.sample.manage.service.impl;

import com.dji.sample.manage.model.dto.DualStreamAgentCapabilityDTO;
import com.dji.sample.manage.model.dto.DualStreamAgentHeartbeatDTO;
import com.dji.sample.manage.model.dto.DualStreamAgentStatusDTO;
import com.dji.sample.manage.model.dto.DualStreamCommandAckDTO;
import com.dji.sample.manage.model.dto.DualStreamCommandDTO;
import com.dji.sample.manage.model.dto.DualStreamEventDTO;
import com.dji.sample.manage.model.dto.DualStreamLiveGroupDTO;
import com.dji.sample.manage.service.IDualStreamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class DualStreamServiceImpl implements IDualStreamService {

    private static final String GROUP_KEY_PREFIX = "dual-stream:group:";
    private static final String TASK_EVENTS_KEY_PREFIX = "dual-stream:task-events:";
    private static final String DEFAULT_VISIBLE_STREAM_SUFFIX = "-0";

    private final Map<String, DualStreamLiveGroupDTO> groups = new ConcurrentHashMap<>();
    private final Map<String, List<DualStreamEventDTO>> taskEvents = new ConcurrentHashMap<>();
    private final Map<String, DualStreamCommandDTO> commandByDrone = new ConcurrentHashMap<>();

    @Value("${livestream.playback.webrtc-host:}")
    private String webrtcPlaybackHost;

    @Value("${livestream.playback.webrtc-port:#{null}}")
    private Integer webrtcPlaybackPort;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private StreamSplitterService streamSplitterService;

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
            DualStreamEventDTO reviewedEvent = applySingleStreamReview(event).setTaskId(taskId);
            events.add(reviewedEvent);
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
            return copyEvents(cached);
        }

        List<DualStreamEventDTO> restored = restoreEventsFromRedis(taskId);
        if (restored == null) {
            return List.of();
        }
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
                .setStatus("pending")
                .setIssuedAt(System.currentTimeMillis());
        commandByDrone.put(droneSn, command);
        mergeGroup(droneSn, group -> group
                .setLastCommandAction(action)
                .setLastCommandStatus("pending"));
        return copyCommand(command);
    }

    @Override
    public DualStreamCommandDTO pollCommand(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return null;
        }
        return copyCommand(commandByDrone.get(droneSn));
    }

    @Override
    public void acknowledgeCommand(String droneSn, DualStreamCommandAckDTO ack) {
        if (!StringUtils.hasText(droneSn) || ack == null || !StringUtils.hasText(ack.getCommandId())) {
            return;
        }
        commandByDrone.computeIfPresent(droneSn, (sn, existing) -> {
            if (!Objects.equals(existing.getCommandId(), ack.getCommandId())) {
                return existing;
            }
            existing.setStatus(ack.getStatus())
                    .setMessage(ack.getMessage())
                    .setAckedAt(System.currentTimeMillis());
            mergeGroup(sn, group -> group
                    .setLastCommandAction(existing.getAction())
                    .setLastCommandStatus(existing.getStatus()));
            return existing;
        });
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
                .setIssuedAt(System.currentTimeMillis());
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
                String splitThermalStreamId = streamSplitterService == null
                        ? null
                        : streamSplitterService.startSplit(droneSn, sourceStreamId);
                if (splitThermalStreamId == null) {
                    group.setThermalPlayUrl(group.getVisiblePlayUrl());
                } else {
                    group.setVisiblePlayUrl(buildPlaybackUrl(
                            StreamSplitterService.getVisibleStreamId(sourceStreamId)));
                    group.setThermalPlayUrl(buildPlaybackUrl(
                            StreamSplitterService.getThermalStreamId(sourceStreamId)));
                }
            } else {
                if (streamSplitterService != null) {
                    streamSplitterService.stopSplit(droneSn);
                }
            }
        }

        return group;
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
                .setThermalSupported(group.getThermalSupported());
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
                .setReviewStatus(event.getReviewStatus());
    }

    private DualStreamEventDTO applySingleStreamReview(DualStreamEventDTO event) {
        DualStreamEventDTO reviewed = copyEvent(event);
        String droneSn = reviewed.getDroneSn();
        String channel = normalize(reviewed.getAnalysisChannel());
        String commandChannel = inferChannelFromAppliedFocusCommand(droneSn);
        if (StringUtils.hasText(commandChannel)) {
            channel = commandChannel;
            reviewed.setAnalysisChannel(channel);
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

        if ("visible".equals(channel) && isRiskAtLeastMedium(reviewed)) {
            reviewed.setReviewStatus("VISIBLE_SUSPECTED");
            if (shouldIssueFocus(droneSn, "focus-thermal")) {
                issueCommand(droneSn, "focus-thermal");
            }
            return reviewed;
        }

        if ("thermal".equals(channel)) {
            reviewed.setReviewStatus(isRiskAtLeastMedium(reviewed) ? "THERMAL_CONFIRMED" : "THERMAL_REJECTED");
            if (shouldIssueFocus(droneSn, "focus-visible")) {
                issueCommand(droneSn, "focus-visible");
            }
        }
        return reviewed;
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
        if (command == null || !"applied".equals(normalize(command.getStatus()))) {
            return "";
        }
        String action = normalize(command.getAction());
        if ("focus-thermal".equals(action)) {
            return "thermal";
        }
        if ("focus-visible".equals(action)) {
            return "visible";
        }
        return "";
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
            return false;
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

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "";
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
                .setIssuedAt(command.getIssuedAt())
                .setAckedAt(command.getAckedAt());
    }
}
