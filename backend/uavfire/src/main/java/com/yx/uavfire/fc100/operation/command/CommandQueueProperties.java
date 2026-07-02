package com.yx.uavfire.fc100.operation.command;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "fc100.command-queue")
public class CommandQueueProperties {
    private int maxRetries = 3;
    private Duration sendTimeout = Duration.ofSeconds(10);
    private Duration ackTimeout = Duration.ofSeconds(30);
    private Duration retryBackoff = Duration.ofSeconds(5);
    private int dispatchBatchSize = 20;
}
