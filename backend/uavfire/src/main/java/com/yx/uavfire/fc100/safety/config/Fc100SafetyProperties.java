package com.yx.uavfire.fc100.safety.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "fc100.safety")
public class Fc100SafetyProperties {
    private boolean fourEyesEnabled = false;
    private double windSpeedErrorThreshold = 10.0;
    private double windSpeedWarnThreshold = 6.0;
    private double payloadWeightMaxKg = 82.0;
    /** 干重（吊桶+水管+连接件）估算，PAYLOAD_WEIGHT 校验时加到水量上 */
    private double dryWeightKg = 20.0;
}
