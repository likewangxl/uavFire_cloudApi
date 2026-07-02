package com.yx.uavfire.fc100.deliverysync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "fc100.delivery-sync")
public class DeliverySyncProperties {
    /** mock | http */
    private String mode = "mock";
    private String baseUrl = "https://ta-api.dji.com";
    private String ak;
    private String sk;
    private String groupId;
    private String workspaceId = "DEFAULT";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private int manualTakeoverFailureThreshold = 3;
    private Retry retry = new Retry();

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private List<Integer> backoffMs = List.of(500, 1500, 4500);
    }
}
