package com.yx.uavfire.firedetection;

import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FireDetectionService {

    private final AiServiceClient aiServiceClient;
    private final FireDetectionActivityTracker activityTracker;

    // 通过 dual-stream 命令通道告诉 RC Plus agent 开/关红外热区探测。
    // agent 默认不探测；只有监测启动时才允许它周期性切红外测温，避免空闲时画面被切走。
    @Autowired(required = false)
    private IDualStreamService dualStreamService;

    @Value("${ai-service.zlm-rtsp-host:127.0.0.1}")
    private String zlmRtspHost;

    @Value("${ai-service.zlm-rtsp-port:8554}")
    private int zlmRtspPort;

    public boolean startForDrone(String droneSn) {
        return startForDrone(droneSn, null);
    }

    public boolean startForDrone(String droneSn, String videoId) {
        if (!StringUtils.hasText(droneSn)) {
            return false;
        }
        String url = StringUtils.hasText(videoId)
                ? AiServiceClient.rtspUrlForVideoId(videoId, zlmRtspHost, zlmRtspPort)
                : rtspUrlForDrone(droneSn);
        boolean ok = aiServiceClient.startDetection(
                aiServiceClient.fireTaskIdForDrone(droneSn), droneSn, url, "");
        if (ok) {
            activityTracker.markActive(droneSn);
            // 纯可见光模式必须显式清掉 agent 进程中可能遗留的红外探针开关。
            // 仅仅“不再发送 thermal-monitor-on”无法把旧会话留下的 true 恢复为 false。
            issueDualStreamCommand(droneSn, "thermal-monitor-off");
            // 纯可见光识别：仅当镜头真在红外时才切回可见光——
            // agent 收到镜头命令会重建推流，无操作切换也会让直播卡顿。
            if (isThermalFocusActive(droneSn)) {
                issueDualStreamCommand(droneSn, "focus-visible");
            }
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
        // 用户意图是停止监测：无论 ai-service 停止是否成功，都不再允许自动切红外。
        activityTracker.markInactive(droneSn);
        // 停掉 agent 端探测；镜头在红外时切回可见光，确保空闲时主画面是可见光。
        issueDualStreamCommand(droneSn, "thermal-monitor-off");
        if (isThermalFocusActive(droneSn)) {
            issueDualStreamCommand(droneSn, "focus-visible");
        }
        return aiServiceClient.stopDetection(aiServiceClient.fireTaskIdForDrone(droneSn));
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

    private void issueDualStreamCommand(String droneSn, String action) {
        if (dualStreamService == null) {
            return;
        }
        try {
            dualStreamService.issueCommand(droneSn, action);
        } catch (RuntimeException ex) {
            // 命令通道异常不应阻断火情监测启停主流程。
        }
    }

    private String rtspUrlForDrone(String droneSn) {
        return "rtsp://" + zlmRtspHost + ":" + zlmRtspPort + "/live/" + droneSn + "-0";
    }
}
