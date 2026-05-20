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
        String videoId = StringUtils.hasText(body.get("video_id"))
                ? body.get("video_id")
                : AiServiceClient.defaultVideoIdForDrone(droneSn);
        String url = AiServiceClient.rtspUrlForVideoId(videoId, zlmRtspHost, zlmRtspPort);
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
