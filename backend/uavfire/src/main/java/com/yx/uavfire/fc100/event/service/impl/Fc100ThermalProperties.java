package com.yx.uavfire.fc100.event.service.impl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "fc100.thermal")
public class Fc100ThermalProperties {
    private double saturationTempC = 540.0;
}
