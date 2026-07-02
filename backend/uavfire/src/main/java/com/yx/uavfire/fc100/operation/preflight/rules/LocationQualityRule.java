package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class LocationQualityRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R08"; }
    public String description() { return "火点定位质量为 PRECISE"; }
    public RuleCheckResult check(PreflightContext context) {
        return context.fireEvent() != null && "PRECISE".equalsIgnoreCase(context.fireEvent().getGeoQuality())
            ? pass() : block("fire_event.location_quality is not PRECISE");
    }
}
