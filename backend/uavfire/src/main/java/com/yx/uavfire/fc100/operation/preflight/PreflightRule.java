package com.yx.uavfire.fc100.operation.preflight;

public interface PreflightRule {
    String id();
    String description();
    RuleCheckResult check(PreflightContext context);
}
