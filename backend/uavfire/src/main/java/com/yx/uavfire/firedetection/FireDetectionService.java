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
            issueDualStreamCommand(droneSn, "thermal-monitor-on");
            // 启动即把画面切到红外（航线自动到第一航点触发时尤为重要——此路径没有前端来切焦点）。
            issueDualStreamCommand(droneSn, "focus-thermal");
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
        // 停掉 agent 端探测，并把相机切回可见光，确保空闲时主画面是可见光。
        issueDualStreamCommand(droneSn, "thermal-monitor-off");
        issueDualStreamCommand(droneSn, "focus-visible");
        return aiServiceClient.stopDetection(aiServiceClient.fireTaskIdForDrone(droneSn));
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
