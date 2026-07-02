package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

abstract class AbstractPreflightRule {
    protected RuleCheckResult pass() {
        return RuleCheckResult.pass(id(), description());
    }

    protected RuleCheckResult block(String message) {
        return RuleCheckResult.block(id(), description(), message);
    }

    protected RuleCheckResult warn(String message) {
        return RuleCheckResult.warn(id(), description(), message);
    }

    public abstract String id();
    public abstract String description();
}
