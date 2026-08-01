package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FireDetectionService {

    private static final long AGENT_HEARTBEAT_TTL_MS = 15_000L;

    private final IDualStreamService dualStreamService;

    public boolean startForDrone(String droneSn) {
        return startForDrone(droneSn, null);
    }

    public boolean startForDrone(String droneSn, String ignoredVideoId) {
        return issue(droneSn, "visible-detector-arm");
    }

    public boolean stopForDrone(String droneSn) {
        return issue(droneSn, "visible-detector-disarm");
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
        boolean running = !stale && group != null
                && "ARMED".equalsIgnoreCase(group.getDetectorState())
                && "HEALTHY".equalsIgnoreCase(group.getDetectorHealth());
        data.put("running", running);
        data.put("heartbeat_stale", stale);
        if (stale) {
            data.put("status_reason", "agent-heartbeat-stale");
        }
        if (group != null) {
            putIfNotNull(data, "detector_intent", group.getDetectorIntent());
            putIfNotNull(data, "detector_state", group.getDetectorState());
            putIfNotNull(data, "detector_health", group.getDetectorHealth());
            putIfNotNull(data, "detector_reason", group.getDetectorReason());
            putIfNotNull(data, "observed_at", group.getDetectorObservedAt());
        }
        return data;
    }

    private boolean issue(String droneSn, String action) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        DualStreamCommandDTO command = dualStreamService.issueCommand(droneSn, action);
        return command != null;
    }

    private void putIfNotNull(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
