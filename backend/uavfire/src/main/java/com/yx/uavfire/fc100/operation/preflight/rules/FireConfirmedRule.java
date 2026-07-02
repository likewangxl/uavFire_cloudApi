package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class FireConfirmedRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R01"; }
    public String description() { return "火情已人工确认"; }
    public RuleCheckResult check(PreflightContext context) {
        return context.fireEvent() != null && "CONFIRMED".equalsIgnoreCase(context.fireEvent().getConfirmedStatus())
            ? pass() : block("fire_event.confirmed_status is not CONFIRMED");
    }
}
