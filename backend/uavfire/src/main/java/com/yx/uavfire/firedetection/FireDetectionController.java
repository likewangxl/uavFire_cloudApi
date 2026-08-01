package com.yx.uavfire.firedetection;

import com.dji.sdk.common.HttpResultResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

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
        boolean ok = fireDetectionService.startForDrone(droneSn);
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("agent arm command queue failed");
    }

    @GetMapping("/status")
    public HttpResultResponse status(@RequestParam("drone_sn") String droneSn) {
        if (!StringUtils.hasText(droneSn)) {
            return HttpResultResponse.error("drone_sn required");
        }
        return HttpResultResponse.success(fireDetectionService.statusForDrone(droneSn));
    }

    @PostMapping("/stop")
    public HttpResultResponse stop(@RequestBody Map<String, String> body) {
        String droneSn = body.get("drone_sn");
        if (!StringUtils.hasText(droneSn)) {
            return HttpResultResponse.error("drone_sn required");
        }
        boolean ok = fireDetectionService.stopForDrone(droneSn);
        return ok ? HttpResultResponse.success() : HttpResultResponse.error("agent disarm command queue failed");
    }
}
