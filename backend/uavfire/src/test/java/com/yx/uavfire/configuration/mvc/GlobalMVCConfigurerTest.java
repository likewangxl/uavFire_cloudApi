package com.yx.uavfire.configuration.mvc;

import com.yx.uavfire.component.AuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.security.MiniAppAuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalMVCConfigurerTest {

    @Test
    void videoPolicyHasAgentAuthWhileViewerAndStatusKeepUserAuth() {
        InterceptorRegistry registry = new InterceptorRegistry();
        configurer.addInterceptors(registry);
        List<?> interceptors = ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
        MappedInterceptor agent = (MappedInterceptor) interceptors.stream()
                .filter(i -> i instanceof MappedInterceptor && ((MappedInterceptor) i).getInterceptor() instanceof WaylineAgentAuthInterceptor)
                .findFirst().orElseThrow();
        MappedInterceptor user = (MappedInterceptor) interceptors.stream()
                .filter(i -> i instanceof MappedInterceptor && ((MappedInterceptor) i).getInterceptor() instanceof AuthInterceptor)
                .findFirst().orElseThrow();
        var matcher = new org.springframework.util.AntPathMatcher();
        String policy = "/manage/api/v1/dual-stream/agents/A/video-policy";
        assertTrue(agent.matches(policy, matcher));
        assertFalse(user.matches(policy, matcher));
        assertTrue(user.matches("/manage/api/v1/video-bandwidth/viewers/page-0001", matcher));
        assertTrue(user.matches("/manage/api/v1/video-bandwidth/status", matcher));
    }

    private GlobalMVCConfigurer configurer;

    @BeforeEach
    void setUp() {
        configurer = new GlobalMVCConfigurer();
        ReflectionTestUtils.setField(configurer, "authInterceptor", new AuthInterceptor());
        ReflectionTestUtils.setField(configurer, "waylineAgentAuthInterceptor", new WaylineAgentAuthInterceptor());
        ReflectionTestUtils.setField(configurer, "miniAppAuthInterceptor",
                new MiniAppAuthInterceptor(new MiniAppProperties(), new ObjectMapper()));
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
        assertTrue(excludePaths.contains("/manage/api/v1/dual-stream/tasks/*/agent-fire-events"));
        assertTrue(excludePaths.contains("/manage/api/v1/dual-stream/fire-evidence/**"));
        assertTrue(excludePaths.contains("/manage/api/v1/dual-stream/tasks/*/latest-visible-roi"));
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

    @Test
    void addInterceptors_delegatesMiniAppPathsToDedicatedBearerAuth() {
        configurer.addInterceptors(new InterceptorRegistry());

        @SuppressWarnings("unchecked")
        List<String> excludePaths = (List<String>) ReflectionTestUtils.getField(GlobalMVCConfigurer.class, "EXCLUDE_PATHS");

        assertTrue(excludePaths.contains("/miniapp/api/v1/**"));
    }

    @Test
    void requestLoggingFilter_skipsMiniAppAuthenticationPayloads() {
        CommonsRequestLoggingFilter filter = configurer.requestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/miniapp/api/v1/auth/wechat/login");

        Boolean skipped = ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter", request);

        assertTrue(Boolean.TRUE.equals(skipped));
    }
}
