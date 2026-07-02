package com.yx.uavfire.fc100.payload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "fc100.release")
public class Fc100ReleaseProperties {
    private boolean controlledTestAutoEnabled = false;
    private Duration pendingTimeout = Duration.ofMinutes(5);
    private boolean pendingTimeoutAutoReturnEnabled = true;
}
