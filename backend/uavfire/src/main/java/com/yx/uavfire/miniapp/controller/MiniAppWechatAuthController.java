package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.model.WechatLoginRequest;
import com.yx.uavfire.miniapp.web.MiniAppErrorCode;
import com.yx.uavfire.miniapp.web.MiniAppException;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import com.yx.uavfire.miniapp.service.MiniAppSessionService;
import com.yx.uavfire.miniapp.service.WechatCodeGateway;
import com.yx.uavfire.miniapp.security.MiniAppLoginLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/miniapp/api/v1/auth")
public class MiniAppWechatAuthController {

    private final MiniAppProperties properties;
    private final WechatCodeGateway gateway;
    private final MiniAppSessionService sessions;
    private final MiniAppLoginLimiter limiter;

    public MiniAppWechatAuthController(MiniAppProperties properties, WechatCodeGateway gateway,
                                       MiniAppSessionService sessions, MiniAppLoginLimiter limiter) {
        this.properties = properties;
        this.gateway = gateway;
        this.sessions = sessions;
        this.limiter = limiter;
    }

    @PostMapping("/wechat/login")
    public MiniAppResponse<Map<String, Object>> login(
            @RequestBody WechatLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        validate(body);
        limiter.check(request.getRemoteAddr());
        String openId = gateway.exchange(body.getCode());
        return MiniAppControllerSupport.success(sessions.login(openId, body.getDeviceId()), request, response);
    }

    @PostMapping("/bindings")
    public MiniAppResponse<Map<String, Object>> bind(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        limiter.check(request.getRemoteAddr());
        return MiniAppControllerSupport.success(sessions.requestBinding(body.get("bindingTicket"), body.get("username"),
                body.get("displayName"), body.get("organization")), request, response);
    }

    @PostMapping("/refresh")
    public MiniAppResponse<Map<String, Object>> refresh(@RequestBody Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        limiter.check(request.getRemoteAddr());
        return MiniAppControllerSupport.success(sessions.refresh(body.get("refreshToken"), body.get("deviceId")), request, response);
    }

    @PostMapping("/logout")
    public MiniAppResponse<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        MiniAppControllerSupport.requireClaim(request);
        sessions.logout(request.getHeader("Authorization").substring(7).trim());
        return MiniAppControllerSupport.success(Map.of("loggedOut", true), request, response);
    }

    private void validate(WechatLoginRequest body) {
        if (body == null
                || !StringUtils.hasText(body.getCode())
                || body.getCode().length() > 256
                || !StringUtils.hasText(body.getDeviceId())
                || body.getDeviceId().length() < 8
                || body.getDeviceId().length() > 128
                || !StringUtils.hasText(body.getClientVersion())
                || body.getClientVersion().length() > 32) {
            throw new MiniAppException(HttpStatus.BAD_REQUEST,
                    MiniAppErrorCode.VALIDATION_ERROR,
                    "code、deviceId 和 clientVersion 不符合接口要求");
        }
    }
}
