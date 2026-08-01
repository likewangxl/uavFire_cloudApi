package com.yx.uavfire.fc100.event.controller;

import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.service.FireEventService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FireEventControllerContractTest {

    @Test
    void listSerializesAgentSafetyFieldsRequiredByTheWebClient() throws Exception {
        FireEventService service = mock(FireEventService.class);
        FireEventDTO event = new FireEventDTO();
        event.setEventId("event-1");
        event.setNotificationVersion(2);
        event.setLastAgentSequence(9L);
        event.setUpdateTime(2_000L);
        event.setDetectionKind("FIRE");
        event.setDetectionStatus("RESULT_DURABLE");
        event.setLocationStatus("PRECISE");
        event.setFlightStatus("MISSION_RESUMED");
        event.setGeoMethod("LASER_RANGEFINDER");
        event.setLat(34.8);
        event.setLng(109.2);
        when(service.list(any(), any(), anyInt())).thenReturn(List.of(event));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FireEventController(service)).build();

        mvc.perform(get("/api/fire/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].eventId").value("event-1"))
            .andExpect(jsonPath("$.data[0].notificationVersion").value(2))
            .andExpect(jsonPath("$.data[0].lastAgentSequence").value(9))
            .andExpect(jsonPath("$.data[0].updateTime").value(2000))
            .andExpect(jsonPath("$.data[0].detectionKind").value("FIRE"))
            .andExpect(jsonPath("$.data[0].detectionStatus").value("RESULT_DURABLE"))
            .andExpect(jsonPath("$.data[0].locationStatus").value("PRECISE"))
            .andExpect(jsonPath("$.data[0].flightStatus").value("MISSION_RESUMED"))
            .andExpect(jsonPath("$.data[0].geoMethod").value("LASER_RANGEFINDER"));
    }
}
