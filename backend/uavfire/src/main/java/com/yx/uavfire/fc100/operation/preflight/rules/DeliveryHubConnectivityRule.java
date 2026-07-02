package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class DeliveryHubConnectivityRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R14"; }
    public String description() { return "DeliveryHub 连通性正常"; }
    public RuleCheckResult check(PreflightContext context) {
        return context.deliveryHubReachable() ? pass() : block("DeliveryHub health check failed");
    }
}
