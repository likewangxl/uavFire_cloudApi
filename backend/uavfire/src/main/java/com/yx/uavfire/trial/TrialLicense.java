package com.yx.uavfire.trial;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TrialLicense {

    private final TrialExpirationPolicy policy;

    @Autowired
    public TrialLicense(TrialLicenseProperties properties, Environment environment) {
        this(TrialExpirationPolicy.from(properties, Clock.systemUTC(), DeploymentEnvironment.isProduction(environment)));
    }

    TrialLicense(TrialExpirationPolicy policy) {
        this.policy = policy;
    }

    public TrialExpirationPolicy policy() {
        return policy;
    }

    public Map<String, Object> publicStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", policy.modeName());
        body.put("expiresAt", policy.getExpiresAt() == null ? null : policy.getExpiresAt().toString());
        body.put("expired", policy.isExpired());
        body.put("display", policy.display());
        body.put("production", policy.isProduction());
        return body;
    }
}
