package com.yx.uavfire.fc100.deliverysync.config;

import com.yx.uavfire.trial.DeploymentEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Mock delivery is the safe default. Production must opt in or switch to http. */
@Component
public class DeliverySyncModeGuard {

    public DeliverySyncModeGuard(DeliverySyncProperties properties, Environment environment) {
        boolean mock = properties.getMode() == null || "mock".equalsIgnoreCase(properties.getMode());
        if (DeploymentEnvironment.isProduction(environment) && mock && !properties.isAllowMock()) {
            throw new IllegalStateException(
                    "生产环境拒绝司运 mock。请设置 fc100.delivery-sync.mode=http，或显式 fc100.delivery-sync.allow-mock=true");
        }
    }
}