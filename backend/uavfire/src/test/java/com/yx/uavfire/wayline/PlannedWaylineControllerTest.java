package com.yx.uavfire.wayline;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.GlobalExceptionHandler;
import com.yx.uavfire.wayline.controller.PlannedWaylineController;
import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaypointDTO;
import com.yx.uavfire.wayline.model.param.CreatePlannedWaylineParam;
import com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam;
import com.yx.uavfire.wayline.model.param.PublishPlannedWaylineResponse;
import com.yx.uavfire.wayline.model.param.UpdatePlannedWaylineParam;
import com.yx.uavfire.wayline.service.IPlannedWaylineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void createParamShouldBindFrontendCamelCasePayloadWithSnakeCaseMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

        CreatePlannedWaylineParam param = mapper.readValue("{"
                        + "\"name\":\"Survey A\","
                        + "\"aircraftModelKey\":\"M30T\","
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
                        + "}",
                CreatePlannedWaylineParam.class);

        assertEquals("M30T", param.getAircraftModelKey());
        assertEquals(80.0, param.getDefaultHeight());
        assertEquals(12.5, param.getMaxSpeed());
        assertEquals(120.1, param.getWaypoints().get(0).getGcjLng());
        assertEquals(30.2, param.getWaypoints().get(0).getGcjLat());
        assertEquals(120.0, param.getWaypoints().get(0).getWgsLng());
        assertEquals(30.1, param.getWaypoints().get(0).getWgsLat());
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
    void createPlannedWaylineShouldAllowBlankDeviceTargetForPlanningOnly() throws Exception {
        when(plannedWaylineService.create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class)))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .workspaceId("workspace-001")
                        .name("Survey A")
                        .aircraftModelKey("M30T")
                        .gatewaySn("")
                        .aircraftSn("")
                        .status("draft")
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Survey A\","
                                + "\"aircraftModelKey\":\"M30T\","
                                + "\"gatewaySn\":\"\","
                                + "\"aircraftSn\":\"\","
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
                .andExpect(jsonPath("$.data.gatewaySn").value(""))
                .andExpect(jsonPath("$.data.aircraftSn").value(""));

        verify(plannedWaylineService).create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class));
    }

    @Test
    void createPlannedWaylineShouldAcceptLegacyWaypointCoordinateAliasesAndDefaults() throws Exception {
        when(plannedWaylineService.create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class)))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-legacy")
                        .workspaceId("workspace-001")
                        .name("Legacy Alias")
                        .aircraftModelKey("M30T")
                        .defaultHeight(30.0)
                        .maxSpeed(5.0)
                        .status("draft")
                        .waypoints(List.of(new PlannedWaypointDTO()
                                .setOrder(1)
                                .setGcjLng(120.1)
                                .setGcjLat(30.2)
                                .setWgsLng(120.1)
                                .setWgsLat(30.2)
                                .setHeight(30.0)))
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Legacy Alias\","
                                + "\"aircraftModelKey\":null,"
                                + "\"defaultHeight\":null,"
                                + "\"maxSpeed\":null,"
                                + "\"waypoints\":[{"
                                + "\"order\":1,"
                                + "\"lng\":120.1,"
                                + "\"lat\":30.2,"
                                + "\"height\":30.0"
                                + "}]"
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.plannedWaylineId").value("pw-legacy"));

        verify(plannedWaylineService).create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.argThat(param ->
                        "M30T".equals(param.getAircraftModelKey())
                                && Double.valueOf(30.0).equals(param.getDefaultHeight())
                                && Double.valueOf(5.0).equals(param.getMaxSpeed())
                                && Double.valueOf(120.1).equals(param.getWaypoints().get(0).getGcjLng())
                                && Double.valueOf(30.2).equals(param.getWaypoints().get(0).getWgsLat())));
    }


    @Test
    void createInsertFailureShouldReturnError() throws Exception {
        when(plannedWaylineService.create(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(CreatePlannedWaylineParam.class)))
                .thenThrow(new IllegalArgumentException("Failed to create planned wayline."));

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
    void updatePlannedWaylineShouldOverwriteEditableFields() throws Exception {
        when(plannedWaylineService.update(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("pw-001"),
                any(UpdatePlannedWaylineParam.class)))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .workspaceId("workspace-001")
                        .name("Survey B")
                        .aircraftModelKey("M300RTK")
                        .gatewaySn("GW-002")
                        .aircraftSn("AC-002")
                        .defaultHeight(100.0)
                        .maxSpeed(18.0)
                        .status("draft")
                        .waypoints(List.of(new PlannedWaypointDTO()
                                .setOrder(1)
                                .setGcjLng(121.1)
                                .setGcjLat(31.2)
                                .setWgsLng(121.0)
                                .setWgsLat(31.1)
                                .setHeight(100.0)))
                        .build());

        mockMvc.perform(put("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("Survey B"))
                .andExpect(jsonPath("$.data.aircraftModelKey").value("M300RTK"))
                .andExpect(jsonPath("$.data.gatewaySn").value("GW-002"))
                .andExpect(jsonPath("$.data.aircraftSn").value("AC-002"))
                .andExpect(jsonPath("$.data.defaultHeight").value(100.0))
                .andExpect(jsonPath("$.data.maxSpeed").value(18.0));

        verify(plannedWaylineService).update(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("pw-001"),
                any(UpdatePlannedWaylineParam.class));
    }

    @Test
    void publishPlannedWaylineShouldReturnPublishedWaylineId() throws Exception {
        when(plannedWaylineService.publish("workspace-001", "pw-001", "alice"))
                .thenReturn(PublishPlannedWaylineResponse.builder()
                        .plannedWaylineId("pw-001")
                        .publishedWaylineId("wayline-001")
                        .publishedWaylineName("Survey A")
                        .publisher("alice")
                        .publishTime(2000L)
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001/publish")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.plannedWaylineId").value("pw-001"))
                .andExpect(jsonPath("$.data.publishedWaylineId").value("wayline-001"))
                .andExpect(jsonPath("$.data.publishedWaylineName").value("Survey A"))
                .andExpect(jsonPath("$.data.publisher").value("alice"))
                .andExpect(jsonPath("$.data.publishTime").value(2000L));

        verify(plannedWaylineService).publish("workspace-001", "pw-001", "alice");
    }

    @Test
    void generatePlannedWaylineFileShouldReturnKmzMetadata() throws Exception {
        when(plannedWaylineService.generateFile("workspace-001", "pw-001", "alice"))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .status("file_generated")
                        .publishedWaylineId("wayline-001")
                        .kmzUrl("http://example.test/wayline.kmz")
                        .kmzMd5("abc123")
                        .kmzObjectKey("wayline/pw-001.kmz")
                        .fileGeneratedTime(3000L)
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001/generate-file")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("file_generated"))
                .andExpect(jsonPath("$.data.publishedWaylineId").value("wayline-001"))
                .andExpect(jsonPath("$.data.kmzUrl").value("http://example.test/wayline.kmz"))
                .andExpect(jsonPath("$.data.kmzMd5").value("abc123"))
                .andExpect(jsonPath("$.data.fileGeneratedTime").value(3000L));

        verify(plannedWaylineService).generateFile("workspace-001", "pw-001", "alice");
    }

    @Test
    void preparePlannedWaylineTaskShouldReturnPublishingStatus() throws Exception {
        when(plannedWaylineService.prepareTask(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("pw-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(PreparePlannedWaylineTaskParam.class)))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .status("publishing")
                        .taskStatus("publishing")
                        .flightId("flight-001")
                        .dockSn("DOCK-001")
                        .droneSn("DRONE-001")
                        .publishTime(4000L)
                        .publisher("alice")
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001/prepare")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dockSn\":\"DOCK-001\",\"droneSn\":\"DRONE-001\",\"executeTime\":0,\"taskType\":\"IMMEDIATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("publishing"))
                .andExpect(jsonPath("$.data.taskStatus").value("publishing"))
                .andExpect(jsonPath("$.data.flightId").value("flight-001"))
                .andExpect(jsonPath("$.data.dockSn").value("DOCK-001"));

        verify(plannedWaylineService).prepareTask(org.mockito.ArgumentMatchers.eq("workspace-001"),
                org.mockito.ArgumentMatchers.eq("pw-001"),
                org.mockito.ArgumentMatchers.eq("alice"),
                any(PreparePlannedWaylineTaskParam.class));
    }

    @Test
    void executePlannedWaylineTaskShouldReturnExecutingStatus() throws Exception {
        when(plannedWaylineService.executeTask("workspace-001", "pw-001"))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .status("executing")
                        .taskStatus("executing")
                        .executedTime(5000L)
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001/execute")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskStatus").value("executing"))
                .andExpect(jsonPath("$.data.executedTime").value(5000L));

        verify(plannedWaylineService).executeTask("workspace-001", "pw-001");
    }

    @Test
    void cancelPlannedWaylineTaskShouldReturnCanceledStatus() throws Exception {
        when(plannedWaylineService.cancelTask("workspace-001", "pw-001"))
                .thenReturn(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .status("canceled")
                        .taskStatus("canceled")
                        .build());

        mockMvc.perform(post("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001/cancel")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskStatus").value("canceled"));

        verify(plannedWaylineService).cancelTask("workspace-001", "pw-001");
    }

    @Test
    void getPlannedWaylineShouldReturnSingleWorkspaceScopedRecord() throws Exception {
        when(plannedWaylineService.getOne("workspace-001", "pw-001"))
                .thenReturn(java.util.Optional.of(PlannedWaylineDTO.builder()
                        .plannedWaylineId("pw-001")
                        .workspaceId("workspace-001")
                        .name("Survey A")
                        .status("published")
                        .publishedWaylineId("wayline-001")
                        .publisher("alice")
                        .publishTime(2000L)
                        .build()));

        mockMvc.perform(get("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.plannedWaylineId").value("pw-001"))
                .andExpect(jsonPath("$.data.publisher").value("alice"))
                .andExpect(jsonPath("$.data.publishTime").value(2000L));

        verify(plannedWaylineService).getOne("workspace-001", "pw-001");
    }

    @Test
    void getMissingPlannedWaylineShouldReturnError() throws Exception {
        when(plannedWaylineService.getOne("workspace-001", "pw-001"))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Planned wayline doesn't exist."));

        verify(plannedWaylineService).getOne("workspace-001", "pw-001");
    }

    @Test
    void deleteMissingPlannedWaylineShouldReturnError() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Planned wayline doesn't exist."))
                .when(plannedWaylineService).delete("workspace-001", "pw-001");

        mockMvc.perform(delete("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Planned wayline doesn't exist."));

        verify(plannedWaylineService).delete("workspace-001", "pw-001");
    }

    @Test
    void deletePlannedWaylineShouldRemoveWorkspaceOwnedRecord() throws Exception {
        mockMvc.perform(delete("/wayline/api/v1/workspaces/workspace-001/planned-waylines/pw-001")
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("1", "alice", 1, "workspace-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"));

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
                        .requestAttr(com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM,
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
