package com.yx.uavfire.fc100.common.web;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 MDC 注入 requestId / missionNo / operatorId（妥 operatorId 等 JWT 接入再填）。
 * logback pattern: [%X{{requestId:-}} %X{{missionNo:-}} %X{{operatorId:-}}]
 *
 * @see "spec §5.6 日志规范"
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class Fc100RequestLoggingFilter extends OncePerRequestFilter {

    private static final Pattern MISSION_NO_PATTERN =
        Pattern.compile("/api/fire/missions/(MISSION-[\\w-]+)");

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse resp,
                                     FilterChain chain)
            throws ServletException, IOException {
        String reqId = req.getHeader("X-Request-Id");
        if (reqId == null || reqId.isBlank()) {
            reqId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("requestId", reqId);

        Matcher m = MISSION_NO_PATTERN.matcher(req.getRequestURI());
        if (m.find()) MDC.put("missionNo", m.group(1));

        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.clear();
        }
    }
}
