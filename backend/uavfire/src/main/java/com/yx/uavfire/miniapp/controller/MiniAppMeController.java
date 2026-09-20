package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.service.MiniAppReadService;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.util.Map;

@RestController
@RequestMapping("/miniapp/api/v1")
public class MiniAppMeController {
    private final MiniAppProperties properties;
    private final MiniAppReadService reads;
    public MiniAppMeController(MiniAppProperties properties, MiniAppReadService reads) {
        this.properties = properties; this.reads = reads;
    }
    @GetMapping("/me")
    public MiniAppResponse<Map<String, Object>> me(HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        return MiniAppControllerSupport.success(reads.me(MiniAppControllerSupport.requireClaim(request)), request, response);
    }
}
