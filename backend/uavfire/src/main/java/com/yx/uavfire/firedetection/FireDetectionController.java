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
        // video_id 仅为旧客户端兼容保留；Agent 直接消费 MSDK 可见光帧。
        String videoId = body.get("video_id");
        boolean ok = fireDetectionService.startForDrone(droneSn, videoId);
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("agent fire inference start failed");
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
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("agent fire inference stop failed");
    }
}
