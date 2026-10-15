package com.yx.uavfire.msdk.security;

import com.yx.uavfire.msdk.config.MsdkDeviceProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Device credential for the MSDK paths that are outside the user-login interceptor. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MsdkDeviceCredentialFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Msdk-Device-Token";

    private final MsdkDeviceProperties properties;

    public MsdkDeviceCredentialFilter(MsdkDeviceProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.contains("/msdk/devices")) {
            return true;
        }
        return !(uri.endsWith("/msdk/devices/state")
                || uri.endsWith("/commands/poll")
                || uri.endsWith("/commands/ack"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!matches(properties.getDeviceToken(), request.getHeader(HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"msdk device credential rejected\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean matches(String expected, String presented) {
        if (expected == null || expected.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256(expected),
                sha256(presented));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
