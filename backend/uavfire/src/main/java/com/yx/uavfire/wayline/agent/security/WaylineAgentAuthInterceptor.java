package com.yx.uavfire.wayline.agent.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.yx.uavfire.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WaylineAgentAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_AGENT_TOKEN = "x-agent-token";
    public static final String HEADER_AGENT_TIMESTAMP = "x-agent-timestamp";
    public static final String HEADER_AGENT_NONCE = "x-agent-nonce";

    public static final String ATTR_CLAIM = "waylineAgentClaim";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Value("${wayline-agent.fire-event-require-https:true}")
    private boolean fireEventRequireHttps;

    @Value("${wayline-agent.fire-event-max-clock-skew-ms:300000}")
    private long fireEventMaxClockSkewMs;

    private final ConcurrentHashMap<String, Long> replayNonceExpiry = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader(HEADER_AGENT_TOKEN);
        if (!StringUtils.hasText(token)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            log.warn("wayline-agent: missing {} header for {}", HEADER_AGENT_TOKEN, request.getRequestURI());
            return false;
        }

        DecodedJWT decoded;
        try {
            JWTVerifier verifier = JWT.require(JwtUtil.algorithm).build();
            decoded = verifier.verify(token);
        } catch (JWTVerificationException e) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            log.warn("wayline-agent: token verify failed: {}", e.getMessage());
            return false;
        }

        String role = decoded.getClaim("role").asString();
        if (!WaylineAgentClaim.ROLE.equals(role)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            log.warn("wayline-agent: token role is {}, expected {}", role, WaylineAgentClaim.ROLE);
            return false;
        }

        String claimSn = decoded.getClaim("droneSn").asString();
        if (!StringUtils.hasText(claimSn)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return false;
        }
        String pathSn = extractDroneSn(request.getRequestURI());
        if (isAgentFireEventPath(request.getRequestURI()) && pathSn == null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            log.warn("wayline-agent: agent fire event task is not bound to a drone: {}", request.getRequestURI());
            return false;
        }
        if (pathSn != null && !pathSn.equals(claimSn)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            log.warn("wayline-agent: token droneSn={} does not match path droneSn={}", claimSn, pathSn);
            return false;
        }

        if (isProtectedFireRequest(request)) {
            if (fireEventRequireHttps && !isSecure(request)) {
                response.setStatus(HttpStatus.UPGRADE_REQUIRED.value());
                log.warn("wayline-agent: fire request rejected without HTTPS uri={}", request.getRequestURI());
                return false;
            }
            if (!acceptFreshNonce(request, claimSn)) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }
        }

        request.setAttribute(ATTR_CLAIM, new WaylineAgentClaim(claimSn, role));
        return true;
    }

    private String extractDroneSn(String uri) {
        String agentPattern = "/**/agents/{drone_sn}/**";
        if (PATH_MATCHER.match(agentPattern, uri)) {
            return PATH_MATCHER.extractUriTemplateVariables(agentPattern, uri).get("drone_sn");
        }

        String fireEventPattern = "/**/dual-stream/tasks/{task_id}/agent-fire-events";
        if (!PATH_MATCHER.match(fireEventPattern, uri)) {
            return null;
        }
        String taskId = PATH_MATCHER.extractUriTemplateVariables(fireEventPattern, uri).get("task_id");
        return StringUtils.hasText(taskId) && taskId.startsWith("fire-")
                ? taskId.substring("fire-".length())
                : null;
    }

    private boolean isAgentFireEventPath(String uri) {
        return PATH_MATCHER.match("/**/dual-stream/tasks/*/agent-fire-events", uri);
    }

    private boolean isProtectedFireRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.endsWith("/agent-fire-events")
                || uri.endsWith("/video-policy")
                || ("POST".equalsIgnoreCase(request.getMethod()) && uri.endsWith("/fire-evidence"));
    }

    private boolean isSecure(HttpServletRequest request) {
        return request.isSecure();
    }

    private boolean acceptFreshNonce(HttpServletRequest request, String claimSn) {
        String timestampHeader = request.getHeader(HEADER_AGENT_TIMESTAMP);
        String nonce = request.getHeader(HEADER_AGENT_NONCE);
        if (!StringUtils.hasText(timestampHeader)
                || !StringUtils.hasText(nonce)
                || nonce.length() > 128
                || !nonce.matches("[A-Za-z0-9._-]+")) {
            log.warn("wayline-agent: missing or invalid replay headers uri={}", request.getRequestURI());
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException invalid) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > fireEventMaxClockSkewMs) {
            log.warn("wayline-agent: stale fire request droneSn={} skewMs={}", claimSn, now - timestamp);
            return false;
        }
        replayNonceExpiry.entrySet().removeIf(entry -> entry.getValue() < now);
        String key = claimSn + ":" + nonce;
        return replayNonceExpiry.putIfAbsent(key, now + fireEventMaxClockSkewMs) == null;
    }
}
