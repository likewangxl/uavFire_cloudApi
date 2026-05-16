package com.yx.uavfire.wayline.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import com.yx.uavfire.wayline.agent.service.WaylineEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WaylineAgentEventControllerTest {

    private MockMvc mockMvc;
    private WaylineEventStore store;

    @BeforeEach
    void setUp() {
        store = new WaylineEventStore();
        WaylineAgentEventController controller = new WaylineAgentEventController(store);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addPlaceholderValue("url.wayline-agent.prefix", "wayline-agent")
                .addPlaceholderValue("url.wayline-agent.version", "/api/v1")
                .build();
    }

    @Test
    void listEvents_returnsStoredRecordsForMission() throws Exception {
        store.append(new WaylineEventRecord("SN-A", "m-1", "wayline_state_change", 1000L, "payload-1"));
        store.append(new WaylineEventRecord("SN-A", "m-1", "wayline_progress", 2000L, "payload-2"));

        mockMvc.perform(get("/wayline-agent/api/v1/missions/m-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].method").value("wayline_state_change"))
                .andExpect(jsonPath("$.data[1].method").value("wayline_progress"));
    }

    @Test
    void listEvents_returnsEmptyArrayWhenMissionUnknown() throws Exception {
        mockMvc.perform(get("/wayline-agent/api/v1/missions/missing/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
