package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.manage.model.enums.UserTypeEnum;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/miniapp/api/v1")
public class MiniAppMeController {

    private final MiniAppProperties properties;

    public MiniAppMeController(MiniAppProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/me")
    public MiniAppResponse<Map<String, Object>> me(
            HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        CustomClaim claim = MiniAppControllerSupport.requireClaim(request);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", claim.getId());
        user.put("displayName", claim.getUsername());
        user.put("roles", List.of(UserTypeEnum.find(claim.getUserType()).name()));
        user.put("permissions", Collections.emptyList());

        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("workspaceId", claim.getWorkspaceId());
        // Legacy JWTs only contain the workspace id. A workspace adapter will resolve its display name later.
        workspace.put("name", claim.getWorkspaceId());

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("flightControlEnabled", properties.getFlightControl().isEnabled());
        capabilities.put("wechatAuthConfigured", properties.getWechat().isEnabled());
        capabilities.put("aircraftCompatibilityMode", "CAPABILITY_DRIVEN");
        capabilities.put("unknownAircraftPolicy", "READ_ONLY_FAIL_CLOSED");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", user);
        data.put("workspace", workspace);
        data.put("capabilities", capabilities);
        return MiniAppControllerSupport.success(data, request, response);
    }
}
