package com.yx.uavfire.trial;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Production is an explicit profile or UAVFIRE_ENV, never the implicit default. */
public final class DeploymentEnvironment {

    private DeploymentEnvironment() {
    }

    public static boolean isProduction(Environment environment) {
        if (environment != null) {
            for (String profile : environment.getActiveProfiles()) {
                if (isProductionName(profile)) {
                    return true;
                }
            }
            if (isProductionName(environment.getProperty("uavfire.env"))) {
                return true;
            }
            if (isProductionName(environment.getProperty("UAVFIRE_ENV"))) {
                return true;
            }
        }
        return isProductionName(System.getenv("UAVFIRE_ENV"));
    }

    private static boolean isProductionName(String value) {
        return StringUtils.hasText(value)
                && ("prod".equalsIgnoreCase(value) || "production".equalsIgnoreCase(value));
    }
}
