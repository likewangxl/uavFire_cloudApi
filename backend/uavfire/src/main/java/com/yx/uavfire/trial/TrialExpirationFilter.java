package com.yx.uavfire.trial;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;

/** Last line of defence for requests racing with application shutdown. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TrialExpirationFilter extends OncePerRequestFilter {

    private final TrialExpirationPolicy policy;

    public TrialExpirationFilter() {
        this(Clock.systemUTC());
    }

    TrialExpirationFilter(Clock clock) {
        this.policy = new TrialExpirationPolicy(clock);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!policy.isExpired()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":403,\"message\":\"" + TrialExpirationPolicy.EXPIRED_MESSAGE + "\"}");
    }
}
