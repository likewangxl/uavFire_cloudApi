package com.yx.miniappruntime;

import com.yx.uavfire.miniapp.security.MiniAppAuthInterceptor;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Separate read-only mobile runtime: no flight controllers, schedulers, MQTT or device command consumers. */
@SpringBootApplication(scanBasePackages = "com.yx.uavfire.miniapp")
public class MiniAppStandaloneApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniAppStandaloneApplication.class, args);
    }

    @Bean
    public MsdkDeviceStateService miniAppDeviceReadState() {
        // This isolated process has no telemetry ingestion. Missing telemetry remains UNKNOWN.
        return new MsdkDeviceStateService();
    }

    @Bean
    public WebMvcConfigurer miniAppRoutes(MiniAppAuthInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/miniapp/api/v1/**")
                        .excludePathPatterns("/miniapp/api/v1/auth/wechat/login",
                                "/miniapp/api/v1/auth/refresh", "/miniapp/api/v1/auth/bindings");
            }
        };
    }
}
