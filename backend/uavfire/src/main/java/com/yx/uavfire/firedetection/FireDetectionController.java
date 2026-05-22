package com.yx.uavfire.firedetection;

import com.dji.sdk.common.HttpResultResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.Map;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/fire-detection")
public class FireDetectionController {

    private final AiServiceClient aiServiceClient;
    private final String zlmRtspHost;
    private final int zlmRtspPort;

    public FireDetectionController(AiServiceClient aiServiceClient,
                                   @Value("${ai-service.zlm-rtsp-host:127.0.0.1}") String zlmRtspHost,
                                   @Value("${ai-service.zlm-rtsp-port:8554}") int zlmRtspPort) {
        this.aiServiceClient = aiServiceClient;
        this.zlmRtspHost = zlmRtspHost;
        this.zlmRtspPort = zlmRtspPort;
    }

    @PostMapping("/start")
    public HttpResultResponse start(@RequestBody Map<String, String> body) {
        String droneSn = body.get("drone_sn");
        if (!StringUtils.hasText(droneSn)) {
            return HttpResultResponse.error("drone_sn required");
        }
        // MSDK Migration Phase 1 (docs/MSDK_MIGRATION_PLAN.md):
        // Default URL points at the MSDK Agent's RTMP push (ZLM stream-id
        // "{drone_sn}-0", matching DjiLiveStreamController). When caller
        // explicitly supplies a Cloud SDK video_id we honour it — this is
        // the rollback rail for phase 1, removed in phase 2.
        String videoId = body.get("video_id");
        String url;
        if (StringUtils.hasText(videoId)) {
            url = AiServiceClient.rtspUrlForVideoId(videoId, zlmRtspHost, zlmRtspPort);
        } else {
            url = "rtsp://" + zlmRtspHost + ":" + zlmRtspPort + "/live/" + droneSn + "-0";
        }
        boolean ok = aiServiceClient.startDetection(
                aiServiceClient.fireTaskIdForDrone(droneSn), droneSn, url, "");
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("ai-service start failed");
    }

    @PostMapping("/stop")
    public HttpResultResponse stop(@RequestBody Map<String, String> body) {
        String droneSn = body.get("drone_sn");
        if (!StringUtils.hasText(droneSn)) {
            return HttpResultResponse.error("drone_sn required");
        }
        boolean ok = aiServiceClient.stopDetection(aiServiceClient.fireTaskIdForDrone(droneSn));
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("ai-service stop failed");
    }
}
