package com.yx.uavfire.fc100.operation.preflight;

import lombok.Data;

@Data
public class RuleCheckResult {
    private String ruleId;
    private String description;
    private RuleStatus status;
    private String message;

    public static RuleCheckResult pass(String ruleId, String description) {
        return of(ruleId, description, RuleStatus.PASS, null);
    }

    public static RuleCheckResult block(String ruleId, String description, String message) {
        return of(ruleId, description, RuleStatus.BLOCK, message);
    }

    public static RuleCheckResult warn(String ruleId, String description, String message) {
        return of(ruleId, description, RuleStatus.WARN, message);
    }

    private static RuleCheckResult of(String ruleId, String description, RuleStatus status, String message) {
        RuleCheckResult result = new RuleCheckResult();
        result.ruleId = ruleId;
        result.description = description;
        result.status = status;
        result.message = message;
        return result;
    }
}
