package com.yx.uavfire.firedetection;

import com.dji.sdk.common.HttpResultResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/fire-detection")
public class FireDetectionController {

    private final FireDetectionService fireDetectionService;

    public FireDetectionController(FireDetectionService fireDetectionService) {
        this.fireDetectionService = fireDetectionService;
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
        boolean ok = fireDetectionService.startForDrone(droneSn, videoId);
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("ai-service start failed");
    }

    @GetMapping("/status")
    public HttpResultResponse status(@RequestParam("drone_sn") String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return HttpResultResponse.error("drone_sn required");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("drone_sn", droneSn);
        data.put("running", fireDetectionService.isActiveForDrone(droneSn));
        return HttpResultResponse.success(data);
    }

    @PostMapping("/stop")
    public HttpResultResponse stop(@RequestBody Map<String, String> body) {
        String droneSn = body.get("drone_sn");
        if (!StringUtils.hasText(droneSn)) {
            return HttpResultResponse.error("drone_sn required");
        }
        boolean ok = fireDetectionService.stopForDrone(droneSn);
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("ai-service stop failed");
    }
}
