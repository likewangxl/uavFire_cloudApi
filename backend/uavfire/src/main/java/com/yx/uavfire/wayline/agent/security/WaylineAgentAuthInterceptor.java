package com.yx.uavfire.wayline.agent.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.yx.uavfire.common.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Component
public class WaylineAgentAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_AGENT_TOKEN = "x-agent-token";

    public static final String ATTR_CLAIM = "waylineAgentClaim";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

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
        String pathSn = extractDroneSn(request.getRequestURI());
        if (pathSn != null && !pathSn.equals(claimSn)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            log.warn("wayline-agent: token droneSn={} does not match path droneSn={}", claimSn, pathSn);
            return false;
        }

        request.setAttribute(ATTR_CLAIM, new WaylineAgentClaim(claimSn, role));
        return true;
    }

    private String extractDroneSn(String uri) {
        String pattern = "/**/agents/{drone_sn}/**";
        if (!PATH_MATCHER.match(pattern, uri)) {
            return null;
        }
        return PATH_MATCHER.extractUriTemplateVariables(pattern, uri).get("drone_sn");
    }
}
