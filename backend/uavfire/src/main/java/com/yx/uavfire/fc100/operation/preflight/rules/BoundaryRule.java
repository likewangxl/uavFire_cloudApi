package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;
import com.yx.uavfire.fc100.operation.preflight.boundary.BoundaryCheckResult;

public class BoundaryRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R09"; }
    public String description() { return "起降点、投放点和航线不越界"; }
    public RuleCheckResult check(PreflightContext context) {
        BoundaryCheckResult result = context.boundaryCheck();
        if (result == null || !result.isReferenceLayerPresent()) {
            return warn("UOM reference layer missing; boundary check skipped");
        }
        return result.isInside() ? pass() : block(result.getMessage());
    }
}
