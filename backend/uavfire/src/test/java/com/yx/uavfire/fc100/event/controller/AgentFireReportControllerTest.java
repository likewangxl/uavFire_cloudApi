package com.yx.uavfire.fc100.event.controller;

import com.yx.uavfire.fc100.event.service.AgentFireReportIngress;
import com.yx.uavfire.fc100.event.service.AgentFireReportValidator;
import com.yx.uavfire.fc100.event.service.impl.UnavailableAgentFireReportIngress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentFireReportControllerTest {
    private AgentFireReportIngress ingress;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        ingress = mock(AgentFireReportIngress.class);
        AgentFireReportController controller = new AgentFireReportController(
            ingress, new AgentFireReportValidator());
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("url.manage.prefix", "/manage")
            .addPlaceholderValue("url.manage.version", "/api/v1")
            .build();
    }

    @Test void validInitialReturnsCommittedIdentity() throws Exception {
        when(ingress.accept(any())).thenReturn(AgentFireReportIngress.Result.committed(true));
        mvc.perform(post("/manage/api/v1/fire-events/agent-report")
                .contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value("event-1"))
            .andExpect(jsonPath("$.acceptedSequence").value(1))
            .andExpect(jsonPath("$.eventPersisted").value(true))
            .andExpect(jsonPath("$.notificationQueued").value(true))
            .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test void exactDuplicateIs200AndConflictIs409() throws Exception {
        when(ingress.accept(any())).thenReturn(AgentFireReportIngress.Result.duplicate(false));
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        when(ingress.accept(any())).thenReturn(AgentFireReportIngress.Result.conflict("payload hash mismatch"));
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isConflict());
    }

    @Test void acceptsPreciseAndDegradedTerminalMappings() throws Exception {
        when(ingress.accept(any())).thenReturn(AgentFireReportIngress.Result.committed(false));
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(precise()))
            .andExpect(status().isOk());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(degraded()))
            .andExpect(status().isOk());
    }

    @Test void productionPlaceholderFailsUnavailableInsteadOfSynthesizingAck() throws Exception {
        AgentFireReportController controller = new AgentFireReportController(
            new UnavailableAgentFireReportIngress(), new AgentFireReportValidator());
        MockMvc unavailableMvc = MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("url.manage.prefix", "/manage")
            .addPlaceholderValue("url.manage.version", "/api/v1").build();
        unavailableMvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(initial()))
            .andExpect(status().isServiceUnavailable());
    }

    @Test void rejectsPartialCoordinatesUnknownEnumsAndCrossStateFieldsBeforeIngress() throws Exception {
        String partial = degraded().replace("\"aircraft\":{\"lat\":34.1,\"lng\":108.9,\"alt\":120.0}",
            "\"aircraft\":{\"lat\":34.1,\"lng\":108.9}");
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(partial)).andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("\"FIRE\"", "\"EMBER\""))).andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("\"locationStatus\":\"LASER_LOCATING\"",
                "\"locationStatus\":\"LASER_LOCATING\",\"fireLat\":34.0"))).andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replaceFirst("}$", ",\"imageBase64\":\"forbidden\"}")))
            .andExpect(status().isBadRequest());
        verify(ingress, never()).accept(any());
    }

    @Test void rejectsRoiReleaseIdentityClockSkewAndInvalidTerminalSamples() throws Exception {
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("\"width\":0.2", "\"width\":0.8"))).andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replace("visible-fire-wechat-best2-20260728", "unapproved"))).andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replaceFirst("\\\"eventTimestamp\\\":\\d+", "\\\"eventTimestamp\\\":1")))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(initial().replaceFirst("\\\"eventTimestamp\\\":\\d+",
                "\\\"eventTimestamp\\\":" + Long.MIN_VALUE)))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
            .content(precise().replace("\"rangeMeters\":60.0", "\"rangeMeters\":-1.0"))).andExpect(status().isBadRequest());
        verify(ingress, never()).accept(any());
    }

    private String path() { return "/manage/api/v1/fire-events/agent-report"; }
    private long now() { return System.currentTimeMillis(); }
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
