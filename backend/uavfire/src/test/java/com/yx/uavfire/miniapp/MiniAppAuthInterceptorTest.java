package com.yx.uavfire.miniapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.security.MiniAppAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MiniAppAuthInterceptorTest {

    private MiniAppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MiniAppProperties();
        properties.setEnabled(true);
    }

    @Test
    void missingBearerTokenReturnsContractShapedUnauthorizedResponse() throws Exception {
        MiniAppAuthInterceptor interceptor = new MiniAppAuthInterceptor(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/miniapp/api/v1/me");
        request.addHeader("X-Request-Id", "req-from-miniapp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertEquals("req-from-miniapp", response.getHeader("X-Request-Id"));
        assertEquals("AUTH_REQUIRED",
                new ObjectMapper().readTree(response.getContentAsString()).path("code").asText());
    }

    @Test
    void disabledMiniAppFailsClosedBeforeAuthentication() throws Exception {
        properties.setEnabled(false);
        MiniAppAuthInterceptor interceptor = new MiniAppAuthInterceptor(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/miniapp/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(503, response.getStatus());
        assertEquals("MINIAPP_DISABLED",
                new ObjectMapper().readTree(response.getContentAsString()).path("code").asText());
    }
}
