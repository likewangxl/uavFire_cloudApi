package com.dji.sample.wayline;

import com.dji.sample.common.model.CustomClaim;
import com.dji.sample.component.GlobalExceptionHandler;
import com.dji.sample.wayline.controller.PlannedWaylineController;
import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sample.wayline.service.IPlannedWaylineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlannedWaylineControllerTest {

    private MockMvc mockMvc;

    private IPlannedWaylineService plannedWaylineService;

    @BeforeEach
    void setUp() {
        plannedWaylineService = mock(IPlannedWaylineService.class);
        PlannedWaylineController controller = new PlannedWaylineController(plannedWaylineService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addPlaceholderValue("url.wayline.prefix", "/wayline/api")
                .addPlaceholderValue("url.wayline.version", "/v1")
                .build();
    }

    @Test
    void createPlannedWaylineShouldPersistDraftRecord() throws Exception {
        when(plannedWaylineService.create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class)))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .workspaceId("workspace-001")
                        .name("Survey A")
                        .status("draft")
                        .waypoints(List.of(new PlannedWaypointDTO()
                                .setOrder(1)
                                .setGcjLng(120.1)
                                .setGcjLat(30.2)
                                .setWgsLng(120.0)
                                .setWgsLat(30.1)
                                .setHeight(80.0)))
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLng\":120.1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1,"
                                + "\"height\":80.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.plannedWaylineId").value("pw-001"))
                .andExpect(jsonPath("$.data.status").value("draft"));

        verify(plannedWaylineService).create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class));
    }

    @Test
    void createInsertFailureShouldReturnError() throws Exception {
        when(plannedWaylineService.create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class)))
                .thenThrow(new IllegalArgumentException("Failed to create planned wayline."));

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLng\":120.1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1,"
                                + "\"height\":80.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Failed to create planned wayline."));
    }

    @Test
    void workspaceMismatchShouldBeRejected() throws Exception {
        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-002"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLng\":120.1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1,"
                                + "\"height\":80.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Workspace mismatch."));

        verifyNoInteractions(plannedWaylineService);
    }

    @Test
    void invalidBlankNameShouldBeRejected() throws Exception {
        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLng\":120.1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1,"
                                + "\"height\":80.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));

        verifyNoInteractions(plannedWaylineService);
    }

    @Test
    void invalidEmptyWaypointsShouldBeRejected() throws Exception {
        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("waypoints")));

        verifyNoInteractions(plannedWaylineService);
    }

    @Test
    void malformedWaypointMissingOrderShouldBeRejected() throws Exception {
        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"gcjLng\":120.1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1,"
                                + "\"height\":80.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("order")));

        verifyNoInteractions(plannedWaylineService);
    }

    @Test
    void malformedWaypointMissingCoordinateShouldBeRejected() throws Exception {
        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1,"
                                + "\"height\":80.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("gcjLng")));

        verifyNoInteractions(plannedWaylineService);
    }

    @Test
    void malformedWaypointMissingHeightShouldBeRejected() throws Exception {
        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"GW-001\","
                                + "\"aircraftSn\":\"AC-001\","
                                + "\"defaultHeight\":80.0,"
                                + "\"maxSpeed\":12.5,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLng\":120.1,"
                                + "\"gcjLat\":30.2,"
                                + "\"wgsLng\":120.0,"
                                + "\"wgsLat\":30.1"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("height")));

        verifyNoInteractions(plannedWaylineService);
    }

    @Test
    void updateMissingPlannedWaylineShouldReturnError() throws Exception {
        when(plannedWaylineService.update(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("pw-001"),
                any(UpdatePlannedWaylineParam.class)))
                .thenThrow(new IllegalArgumentException("Planned wayline doesn't exist."));

        mockMvc.perform(put("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey B\","
                                + "\"aircraftModelKey\":\"M300RTK\","
                                + "\"gatewaySn\":\"GW-002\","
                                + "\"aircraftSn\":\"AC-002\","
                                + "\"defaultHeight\":100.0,"
                                + "\"maxSpeed\":18.0,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"gcjLng\":121.1,"
                                + "\"gcjLat\":31.2,"
                                + "\"wgsLng\":121.0,"
                                + "\"wgsLat\":31.1,"
                                + "\"height\":100.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Planned wayline doesn't exist."));

        verify(plannedWaylineService).update(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("pw-001"),
                any(UpdatePlannedWaylineParam.class));
    }

    @Test
    void deleteMissingPlannedWaylineShouldReturnError() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Planned wayline doesn't exist."))
                .when(plannedWaylineService).delete("workspace-001", "pw-001");

        mockMvc.perform(delete("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Planned wayline doesn't exist."));

        verify(plannedWaylineService).delete("workspace-001", "pw-001");
    }

    @Test
    void listPlannedWaylinesShouldReturnWorkspaceScopedRecords() throws Exception {
        when(plannedWaylineService.getByWorkspace("workspace-001", 2, 20))
                .thenReturn(new com.dji.sdk.common.PaginationData<>(
                        List.of(PlannedWaylineDTO.builder()
                                .plannedWaylineId("pw-001")
                                .workspaceId("workspace-001")
                                .name("Survey A")
                                .status("draft")
                                .build()),
                        new com.dji.sdk.common.Pagination(2, 20, 1)));

        mockMvc.perform(get("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .param("page", "2")
                        .param("page_size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].plannedWaylineId").value("pw-001"))
                .andExpect(jsonPath("$.data.pagination.page").value(2))
                .andExpect(jsonPath("$.data.pagination.page_size").value(20));

        verify(plannedWaylineService).getByWorkspace("workspace-001", 2, 20);
    }
}
