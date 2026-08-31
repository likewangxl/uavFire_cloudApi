package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;

@Service
@RequiredArgsConstructor
public class FireDetectionService {

    private final FireDetectionActivityTracker activityTracker;

    // 通过 dual-stream 命令通道告诉 RC Plus agent 开/关红外热区探测。
    // agent 默认不探测；只有监测启动时才允许它周期性切红外测温，避免空闲时画面被切走。
    @Autowired(required = false)
    private IDualStreamService dualStreamService;

    @Autowired(required = false)
    private MsdkDeviceStateService msdkDeviceStateService;

    public boolean startForDrone(String droneSn) {
        return startForDrone(droneSn, null);
    }

    public boolean startForDrone(String droneSn, String videoId) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        if (msdkDeviceStateService != null) {
            MsdkDeviceStateDTO state = msdkDeviceStateService.get(droneSn).orElse(null);
            if (state != null && isM300(state)) {
                if (!selectedPayloadSupportsVisibleInference(state)) {
                    return false;
                }
            }
        }
        // videoId 保留在 API 中用于兼容旧前端，但端侧推理直接消费 MSDK RGBA 帧，不再拉取 RTSP。
        issueDualStreamCommand(droneSn, "thermal-monitor-off");
        if (isThermalFocusActive(droneSn)) {
            issueDualStreamCommand(droneSn, "focus-visible");
        }
        boolean ok = issueDualStreamCommand(droneSn, "visible-ai-on");
        if (ok) {
            activityTracker.markActive(droneSn);
        }
        return ok;
    }

    public boolean isActiveForDrone(String droneSn) {
        return activityTracker.isActive(droneSn);
    }

    public boolean stopForDrone(String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        // 用户意图是停止监测：先清活动状态，再关闭 agent 推理并恢复可见光。
        activityTracker.markInactive(droneSn);
        boolean stopped = issueDualStreamCommand(droneSn, "visible-ai-off");
        issueDualStreamCommand(droneSn, "thermal-monitor-off");
        if (isThermalFocusActive(droneSn)) {
            issueDualStreamCommand(droneSn, "focus-visible");
        }
        return stopped;
    }

    private boolean isThermalFocusActive(String droneSn) {
        if (dualStreamService == null) {
            return false;
        }
        try {
            var group = dualStreamService.getGroup(droneSn);
            String mode = group == null ? "" : String.valueOf(group.getCurrentMode());
            return mode.toUpperCase().contains("THERMAL");
        } catch (RuntimeException ex) {
            // 状态不可得时宁可不切：镜头默认就在可见光，多切一次反而断流。
            return false;
        }
    }

    private boolean isM300(MsdkDeviceStateDTO state) {
        String model = StringUtils.hasText(state.getAircraftModelKey())
                ? state.getAircraftModelKey() : state.getModel();
        if (!StringUtils.hasText(model)) return false;
        String normalized = model.toUpperCase().replace("_", "").replace("-", "");
        return "M300".equals(normalized) || "M300RTK".equals(normalized)
                || "MATRICE300RTK".equals(normalized);
    }

    private boolean selectedPayloadSupportsVisibleInference(MsdkDeviceStateDTO state) {
        if (state.getPayloads() == null || state.getSelectedPayloadPositionIndex() == null) return false;
        return state.getPayloads().stream()
                .filter(payload -> state.getSelectedPayloadPositionIndex().equals(payload.getPayloadPositionIndex()))
                .anyMatch(payload -> Boolean.TRUE.equals(payload.getVisibleSupported())
                        && !Boolean.FALSE.equals(payload.getLiveStreamSupported()));
    }

    private boolean issueDualStreamCommand(String droneSn, String action) {
        if (dualStreamService == null) {
            return false;
        }
        try {
            return dualStreamService.issueCommand(droneSn, action) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
