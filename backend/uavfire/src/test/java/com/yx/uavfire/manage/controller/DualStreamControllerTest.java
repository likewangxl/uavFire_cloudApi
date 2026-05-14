package com.yx.uavfire.manage.controller;

import com.yx.uavfire.manage.model.dto.DualStreamCommandAckDTO;
import com.yx.uavfire.manage.model.dto.DualStreamEventDTO;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.model.dto.DualStreamLiveGroupDTO;
import com.yx.uavfire.manage.service.IDualStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DualStreamControllerTest {

    private MockMvc mockMvc;

    private IDualStreamService dualStreamService;

    @BeforeEach
    void setUp() {
        DualStreamController controller = new DualStreamController();
        dualStreamService = mock(IDualStreamService.class);
        ReflectionTestUtils.setField(controller, "dualStreamService", dualStreamService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addPlaceholderValue("url.manage.prefix", "/manage/api")
                .addPlaceholderValue("url.manage.version", "/v1")
                .build();
    }

    @Test
    void heartbeatStatusCapabilityEndpoints_updateAndExposeGroupSnapshot() throws Exception {
        when(dualStreamService.getGroup("DRONE-001"))
                .thenReturn(new DualStreamLiveGroupDTO()
                        .setDroneSn("DRONE-001")
                        .setSessionState("RUNNING")
                        .setLiveStatus("ONLINE")
                        .setCurrentMode("DUAL")
                        .setPlaybackStatus("awaiting-media-url")
                        .setVisibleSupported(true)
                        .setThermalSupported(true));

        mockMvc.perform(post("/manage/api/v1/dual-stream/agents/DRONE-001/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"droneSn\":\"DRONE-001\",\"connectionState\":\"STREAMING\",\"sessionState\":\"RUNNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/manage/api/v1/dual-stream/agents/DRONE-001/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"droneSn\":\"DRONE-001\",\"liveStatus\":\"ONLINE\",\"currentMode\":\"DUAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/manage/api/v1/dual-stream/agents/DRONE-001/capability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"droneSn\":\"DRONE-001\",\"visibleSupported\":true,\"thermalSupported\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/manage/api/v1/dual-stream/groups/DRONE-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.droneSn").value("DRONE-001"))
                .andExpect(jsonPath("$.data.sessionState").value("RUNNING"))
                .andExpect(jsonPath("$.data.liveStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.currentMode").value("DUAL"))
                .andExpect(jsonPath("$.data.playbackStatus").value("awaiting-media-url"))
                .andExpect(jsonPath("$.data.visibleSupported").value(true))
                .andExpect(jsonPath("$.data.thermalSupported").value(true));
    }

    @Test
    void commandEndpoints_supportIssuePollAndAck() throws Exception {
        when(dualStreamService.issueCommand("DRONE-001", "start"))
                .thenReturn(new DualStreamCommandDTO()
                        .setCommandId("cmd-start")
                        .setDroneSn("DRONE-001")
                        .setAction("start")
                        .setStatus("pending")
                        .setIssuedAt(1000L));
        when(dualStreamService.issueCommand("DRONE-001", "stop"))
                .thenReturn(new DualStreamCommandDTO()
                        .setCommandId("cmd-stop")
                        .setDroneSn("DRONE-001")
                        .setAction("stop")
                        .setStatus("pending")
                        .setIssuedAt(2000L));
        when(dualStreamService.issueCommand("DRONE-001", "focus-visible"))
                .thenReturn(new DualStreamCommandDTO()
                        .setCommandId("cmd-focus-visible")
                        .setDroneSn("DRONE-001")
                        .setAction("focus-visible")
                        .setStatus("pending")
                        .setIssuedAt(3000L));
        when(dualStreamService.issueCommand("DRONE-001", "focus-thermal"))
                .thenReturn(new DualStreamCommandDTO()
                        .setCommandId("cmd-focus-thermal")
                        .setDroneSn("DRONE-001")
                        .setAction("focus-thermal")
                        .setStatus("pending")
                        .setIssuedAt(4000L));
        when(dualStreamService.issueCommand("DRONE-001", "focus"))
                .thenReturn(new DualStreamCommandDTO()
                        .setCommandId("cmd-focus")
                        .setDroneSn("DRONE-001")
                        .setAction("focus")
                        .setStatus("pending")
                        .setIssuedAt(5000L));
        when(dualStreamService.pollCommand("DRONE-001"))
                .thenReturn(new DualStreamCommandDTO()
                        .setCommandId("cmd-start")
                        .setDroneSn("DRONE-001")
                        .setAction("start")
                        .setStatus("pending")
                        .setIssuedAt(1000L));

        mockMvc.perform(post("/manage/api/v1/dual-stream/groups/DRONE-001/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.droneSn").value("DRONE-001"))
                .andExpect(jsonPath("$.data.action").value("start"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.issuedAt").value(1000L));

        mockMvc.perform(post("/manage/api/v1/dual-stream/groups/DRONE-001/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("stop"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.issuedAt").value(2000L));

        mockMvc.perform(post("/manage/api/v1/dual-stream/groups/DRONE-001/focus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"focus-visible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("focus-visible"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.issuedAt").value(3000L));

        mockMvc.perform(post("/manage/api/v1/dual-stream/groups/DRONE-001/focus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"focus-thermal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("focus-thermal"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.issuedAt").value(4000L));

        mockMvc.perform(post("/manage/api/v1/dual-stream/groups/DRONE-001/focus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("focus"))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.issuedAt").value(5000L));

        mockMvc.perform(get("/manage/api/v1/dual-stream/agents/DRONE-001/command"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value("cmd-start"))
                .andExpect(jsonPath("$.data.action").value("start"))
                .andExpect(jsonPath("$.data.status").value("pending"));

        mockMvc.perform(post("/manage/api/v1/dual-stream/agents/DRONE-001/command/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"cmd-start\",\"status\":\"applied\",\"message\":\"start executed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(dualStreamService).acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
                .setCommandId("cmd-start")
                .setStatus("applied")
                .setMessage("start executed"));
    }

    @Test
    void taskEventEndpoint_acceptsAiEventCallback() throws Exception {
        mockMvc.perform(post("/manage/api/v1/dual-stream/tasks/task-001/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"droneSn\":\"DRONE-001\",\"fusionScore\":0.712,\"riskLevel\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(dualStreamService).acceptEvent("task-001", new DualStreamEventDTO()
                .setDroneSn("DRONE-001")
                .setFusionScore(0.712)
                .setRiskLevel("HIGH"));
    }

    @Test
    void taskEventsEndpoint_returnsStoredAiEvents() throws Exception {
        when(dualStreamService.listEvents("task-001"))
                .thenReturn(java.util.List.of(new DualStreamEventDTO()
                        .setTaskId("task-001")
                        .setDroneSn("DRONE-001")
                        .setFusionScore(0.712)
                        .setRiskLevel("HIGH")));

        mockMvc.perform(get("/manage/api/v1/dual-stream/tasks/task-001/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].taskId").value("task-001"))
                .andExpect(jsonPath("$.data[0].droneSn").value("DRONE-001"))
                .andExpect(jsonPath("$.data[0].riskLevel").value("HIGH"));
    }
}
