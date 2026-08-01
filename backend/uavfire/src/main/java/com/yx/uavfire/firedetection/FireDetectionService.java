package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FireDetectionService {

    private static final long AGENT_HEARTBEAT_TTL_MS = 15_000L;
    private static final Set<String> AGENT_CONNECTION_STATES = Set.of(
            "IDLE", "SDK_READY", "AIRCRAFT_CONNECTED", "CAPABILITY_READY", "STREAMING", "DEGRADED", "ERROR");
    private static final Set<String> AGENT_SESSION_STATES = Set.of(
            "INIT", "STARTING", "RUNNING", "STOPPING", "STOPPED", "FAILED");

    private final IDualStreamService dualStreamService;

    public boolean startForDrone(String droneSn) {
        return startForDrone(droneSn, null);
    }

    public boolean startForDrone(String droneSn, String ignoredVideoId) {
        return setIntent(droneSn, true);
    }

    public boolean stopForDrone(String droneSn) {
        return setIntent(droneSn, false);
    }

    public boolean isActiveForDrone(String droneSn) {
        return Boolean.TRUE.equals(statusForDrone(droneSn).get("running"));
    }

    public Map<String, Object> statusForDrone(String droneSn) {
        DualStreamLiveGroupDTO group = StringUtils.hasText(droneSn) ? dualStreamService.getGroup(droneSn) : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("drone_sn", droneSn);
        data.put("executor", "AGENT");
        Long observedAt = group == null ? null : group.getDetectorObservedAt();
        boolean stale = observedAt == null || System.currentTimeMillis() - observedAt > AGENT_HEARTBEAT_TTL_MS;
        boolean validRunningCombination = group != null
                && "STREAMING".equalsIgnoreCase(group.getConnectionState())
                && "RUNNING".equalsIgnoreCase(group.getSessionState())
                && "ARMED".equalsIgnoreCase(group.getDetectorIntent())
                && "ARMED".equalsIgnoreCase(group.getDetectorState())
                && "HEALTHY".equalsIgnoreCase(group.getDetectorHealth());
        boolean legalCombination = isLegalCombination(group);
        boolean running = !stale && validRunningCombination;
        data.put("running", running);
        data.put("heartbeat_stale", stale);
        if (stale) {
            data.put("status_reason", "agent-heartbeat-stale");
        } else if (!legalCombination) {
            data.put("status_reason", "invalid-agent-detector-state");
        } else if (!validRunningCombination) {
            data.put("status_reason", StringUtils.hasText(group.getDetectorReason())
                    ? group.getDetectorReason() : "agent-detector-not-running");
        }
        if (group != null) {
            putIfNotNull(data, "detector_intent", group.getDetectorIntent());
            putIfNotNull(data, "detector_state", group.getDetectorState());
            putIfNotNull(data, "detector_health", group.getDetectorHealth());
            putIfNotNull(data, "detector_reason", group.getDetectorReason());
            putIfNotNull(data, "detector_intent_version", group.getDetectorIntentVersion());
            putIfNotNull(data, "observed_at", group.getDetectorObservedAt());
        }
        return data;
    }

    private boolean isLegalCombination(DualStreamLiveGroupDTO group) {
        if (group == null
                || !AGENT_CONNECTION_STATES.contains(normalize(group.getConnectionState()))
                || !AGENT_SESSION_STATES.contains(normalize(group.getSessionState()))) {
            return false;
        }
        String intent = normalize(group.getDetectorIntent());
        String state = normalize(group.getDetectorState());
        String health = normalize(group.getDetectorHealth());
        if ("DISARMED".equals(intent)) {
            return "DISARMED".equals(state) && "HEALTHY".equals(health);
        }
        if (!"ARMED".equals(intent)) {
            return false;
        }
        return ("ARMED".equals(state) && "HEALTHY".equals(health))
                || ("BLOCKED".equals(state) && "UNHEALTHY".equals(health));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private boolean setIntent(String droneSn, boolean armed) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        DualStreamCommandDTO command = dualStreamService.setDetectorIntent(droneSn, armed);
        return command != null;
    }

    private void putIfNotNull(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
