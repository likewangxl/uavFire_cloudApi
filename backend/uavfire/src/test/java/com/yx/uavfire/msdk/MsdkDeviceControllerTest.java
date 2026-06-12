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
                        .content("{\"gatewaySn\":\"RC_PLUS_LOCAL\",\"aircraftSn\":\"AIR-1\",\"online\":true,\"connectionState\":\"CONNECTED\",\"deviceName\":\"DJI Matrice 4T\",\"model\":\"Matrice 4T\",\"latitude\":34.1,\"longitude\":108.2,\"batteryPercent\":88}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/manage/api/v1/msdk/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].aircraftSn").value("AIR-1"))
                .andExpect(jsonPath("$.data[0].gatewaySn").value("RC_PLUS_LOCAL"))
                .andExpect(jsonPath("$.data[0].deviceName").value("DJI Matrice 4T"))
                .andExpect(jsonPath("$.data[0].model").value("Matrice 4T"))
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

    @Test
    void getCommandEndpointExposesTerminalResultToOperator() throws Exception {
        String enqueue = mockMvc.perform(post("/manage/api/v1/msdk/devices/AIR-1/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"gimbal_reset\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String commandId = new ObjectMapper().readTree(enqueue).at("/data/commandId").asText();

        // Before the agent acks, the operator can read the pending status.
        mockMvc.perform(get("/manage/api/v1/msdk/devices/AIR-1/commands/" + commandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/manage/api/v1/msdk/devices/AIR-1/commands/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"" + commandId + "\",\"status\":\"FAILED\",\"message\":\"gimbal-busy\"}"))
                .andExpect(status().isOk());

        // The real agent execution result is now visible to the operator.
        mockMvc.perform(get("/manage/api/v1/msdk/devices/AIR-1/commands/" + commandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.message").value("gimbal-busy"));
    }

    @Test
    void navigationLightCommandIsAcceptedByTheWhitelist() throws Exception {
        mockMvc.perform(post("/manage/api/v1/msdk/devices/AIR-1/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"navigation_light\",\"params\":{\"enabled\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.command").value("navigation_light"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}
