package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class DeviceOnlineRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R04"; }
    public String description() { return "FC100 在线并已接入 Delivery Sync"; }
    public RuleCheckResult check(PreflightContext context) {
        return context.deviceProperties() != null && Boolean.TRUE.equals(context.deviceProperties().getOnlineStatus())
            ? pass() : block("FC100 is offline or Delivery Sync properties are unavailable");
    }
}
