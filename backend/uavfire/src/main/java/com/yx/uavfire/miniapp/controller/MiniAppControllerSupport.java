package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.web.MiniAppErrorCode;
import com.yx.uavfire.miniapp.web.MiniAppException;
import com.yx.uavfire.miniapp.web.MiniAppRequestIds;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import org.springframework.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final class MiniAppControllerSupport {

    private MiniAppControllerSupport() {
    }

    static void requireEnabled(MiniAppProperties properties) {
        if (!properties.isEnabled()) {
            throw new MiniAppException(HttpStatus.SERVICE_UNAVAILABLE,
                    MiniAppErrorCode.MINIAPP_DISABLED, "微信小程序接口尚未启用");
        }
    }

    static CustomClaim requireClaim(HttpServletRequest request) {
        Object claim = request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        if (!(claim instanceof CustomClaim)) {
            throw new MiniAppException(HttpStatus.UNAUTHORIZED,
                    MiniAppErrorCode.AUTH_REQUIRED, "请先登录");
        }
        return (CustomClaim) claim;
    }

    static <T> MiniAppResponse<T> success(T data, HttpServletRequest request, HttpServletResponse response) {
        return MiniAppResponse.success(MiniAppRequestIds.apply(request, response), data);
    }
}
