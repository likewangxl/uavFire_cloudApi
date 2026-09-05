package com.yx.uavfire.miniapp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.common.util.JwtUtil;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.web.MiniAppErrorCode;
import com.yx.uavfire.miniapp.web.MiniAppRequestIds;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

@Component
public class MiniAppAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final MiniAppProperties properties;
    private final ObjectMapper objectMapper;

    public MiniAppAuthInterceptor(MiniAppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (!properties.isEnabled()) {
            writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    MiniAppErrorCode.MINIAPP_DISABLED, "微信小程序接口尚未启用");
            return false;
        }

        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    MiniAppErrorCode.AUTH_REQUIRED, "请先登录");
            return false;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    MiniAppErrorCode.AUTH_REQUIRED, "登录凭证无效");
            return false;
        }

        try {
            request.setAttribute(AuthInterceptor.TOKEN_CLAIM,
                    new CustomClaim(JwtUtil.verifyToken(token).getClaims()));
            return true;
        } catch (Exception exception) {
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    MiniAppErrorCode.AUTH_TOKEN_EXPIRED, "登录凭证无效或已过期");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        request.removeAttribute(AuthInterceptor.TOKEN_CLAIM);
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, MiniAppErrorCode code, String message) throws Exception {
        String requestId = MiniAppRequestIds.apply(request, response);
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), MiniAppResponse.error(requestId, code, message));
    }
}
