package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.model.WechatLoginRequest;
import com.yx.uavfire.miniapp.web.MiniAppErrorCode;
import com.yx.uavfire.miniapp.web.MiniAppException;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
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

    public MiniAppWechatAuthController(MiniAppProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/wechat/login")
    public MiniAppResponse<Map<String, Object>> login(
            @RequestBody WechatLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        validate(body);

        // Deliberately fail closed. The next iteration will exchange the one-time code on the
        // server, bind openid to an approved system account, then mint short-lived tokens.
        throw new MiniAppException(HttpStatus.SERVICE_UNAVAILABLE,
                MiniAppErrorCode.WECHAT_API_UNAVAILABLE,
                "微信登录服务尚未完成服务端配置");
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
