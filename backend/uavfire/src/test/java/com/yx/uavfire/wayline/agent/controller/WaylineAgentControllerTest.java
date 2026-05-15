package com.yx.uavfire.wayline.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandAckDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentCommandDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO;
import com.yx.uavfire.wayline.agent.service.IWaylineAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaylineAgentControllerTest {

    private MockMvc mockMvc;
    private IWaylineAgentService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        WaylineAgentController controller = new WaylineAgentController();
        service = mock(IWaylineAgentService.class);
        ReflectionTestUtils.setField(controller, "waylineAgentService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addPlaceholderValue("url.wayline-agent.prefix", "wayline-agent")
                .addPlaceholderValue("url.wayline-agent.version", "/api/v1")
                .build();
    }

    @Test
    void pollCommand_returnsServiceResult() throws Exception {
        when(service.pollCommand("SN-A")).thenReturn(new WaylineAgentCommandDTO()
                .setTid("tid-1")
                .setBid("m-1")
                .setMethod("wayline_dispatch"));

        mockMvc.perform(get("/wayline-agent/api/v1/agents/SN-A/command"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tid").value("tid-1"))
                .andExpect(jsonPath("$.data.bid").value("m-1"))
                .andExpect(jsonPath("$.data.method").value("wayline_dispatch"));
    }

    @Test
    void pollCommand_returnsNullDataWhenQueueEmpty() throws Exception {
        when(service.pollCommand("SN-A")).thenReturn(null);

        mockMvc.perform(get("/wayline-agent/api/v1/agents/SN-A/command"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void acknowledgeCommand_forwardsTidAndResultToService() throws Exception {
        WaylineAgentCommandAckDTO body = new WaylineAgentCommandAckDTO()
                .setTid("tid-1")
                .setResult(0)
                .setOutput("ok");

        mockMvc.perform(post("/wayline-agent/api/v1/agents/SN-A/command/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());

        ArgumentCaptor<WaylineAgentCommandAckDTO> captor = ArgumentCaptor.forClass(WaylineAgentCommandAckDTO.class);
        verify(service).acknowledgeCommand(eq("SN-A"), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("tid-1", captor.getValue().getTid());
        org.junit.jupiter.api.Assertions.assertEquals(0, captor.getValue().getResult());
    }

    @Test
    void dispatch_invokesServiceWithDeserializedBody() throws Exception {
        when(service.dispatchWayline(eq("SN-A"), any(WaylineDispatchDataDTO.class)))
                .thenReturn(new WaylineAgentCommandDTO().setTid("tid-new").setMethod("wayline_dispatch"));

        WaylineDispatchDataDTO body = new WaylineDispatchDataDTO()
                .setMissionId("m-1")
                .setKmzUrl("http://x/y.kmz")
                .setKmzMd5("md5-abc");

        mockMvc.perform(post("/wayline-agent/api/v1/agents/SN-A/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tid").value("tid-new"));

        ArgumentCaptor<WaylineDispatchDataDTO> captor = ArgumentCaptor.forClass(WaylineDispatchDataDTO.class);
        verify(service).dispatchWayline(eq("SN-A"), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("m-1", captor.getValue().getMissionId());
        org.junit.jupiter.api.Assertions.assertEquals("md5-abc", captor.getValue().getKmzMd5());
    }

    @Test
    void pauseResumeStopQueryBreakpoint_routeToCorrectServiceMethod() throws Exception {
        when(service.pauseMission(eq("SN-A"), any())).thenReturn(new WaylineAgentCommandDTO().setMethod("wayline_pause"));
        when(service.resumeMission(eq("SN-A"), any())).thenReturn(new WaylineAgentCommandDTO().setMethod("wayline_resume"));
        when(service.stopMission(eq("SN-A"), any())).thenReturn(new WaylineAgentCommandDTO().setMethod("wayline_stop"));

        String ctrl = json.writeValueAsString(new WaylineControlDataDTO().setMissionId("m-1"));

        mockMvc.perform(post("/wayline-agent/api/v1/agents/SN-A/pause")
                        .contentType(MediaType.APPLICATION_JSON).content(ctrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("wayline_pause"));

        mockMvc.perform(post("/wayline-agent/api/v1/agents/SN-A/resume")
                        .contentType(MediaType.APPLICATION_JSON).content(ctrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("wayline_resume"));

        mockMvc.perform(post("/wayline-agent/api/v1/agents/SN-A/stop")
                        .contentType(MediaType.APPLICATION_JSON).content(ctrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("wayline_stop"));

        verify(service).pauseMission(eq("SN-A"), any());
        verify(service).resumeMission(eq("SN-A"), any());
        verify(service).stopMission(eq("SN-A"), any());
    }
}
