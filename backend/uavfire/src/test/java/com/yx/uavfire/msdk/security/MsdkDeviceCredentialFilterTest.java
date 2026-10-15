package com.yx.uavfire.msdk.security;

import com.yx.uavfire.msdk.config.MsdkDeviceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MsdkDeviceCredentialFilterTest {

    @Test
    void missingOrWrongTokenIsRejectedAndMatchingTokenPasses() throws Exception {
        assertEquals(401, filter("expected-token", null, "/api/v1/msdk/devices/state").getStatus());
        assertEquals(401, filter("expected-token", "wrong", "/api/v1/msdk/devices/AIRCRAFT-1/commands/poll").getStatus());
        assertEquals(401, filter("", "expected-token", "/api/v1/msdk/devices/AIRCRAFT-1/commands/ack").getStatus());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/msdk/devices/state");
        request.addHeader(MsdkDeviceCredentialFilter.HEADER, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("expected-token").doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertSame(request, chain.getRequest());
    }

    @Test
    void otherPathsStayOpen() throws Exception {
        MockHttpServletResponse response = filter("", null, "/manage/api/v1/login");
        assertEquals(200, response.getStatus());
        assertNull(response.getContentType());
    }

    private static MockHttpServletResponse filter(String expected, String presented, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (presented != null) {
            request.addHeader(MsdkDeviceCredentialFilter.HEADER, presented);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(expected).doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MsdkDeviceCredentialFilter filter(String expected) {
        MsdkDeviceProperties properties = new MsdkDeviceProperties();
        properties.setDeviceToken(expected);
        return new MsdkDeviceCredentialFilter(properties);
    }
}
