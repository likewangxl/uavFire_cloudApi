package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class WindLimitRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R06"; }
    public String description() { return "风速不超过派发阈值"; }
    public RuleCheckResult check(PreflightContext context) {
        Double wind = context.mission() == null ? null : context.mission().getWindSpeedAtApproval();
        if (wind == null && context.deviceProperties() != null) {
            wind = context.deviceProperties().getWindSpeed();
        }
        if (wind == null) {
            return warn("wind speed missing");
        }
        return wind <= context.properties().getMaxWindMps()
            ? pass() : block("wind speed " + wind + "m/s exceeds " + context.properties().getMaxWindMps() + "m/s");
    }
}
