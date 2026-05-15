package com.yx.uavfire.wayline.agent.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.yx.uavfire.common.util.JwtUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaylineAgentAuthInterceptorTest {

    private static final String SECRET = "test-secret";

    private WaylineAgentAuthInterceptor interceptor;

    @BeforeAll
    static void initJwt() {
        ReflectionTestUtils.setField(JwtUtil.class, "algorithm", Algorithm.HMAC256(SECRET));
        ReflectionTestUtils.setField(JwtUtil.class, "age", 3600_000L);
    }

    @BeforeEach
    void setUp() {
        interceptor = new WaylineAgentAuthInterceptor();
    }

    @Test
    void preHandle_rejectsRequestWithoutToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/wayline-agent/api/v1/agents/SN-A/command");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(req, resp, new Object());

        assertFalse(ok);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void preHandle_rejectsTokenWithInvalidSignature() throws Exception {
        String bogus = JWT.create()
                .withClaim("role", WaylineAgentClaim.ROLE)
                .withClaim("droneSn", "SN-A")
                .sign(Algorithm.HMAC256("different-secret"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/wayline-agent/api/v1/agents/SN-A/command");
        req.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, bogus);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(req, resp, new Object());

        assertFalse(ok);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void preHandle_rejectsTokenWithWrongRole() throws Exception {
        String token = JWT.create()
                .withClaim("role", "user")
                .withClaim("droneSn", "SN-A")
                .sign(JwtUtil.algorithm);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/wayline-agent/api/v1/agents/SN-A/command");
        req.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(req, resp, new Object());

        assertFalse(ok);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void preHandle_rejectsWhenPathDroneSnDoesNotMatchClaim() throws Exception {
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/wayline-agent/api/v1/agents/SN-B/command");
        req.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(req, resp, new Object());

        assertFalse(ok);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void preHandle_acceptsValidTokenAndPopulatesClaim() throws Exception {
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/wayline-agent/api/v1/agents/SN-A/command");
        req.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(req, resp, new Object());

        assertTrue(ok);
        assertEquals(200, resp.getStatus());
        WaylineAgentClaim claim = (WaylineAgentClaim) req.getAttribute(WaylineAgentAuthInterceptor.ATTR_CLAIM);
        assertNotNull(claim);
        assertEquals("SN-A", claim.getDroneSn());
        assertEquals(WaylineAgentClaim.ROLE, claim.getRole());
    }
}
