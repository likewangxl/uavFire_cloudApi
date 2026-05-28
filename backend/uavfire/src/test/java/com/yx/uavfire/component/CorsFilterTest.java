package com.yx.uavfire.component;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorsFilterTest {

    private final CorsFilter filter = new CorsFilter();

    @Test
    void apiResponsesDisableBrowserCache() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/manage/api/v1/users/current");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("no-store, no-cache, max-age=0, must-revalidate", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals("0", response.getHeader("Expires"));
    }

    @Test
    void staticResourcesKeepDefaultCacheSemantics() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Pragma"));
        assertNull(response.getHeader("Expires"));
    }

    @Test
    void corsAllowsFc100IdempotencyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/fire/delivery/wayline-tasks/import-create");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("X-Idempotency-Key"));
    }
}
