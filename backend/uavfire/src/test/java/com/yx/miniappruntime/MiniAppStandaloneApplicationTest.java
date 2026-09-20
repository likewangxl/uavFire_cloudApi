package com.yx.miniappruntime;

import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import com.yx.uavfire.miniapp.controller.MiniAppBusinessController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MiniAppStandaloneApplication.class, properties = {
        "spring.config.name=miniapp-standalone-test", "miniapp.enabled=true",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false",
        "management.endpoints.enabled-by-default=false",
        "spring.datasource.url=jdbc:h2:mem:standalone;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.datasource.druid.initial-size=0"
})
@AutoConfigureMockMvc
class MiniAppStandaloneApplicationTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;

    @Test void standaloneRuntimeExposesOnlyMobileReadsAndHasNoFlightConsumers() {
        assertNotNull(context.getBean(MiniAppBusinessController.class));
        assertTrue(context.getBeansOfType(OperationIncidentService.class).isEmpty());
        assertTrue(context.getBeansOfType(MqttPahoMessageDrivenChannelAdapter.class).isEmpty());
        mappings.getHandlerMethods().keySet().forEach(mapping -> mapping.getPatternValues().forEach(path ->
                assertTrue(path.startsWith("/miniapp/api/v1/") || path.equals("/error"), path)));
    }

    @Test void realMvcGuardsProtectedRoutesAndDoesNotExposeLegacyLogin() throws Exception {
        mvc.perform(get("/miniapp/api/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post("/miniapp/api/v1/auth/refresh").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/manage/api/v1/demo-login")).andExpect(status().isNotFound());
    }
}
