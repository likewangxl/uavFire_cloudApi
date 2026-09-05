package com.yx.uavfire.miniapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.miniapp.aircraft.AircraftCapabilityProfileResolver;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.controller.MiniAppDashboardController;
import com.yx.uavfire.miniapp.controller.MiniAppMeController;
import com.yx.uavfire.miniapp.controller.MiniAppWechatAuthController;
import com.yx.uavfire.miniapp.web.MiniAppExceptionHandler;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MiniAppControllerTest {

    private MiniAppProperties properties;
    private MsdkDeviceStateService deviceStateService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new MiniAppProperties();
        properties.setEnabled(true);
        properties.getFlightControl().setEnabled(false);
        properties.getDashboard().setLiveDeviceSummaryEnabled(true);
        deviceStateService = new MsdkDeviceStateService(() -> 1_000_000L);
        AircraftCapabilityProfileResolver capabilityProfileResolver =
                new AircraftCapabilityProfileResolver(properties);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MiniAppMeController(properties),
                        new MiniAppDashboardController(properties, deviceStateService, capabilityProfileResolver),
                        new MiniAppWechatAuthController(properties))
                .setControllerAdvice(new MiniAppExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    void meReturnsLegacyIdentityWithoutGrantingFlightControl() throws Exception {
        CustomClaim claim = new CustomClaim("user-1", "leader.zhang", 1, "workspace-1");

        mockMvc.perform(get("/miniapp/api/v1/me")
                        .requestAttr(AuthInterceptor.TOKEN_CLAIM, claim)
                        .header("X-Request-Id", "req-me-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-me-1"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.user.userId").value("user-1"))
                .andExpect(jsonPath("$.data.user.displayName").value("leader.zhang"))
                .andExpect(jsonPath("$.data.user.roles[0]").value("WEB"))
                .andExpect(jsonPath("$.data.workspace.workspaceId").value("workspace-1"))
                .andExpect(jsonPath("$.data.capabilities.flightControlEnabled").value(false));
    }

    @Test
    void dashboardUsesFreshMsdkStateAndMarksUnwiredAggregatesPartial() throws Exception {
        deviceStateService.upsert(new MsdkDeviceStateDTO()
                .setAircraftSn("AIR-M300-1")
                .setOnline(true)
                .setConnectionState("CONNECTED")
                .setModel("M300 RTK")
                .setAircraftModelKey("M300")
                .setBatteryPercent(82)
                .setUpdatedAt(1_000_000L));

        mockMvc.perform(get("/miniapp/api/v1/dashboard/summary")
                        .requestAttr(AuthInterceptor.TOKEN_CLAIM,
                                new CustomClaim("user-1", "leader.zhang", 1, "workspace-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.onlineAircraft").value(1))
                .andExpect(jsonPath("$.data.metrics.recognizedAircraft").value(1))
                .andExpect(jsonPath("$.data.metrics.unknownAircraft").value(0))
                .andExpect(jsonPath("$.data.metrics.controlEligibleAircraft").value(0))
                .andExpect(jsonPath("$.data.metrics.modelFamilies.MATRICE_300").value(1))
                .andExpect(jsonPath("$.data.dataStatus").value("FRESH"))
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.activeTasks").isArray())
                .andExpect(jsonPath("$.data.warnings[0]").isNotEmpty());
    }

    @Test
    void wechatLoginRejectsMissingFieldsWithStableErrorCode() throws Exception {
        mockMvc.perform(post("/miniapp/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void wechatLoginFailsClosedUntilServerSideAdapterIsConfigured() throws Exception {
        mockMvc.perform(post("/miniapp/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\",\"deviceId\":\"device-123456\",\"clientVersion\":\"0.1.0\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WECHAT_API_UNAVAILABLE"));
    }
}
