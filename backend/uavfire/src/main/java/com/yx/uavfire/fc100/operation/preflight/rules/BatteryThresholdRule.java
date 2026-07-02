package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class BatteryThresholdRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R05"; }
    public String description() { return "电量满足最低阈值"; }
    public RuleCheckResult check(PreflightContext context) {
        Integer battery = context.deviceProperties() == null ? null : context.deviceProperties().getBatteryPercent();
        if (battery == null) {
            return block("battery percent missing");
        }
        return battery >= context.properties().getMinBatteryPct()
            ? pass() : block("battery " + battery + "% below " + context.properties().getMinBatteryPct() + "%");
    }
}
