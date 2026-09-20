package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.service.MiniAppReadService;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.util.Map;

@RestController
@RequestMapping("/miniapp/api/v1/dashboard")
public class MiniAppDashboardController {
    private final MiniAppProperties properties;
    private final MiniAppReadService reads;
    public MiniAppDashboardController(MiniAppProperties properties, MiniAppReadService reads) {
        this.properties = properties; this.reads = reads;
    }
    @GetMapping("/summary")
    public MiniAppResponse<Map<String, Object>> summary(HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        return MiniAppControllerSupport.success(reads.summary(MiniAppControllerSupport.requireClaim(request)), request, response);
    }
}
