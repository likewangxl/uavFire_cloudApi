package com.yx.uavfire.fc100.event.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.yx.uavfire.common.util.JwtUtil;
import com.yx.uavfire.fc100.event.service.AgentFireReportIngress;
import com.yx.uavfire.fc100.event.service.AgentFireReportValidator;
import com.yx.uavfire.fc100.event.service.impl.UnavailableAgentFireReportIngress;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentClaim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentFireReportControllerTest {
    private AgentFireReportIngress ingress;
    private MockMvc mvc;

    @BeforeAll static void initJwt() {
        ReflectionTestUtils.setField(JwtUtil.class, "algorithm", Algorithm.HMAC256("agent-report-test-secret"));
    }

    @BeforeEach void setUp() {
        ingress = mock(AgentFireReportIngress.class);
        AgentFireReportController controller = new AgentFireReportController(
            ingress, new AgentFireReportValidator());
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("url.manage.prefix", "/manage")
            .addPlaceholderValue("url.manage.version", "/api/v1")
            .addInterceptors(new WaylineAgentAuthInterceptor())
            .build();
    }

    @Test void validInitialReturnsCommittedIdentity() throws Exception {
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.committed(true));
        mvc.perform(reportRequest()
                .contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value("event-1"))
            .andExpect(jsonPath("$.acceptedSequence").value(1))
            .andExpect(jsonPath("$.eventPersisted").value(true))
            .andExpect(jsonPath("$.notificationQueued").value(true))
            .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test void hashesAndPassesTheExactAuthenticatedRequestBytes() throws Exception {
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.committed(true));
        String raw = "  \n" + initial() + "\n";
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(raw))
            .andExpect(status().isOk());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(ingress).accept(any(), bytes.capture(), hash.capture());
        org.junit.jupiter.api.Assertions.assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8), bytes.getValue());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder();
        for (byte b : digest) expected.append(String.format("%02x", b & 0xff));
        org.junit.jupiter.api.Assertions.assertEquals(expected.toString(), hash.getValue());
    }

    @Test void exactDuplicateIs200AndConflictIs409() throws Exception {
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.duplicate(false));
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.conflict("payload hash mismatch"));
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isConflict());
    }

    @Test void acceptsPreciseAndDegradedTerminalMappings() throws Exception {
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.committed(false));
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(precise()))
            .andExpect(status().isOk());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(degraded()))
            .andExpect(status().isOk());
    }

    @Test void productionPlaceholderFailsUnavailableInsteadOfSynthesizingAck() throws Exception {
        AgentFireReportController controller = new AgentFireReportController(
            new UnavailableAgentFireReportIngress(), new AgentFireReportValidator());
        MockMvc unavailableMvc = MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("url.manage.prefix", "/manage")
            .addPlaceholderValue("url.manage.version", "/api/v1")
            .addInterceptors(new WaylineAgentAuthInterceptor()).build();
        unavailableMvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isServiceUnavailable());
    }

    @Test void requiresAuthenticUnexpiredSameDroneAgentToken() throws Exception {
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isUnauthorized());
        String forged = JWT.create().withClaim("role", WaylineAgentClaim.ROLE).withClaim("droneSn", "drone-1")
            .sign(Algorithm.HMAC256("forged-secret"));
        mvc.perform(reportRequest(forged).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isUnauthorized());
        mvc.perform(reportRequest(token("drone-1", -1_000)).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isUnauthorized());
        mvc.perform(reportRequest(token("other-drone", 60_000)).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isForbidden());
        verify(ingress, never()).accept(any(), any(), anyString());
    }

    @Test void rejectsPartialCoordinatesUnknownEnumsAndCrossStateFieldsBeforeIngress() throws Exception {
        String partial = degraded().replace("\"aircraft\":{\"lat\":34.1,\"lng\":108.9,\"alt\":120.0}",
            "\"aircraft\":{\"lat\":34.1,\"lng\":108.9}");
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(partial)).andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("\"FIRE\"", "\"EMBER\""))).andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("\"locationStatus\":\"LASER_LOCATING\"",
                "\"locationStatus\":\"LASER_LOCATING\",\"fireLat\":34.0"))).andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replaceFirst("}$", ",\"imageBase64\":\"forbidden\"}")))
            .andExpect(status().isBadRequest());
        verify(ingress, never()).accept(any(), any(), anyString());
    }

    @Test void rejectsRoiReleaseIdentityClockSkewAndInvalidTerminalSamples() throws Exception {
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("\"width\":0.2", "\"width\":0.8"))).andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("visible-fire-wechat-best2-20260728", "unapproved"))).andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replaceFirst("\\\"eventTimestamp\\\":\\d+", "\\\"eventTimestamp\\\":1")))
            .andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(initial().replaceFirst("\\\"eventTimestamp\\\":\\d+",
                "\\\"eventTimestamp\\\":" + Long.MIN_VALUE)))
            .andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(precise().replace("\"rangeMeters\":60.0", "\"rangeMeters\":-1.0"))).andExpect(status().isBadRequest());
        verify(ingress, never()).accept(any(), any(), anyString());
    }

    @Test void identityLengthsMatchEveryPersistenceDestinationAndRejectOverflowAs400() throws Exception {
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.committed(true));
        String maxAgent = "a".repeat(64), maxDrone = "d".repeat(64), maxEvent = "e".repeat(64);
        String max = initial()
            .replace("\"agentId\":\"agent-1\"", "\"agentId\":\"" + maxAgent + "\"")
            .replace("\"droneSn\":\"drone-1\"", "\"droneSn\":\"" + maxDrone + "\"")
            .replace("\"eventId\":\"event-1\"", "\"eventId\":\"" + maxEvent + "\"");
        mvc.perform(reportRequest(token(maxDrone, 60_000)).contentType(MediaType.APPLICATION_JSON).content(max))
            .andExpect(status().isOk());

        reset(ingress);
        String overAgent = initial().replace("\"agentId\":\"agent-1\"", "\"agentId\":\"" + "a".repeat(65) + "\"");
        String overDrone = initial().replace("\"droneSn\":\"drone-1\"", "\"droneSn\":\"" + "d".repeat(65) + "\"");
        String overEvent = initial().replace("\"eventId\":\"event-1\"", "\"eventId\":\"" + "e".repeat(65) + "\"");
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(overAgent)).andExpect(status().isBadRequest());
        mvc.perform(reportRequest(token("d".repeat(65), 60_000)).contentType(MediaType.APPLICATION_JSON).content(overDrone))
            .andExpect(status().isBadRequest());
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON).content(overEvent)).andExpect(status().isBadRequest());
        verify(ingress, never()).accept(any(), any(), anyString());
    }

    @Test void acceptsAuthenticatedHistoricalReplayButRejectsTooOldAndFutureObservations() throws Exception {
        when(ingress.accept(any(), any(), anyString())).thenReturn(AgentFireReportIngress.Result.committed(false));
        long outageReplay = System.currentTimeMillis() - 6 * 60 * 60 * 1000L;
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(withEventTimestamp(initial(), outageReplay)))
            .andExpect(status().isOk());
        verify(ingress).accept(any(), any(), anyString());

        long tooOld = System.currentTimeMillis() - AgentFireReportValidator.MAX_REPLAY_AGE_MILLIS - 1;
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(withEventTimestamp(initial(), tooOld)))
            .andExpect(status().isBadRequest());
        long future = System.currentTimeMillis() + AgentFireReportValidator.MAX_FUTURE_CLOCK_SKEW_MILLIS + 10_000;
        mvc.perform(reportRequest().contentType(MediaType.APPLICATION_JSON)
            .content(withEventTimestamp(initial(), future)))
            .andExpect(status().isBadRequest());
    }

    private String path() { return "/manage/api/v1/fire-events/agent-report"; }
    private MockHttpServletRequestBuilder reportRequest() {
        return reportRequest(token("drone-1", 60_000));
    }
    private MockHttpServletRequestBuilder reportRequest(String token) {
        return post(path()).header(WaylineAgentAuthInterceptor.HEADER_AGENT_TOKEN, token);
    }
    private String token(String droneSn, long expiresInMillis) {
        return JWT.create().withClaim("role", WaylineAgentClaim.ROLE).withClaim("droneSn", droneSn)
            .withExpiresAt(new Date(System.currentTimeMillis() + expiresInMillis)).sign(JwtUtil.algorithm);
    }
    private long now() { return System.currentTimeMillis(); }
    private String withEventTimestamp(String report, long timestamp) {
        return report.replaceFirst("\\\"eventTimestamp\\\":\\d+", "\\\"eventTimestamp\\\":" + timestamp);
    }
    private String common(String state, long seq) {
        return "\"agentId\":\"agent-1\",\"droneSn\":\"drone-1\",\"taskId\":\"task-1\"," +
            "\"eventId\":\"event-1\",\"sessionId\":\"session-1\",\"sequence\":" + seq + "," +
            "\"eventTimestamp\":" + now() + ",\"state\":\"" + state + "\",\"detectionKind\":\"FIRE\"," +
            "\"visibleRoi\":{\"x\":0.4,\"y\":0.4,\"width\":0.2,\"height\":0.2}," +
            "\"modelVersion\":\"visible-fire-wechat-best2-20260728\"," +
            "\"modelHash\":\"957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650\"," +
            "\"policyVersion\":\"agent-visible-v1\",\"inputSize\":960,\"runtime\":\"NCNN\"," +
            "\"sourceGeneration\":7,\"coordinatorGeneration\":9";
    }
    private String initial() { return "{" + common("VISUAL_CONFIRMED", 1) +
        ",\"confidence\":0.91,\"locationStatus\":\"LASER_LOCATING\"}"; }
    private String precise() {
        String sample = "{\"status\":\"NORMAL\",\"rangeMeters\":60.0,\"lat\":34.1,\"lng\":108.9,\"alt\":120.0,\"eventTimestamp\":" + now() + "}";
        return "{" + common("RESULT_DURABLE", 6) +
            ",\"locationStatus\":\"PRECISE\",\"geoMethod\":\"LASER_RANGEFINDER\"," +
            "\"fireLat\":34.1,\"fireLng\":108.9,\"fireAlt\":120.0,\"errorRadiusMeters\":5.0," +
            "\"laserSamples\":[" + sample + "," + sample + "," + sample + "]}";
    }
    private String degraded() { return "{" + common("RESULT_DURABLE", 6) +
        ",\"locationStatus\":\"DEGRADED_OSD\",\"geoMethod\":\"AIRCRAFT_OBSERVATION\"," +
        "\"aircraft\":{\"lat\":34.1,\"lng\":108.9,\"alt\":120.0},\"degradedReason\":\"LASER_SAMPLES_INVALID\"}"; }
}
