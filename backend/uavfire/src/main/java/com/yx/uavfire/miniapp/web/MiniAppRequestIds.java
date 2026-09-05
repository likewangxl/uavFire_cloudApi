package com.yx.uavfire.miniapp.web;

import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

public final class MiniAppRequestIds {

    public static final String HEADER = "X-Request-Id";

    private MiniAppRequestIds() {
    }

    public static String resolve(HttpServletRequest request) {
        Object existing = request.getAttribute(HEADER);
        if (existing instanceof String && StringUtils.hasText((String) existing)) {
            return (String) existing;
        }
        String candidate = request.getHeader(HEADER);
        String requestId = isSafe(candidate) ? candidate.trim() : UUID.randomUUID().toString();
        request.setAttribute(HEADER, requestId);
        return requestId;
    }

    public static String apply(HttpServletRequest request, HttpServletResponse response) {
        String requestId = resolve(request);
        response.setHeader(HEADER, requestId);
        return requestId;
    }

    private static boolean isSafe(String value) {
        if (!StringUtils.hasText(value) || value.length() > 128) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current < 0x21 || current > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
