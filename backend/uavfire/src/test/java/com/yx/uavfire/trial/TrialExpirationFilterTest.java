package com.yx.uavfire.trial;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TrialExpirationFilterTest {

    @Test
    void passesRequestsBeforeCutoff() throws Exception {
        TrialExpirationFilter filter = filterAt("2026-09-30T15:59:59.999Z");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertSame(request, chain.getRequest());
    }

    @Test
    void rejectsRequestsAtCutoff() throws Exception {
        TrialExpirationFilter filter = filterAt("2026-09-30T16:00:00Z");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals(
                "{\"code\":403,\"message\":\"" + TrialExpirationPolicy.EXPIRED_MESSAGE + "\"}",
                response.getContentAsString());
    }

    private TrialExpirationFilter filterAt(String instant) {
        return new TrialExpirationFilter(
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
