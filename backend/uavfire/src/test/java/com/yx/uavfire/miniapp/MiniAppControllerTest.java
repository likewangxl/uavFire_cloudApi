package com.yx.uavfire.miniapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.miniapp.controller.*;
import com.yx.uavfire.miniapp.service.*;
import com.yx.uavfire.miniapp.security.*;
import com.yx.uavfire.miniapp.web.MiniAppExceptionHandler;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MiniAppControllerTest {
    MiniAppTestDatabase db; MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        db=new MiniAppTestDatabase(); ObjectMapper mapper=new ObjectMapper();
        MiniAppReadService reads=new MiniAppReadService(db.jdbc,mapper,new MsdkDeviceStateService(System::currentTimeMillis));
        mvc=MockMvcBuilders.standaloneSetup(new MiniAppMeController(db.properties,reads),new MiniAppDashboardController(db.properties,reads),
                new MiniAppWechatAuthController(db.properties,new WechatHttpGateway(db.properties,mapper),db.sessions,new MiniAppLoginLimiter()),new MiniAppBusinessController(reads))
            .addInterceptors(new MiniAppAuthInterceptor(db.properties,mapper,db.sessions) {
                @Override public boolean preHandle(javax.servlet.http.HttpServletRequest r,javax.servlet.http.HttpServletResponse s,Object h) throws Exception {
                    if (r.getRequestURI().equals("/miniapp/api/v1/auth/wechat/login")) return true;
                    return super.preHandle(r,s,h);
                }
            }).setControllerAdvice(new MiniAppExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    String token() {
        db.sessions.login("openid-a","device-12345");db.approve("openid-a");return (String)db.sessions.login("openid-a","device-12345").get("accessToken");
    }
    @Test void meUsesApprovedAccountAndWorkspace() throws Exception {
        mvc.perform(get("/miniapp/api/v1/me").header("Authorization","Bearer "+token()).header("X-Request-Id","req-me-1"))
            .andExpect(status().isOk()).andExpect(header().string("X-Request-Id","req-me-1"))
            .andExpect(jsonPath("$.data.user.userId").value("user-a")).andExpect(jsonPath("$.data.workspace.name").value("林区 A"))
            .andExpect(jsonPath("$.data.capabilities.flightControlEnabled").value(false));
    }
    @Test void dashboardReadsRealEmptyTablesWithoutInventedCounts() throws Exception {
        mvc.perform(get("/miniapp/api/v1/dashboard/summary").header("Authorization","Bearer "+token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.metrics.todayTasks").value(0))
            .andExpect(jsonPath("$.data.metrics.urgentEvents").value(0)).andExpect(jsonPath("$.data.partial").value(true));
    }
    @Test void protectedReadsRejectAnonymousAndForeignIds() throws Exception {
        mvc.perform(get("/miniapp/api/v1/inspection-tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/miniapp/api/v1/events/foreign").header("Authorization","Bearer "+token())).andExpect(status().isNotFound());
    }
    @Test void missingWechatFieldsAndMissingServerConfigurationFailClosed() throws Exception {
        mvc.perform(post("/miniapp/api/v1/auth/wechat/login").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/miniapp/api/v1/auth/wechat/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"one-time-code\",\"deviceId\":\"device-12345\",\"clientVersion\":\"0.2.0\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WECHAT_API_UNAVAILABLE"));
    }
}
