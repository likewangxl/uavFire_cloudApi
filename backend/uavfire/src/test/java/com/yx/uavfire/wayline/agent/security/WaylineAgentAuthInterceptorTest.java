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
        ReflectionTestUtils.setField(interceptor, "fireEventRequireHttps", false);
        ReflectionTestUtils.setField(interceptor, "fireEventMaxClockSkewMs", 300_000L);
    }

    @Test
    void preHandle_rejectsStaleAndReplayedFireRequests() throws Exception {
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));
        String uri = "/manage/api/v1/dual-stream/tasks/fire-SN-A/agent-fire-events";

        MockHttpServletRequest stale = fireRequest(uri, token, "nonce-stale", System.currentTimeMillis() - 600_000L);
        MockHttpServletResponse staleResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(stale, staleResponse, new Object()));
        assertEquals(401, staleResponse.getStatus());

        MockHttpServletRequest first = fireRequest(uri, token, "nonce-once", System.currentTimeMillis());
        assertTrue(interceptor.preHandle(first, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest replay = fireRequest(uri, token, "nonce-once", System.currentTimeMillis());
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(replay, replayResponse, new Object()));
        assertEquals(401, replayResponse.getStatus());
    }

    @Test
    void preHandle_requiresHttpsForFireRequestsWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(interceptor, "fireEventRequireHttps", true);
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));
        MockHttpServletRequest request = fireRequest(
                "/manage/api/v1/dual-stream/tasks/fire-SN-A/agent-fire-events",
                token,
                "nonce-https",
                System.currentTimeMillis());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(426, response.getStatus());
    }

    @Test
    void preHandle_acceptsContainerVerifiedHttpsForFireRequests() throws Exception {
        ReflectionTestUtils.setField(interceptor, "fireEventRequireHttps", true);
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));
        MockHttpServletRequest request = fireRequest(
                "/manage/api/v1/dual-stream/tasks/fire-SN-A/agent-fire-events",
                token,
                "nonce-secure",
                System.currentTimeMillis());
        request.setSecure(true);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private MockHttpServletRequest fireRequest(String uri, String token, String nonce, long timestamp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
        request.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TIMESTAMP, Long.toString(timestamp));
        request.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_NONCE, nonce);
        return request;
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

    @Test
    void preHandle_acceptsAgentFireEventWhenTaskDroneMatchesClaim() throws Exception {
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));
        MockHttpServletRequest req = fireRequest(
                "/manage/api/v1/dual-stream/tasks/fire-SN-A/agent-fire-events",
                token,
                "nonce-task-match",
                System.currentTimeMillis());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void preHandle_rejectsAgentFireEventWhenTaskDroneDoesNotMatchClaim() throws Exception {
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));
        MockHttpServletRequest req = new MockHttpServletRequest(
                "POST", "/manage/api/v1/dual-stream/tasks/fire-SN-B/agent-fire-events");
        req.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(403, resp.getStatus());
    }

    @Test
    void preHandle_rejectsAgentFireEventWithoutFireTaskBinding() throws Exception {
        String token = JwtUtil.createToken(Map.of("role", WaylineAgentClaim.ROLE, "droneSn", "SN-A"));
        MockHttpServletRequest req = new MockHttpServletRequest(
                "POST", "/manage/api/v1/dual-stream/tasks/manual-task/agent-fire-events");
        req.addHeader(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(403, resp.getStatus());
    }
}
