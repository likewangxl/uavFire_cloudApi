package com.yx.uavfire.configuration.mvc;

import com.yx.uavfire.component.AuthInterceptor;
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
        ReflectionTestUtils.setField(configurer, "managePrefix", "manage/api");
        ReflectionTestUtils.setField(configurer, "manageVersion", "/v1");
        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");
        excludePaths.clear();
    }

    @Test
    void addInterceptors_excludesDualStreamAgentEndpointsOnly() {
        configurer.addInterceptors(new InterceptorRegistry());

        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");

        assertTrue(excludePaths.contains("/manage/api/v1/dual-stream/agents/**"));
        assertFalse(excludePaths.contains("/manage/api/v1/dual-stream/**"));
    }
}
