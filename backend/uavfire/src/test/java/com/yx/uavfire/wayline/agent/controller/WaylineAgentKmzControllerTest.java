package com.yx.uavfire.wayline.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.wayline.agent.model.WaylineAgentKmzEntry;
import com.yx.uavfire.wayline.agent.service.IWaylineAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaylineAgentKmzControllerTest {

    private MockMvc mockMvc;
    private IWaylineAgentService service;

    @BeforeEach
    void setUp() {
        WaylineAgentKmzController controller = new WaylineAgentKmzController();
        service = mock(IWaylineAgentService.class);
        ReflectionTestUtils.setField(controller, "waylineAgentService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(new ObjectMapper()),
                        new ByteArrayHttpMessageConverter())
                .addPlaceholderValue("url.wayline-agent.prefix", "wayline-agent")
                .addPlaceholderValue("url.wayline-agent.version", "/api/v1")
                .build();
    }

    @Test
    void downloadKmz_returnsBytesAndMd5HeaderWhenRegistered() throws Exception {
        byte[] kmzBytes = "fake-kmz-bytes".getBytes();
        when(service.getKmz("SN-A", "m-1"))
                .thenReturn(Optional.of(new WaylineAgentKmzEntry("m-1", kmzBytes, "abc123md5")));

        byte[] body = mockMvc.perform(get("/wayline-agent/api/v1/agents/SN-A/missions/m-1/kmz"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Kmz-Md5", "abc123md5"))
                .andExpect(header().longValue("Content-Length", kmzBytes.length))
                .andExpect(content().contentType("application/octet-stream"))
                .andReturn().getResponse().getContentAsByteArray();

        assertArrayEquals(kmzBytes, body);
    }

    @Test
    void downloadKmz_returns404WhenNotRegistered() throws Exception {
        when(service.getKmz("SN-A", "m-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/wayline-agent/api/v1/agents/SN-A/missions/m-missing/kmz"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadKmz_returns404WhenServiceHasDifferentMissionForDrone() throws Exception {
        when(service.getKmz("SN-A", "m-2")).thenReturn(Optional.empty());

        mockMvc.perform(get("/wayline-agent/api/v1/agents/SN-A/missions/m-2/kmz"))
                .andExpect(status().isNotFound());
    }
}
