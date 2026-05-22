package com.yx.uavfire.msdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.msdk.controller.MsdkDeviceController;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MsdkDeviceControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MsdkDeviceController controller = new MsdkDeviceController(new MsdkDeviceStateService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addPlaceholderValue("url.manage.prefix", "/manage/api")
                .addPlaceholderValue("url.manage.version", "/v1")
                .build();
    }

    @Test
    void stateEndpointStoresAndListsOnlineMsdkAircraft() throws Exception {
        mockMvc.perform(post("/manage/api/v1/msdk/devices/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewaySn\":\"RC_PLUS_LOCAL\",\"aircraftSn\":\"AIR-1\",\"online\":true,\"connectionState\":\"CONNECTED\",\"latitude\":34.1,\"longitude\":108.2,\"batteryPercent\":88}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/manage/api/v1/msdk/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].aircraftSn").value("AIR-1"))
                .andExpect(jsonPath("$.data[0].gatewaySn").value("RC_PLUS_LOCAL"))
                .andExpect(jsonPath("$.data[0].latitude").value(34.1))
                .andExpect(jsonPath("$.data[0].longitude").value(108.2))
                .andExpect(jsonPath("$.data[0].batteryPercent").value(88));
    }

    @Test
    void commandEndpointsQueuePollAndAckMsdkCommands() throws Exception {
        mockMvc.perform(post("/manage/api/v1/msdk/devices/AIR-1/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"return_home\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.commandId", startsWith("msdk-")))
                .andExpect(jsonPath("$.data.command").value("return_home"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        String response = mockMvc.perform(post("/manage/api/v1/msdk/devices/AIR-1/commands/poll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.command").value("return_home"))
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String commandId = new ObjectMapper().readTree(response).at("/data/commandId").asText();

        mockMvc.perform(post("/manage/api/v1/msdk/devices/AIR-1/commands/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"" + commandId + "\",\"status\":\"APPLIED\",\"message\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.message").value("ok"));
    }
}
