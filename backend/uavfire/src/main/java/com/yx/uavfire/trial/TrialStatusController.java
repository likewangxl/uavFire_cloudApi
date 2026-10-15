package com.yx.uavfire.trial;

import com.dji.sdk.common.HttpResultResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}")
public class TrialStatusController {

    private final TrialLicense trialLicense;

    public TrialStatusController(TrialLicense trialLicense) {
        this.trialLicense = trialLicense;
    }

    @GetMapping("/trial-status")
    public HttpResultResponse<Map<String, Object>> status() {
        return HttpResultResponse.success(trialLicense.publicStatus());
    }
}
