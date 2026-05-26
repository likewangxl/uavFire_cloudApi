package com.yx.uavfire.configuration.mvc;

import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalMVCConfigurerTest {

    private GlobalMVCConfigurer configurer;

    @BeforeEach
    void setUp() {
        configurer = new GlobalMVCConfigurer();
        ReflectionTestUtils.setField(configurer, "authInterceptor", new AuthInterceptor());
        ReflectionTestUtils.setField(configurer, "waylineAgentAuthInterceptor", new WaylineAgentAuthInterceptor());
        ReflectionTestUtils.setField(configurer, "managePrefix", "manage/api");
        ReflectionTestUtils.setField(configurer, "manageVersion", "/v1");
        ReflectionTestUtils.setField(configurer, "waylineAgentPrefix", "wayline-agent");
        ReflectionTestUtils.setField(configurer, "waylineAgentVersion", "/api/v1");
        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");
        excludePaths.clear();
    }

    @Test
    void addInterceptors_excludesDualStreamAgentAndAiEventIngestEndpointsOnly() {
        configurer.addInterceptors(new InterceptorRegistry());

        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");

        assertTrue(excludePaths.contains("/manage/api/v1/dual-stream/agents/**"));
        assertTrue(excludePaths.contains("/manage/api/v1/dual-stream/tasks/*/events"));
        assertFalse(excludePaths.contains("/manage/api/v1/dual-stream/**"));
    }

    @Test
    void addInterceptors_excludesOnlyMsdkAgentStatePollAndAckPaths() {
        configurer.addInterceptors(new InterceptorRegistry());

        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");

        assertTrue(excludePaths.contains("/manage/api/v1/msdk/devices/state"));
        assertTrue(excludePaths.contains("/manage/api/v1/msdk/devices/*/commands/poll"));
        assertTrue(excludePaths.contains("/manage/api/v1/msdk/devices/*/commands/ack"));
        assertFalse(excludePaths.contains("/manage/api/v1/msdk/devices/**"));
    }

    @Test
    void addInterceptors_excludesWaylineAgentPathsFromGlobalAuth() {
        configurer.addInterceptors(new InterceptorRegistry());

        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");

        assertTrue(excludePaths.contains("/wayline-agent/api/v1/**"));
    }
}
