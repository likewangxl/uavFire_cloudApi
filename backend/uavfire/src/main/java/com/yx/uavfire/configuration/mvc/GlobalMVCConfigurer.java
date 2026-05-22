package com.yx.uavfire.configuration.mvc;

import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class GlobalMVCConfigurer implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private WaylineAgentAuthInterceptor waylineAgentAuthInterceptor;

    private static final List<String> EXCLUDE_PATHS = new ArrayList<>();

    @Value("${url.manage.prefix}")
    private String managePrefix;

    @Value("${url.manage.version}")
    private String manageVersion;

    @Value("${url.wayline-agent.prefix}")
    private String waylineAgentPrefix;

    @Value("${url.wayline-agent.version}")
    private String waylineAgentVersion;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String waylineAgentBase = "/" + waylineAgentPrefix + waylineAgentVersion;

        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/login");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/token/refresh");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/captcha");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/demo-login");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/dual-stream/agents/**");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/msdk/devices/state");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/msdk/devices/*/commands/poll");
        EXCLUDE_PATHS.add("/" + managePrefix + manageVersion + "/msdk/devices/*/commands/ack");
        EXCLUDE_PATHS.add(waylineAgentBase + "/**");
        EXCLUDE_PATHS.add("/");
        EXCLUDE_PATHS.add("/index.html");
        EXCLUDE_PATHS.add("/favicon.ico");
        EXCLUDE_PATHS.add("/error");
        EXCLUDE_PATHS.add("/swagger-ui.html");
        EXCLUDE_PATHS.add("/swagger-ui/**");
        EXCLUDE_PATHS.add("/v3/**");
        EXCLUDE_PATHS.add("/ui/**");
        registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns(EXCLUDE_PATHS);

        registry.addInterceptor(waylineAgentAuthInterceptor)
                .addPathPatterns(waylineAgentBase + "/agents/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // dev/prod 跨域:M4T frontend (8080) 调 M4T backend (6789);fc100 模块合并后共用同一 CORS
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            .allowedHeaders("*")
            .exposedHeaders("x-auth-token", "X-Request-Id", "X-Idempotency-Key")
            .allowCredentials(true)
            .maxAge(3600);
    }

    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
        filter.setIncludeClientInfo(true);
        filter.setIncludeQueryString(true);
        filter.setIncludePayload(true);
        filter.setIncludeHeaders(false);
        filter.setMaxPayloadLength(4096);
        filter.setBeforeMessagePrefix("HTTP request started: ");
        filter.setAfterMessagePrefix("HTTP request finished: ");
        return filter;
    }
}
