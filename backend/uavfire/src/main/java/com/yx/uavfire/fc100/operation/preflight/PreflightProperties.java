package com.yx.uavfire.fc100.operation.preflight;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "fc100.preflight")
public class PreflightProperties {
    private int minBatteryPct = 30;
    private double maxWindMps = 12.0;
    private double maxTotalWeightKg = 85.0;
    private double maxNetPayloadKg = 35.0;
    private Map<String, Boolean> rules = new HashMap<>();
    private TimeBudget timeBudget = new TimeBudget();

    public boolean isRuleEnabled(String id) {
        if (id == null) {
            return true;
        }
        Boolean exact = rules.get(id);
        if (exact != null) {
            return exact;
        }
        Boolean lower = rules.get(id.toLowerCase(Locale.ROOT));
        return lower == null || lower;
    }

    @Data
    public static class TimeBudget {
        private double cruiseSpeedMps = 8.0;
        private double maxFlightMinutes = 40.0;
        private double hoverMinutes = 12.0;
        private double hoverRatio = 1.0;
        private double safetyReserveRatio = 0.2;
    }
}
