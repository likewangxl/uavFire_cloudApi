package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class OperatorConfirmationRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R10"; }
    public String description() { return "操作员已记录起飞确认"; }
    public RuleCheckResult check(PreflightContext context) {
        if (context.operatorId() == null || context.operatorId().isBlank()) {
            return block("operatorId is required");
        }
        for (OperationTakeoffConfirmationEntity record : context.getTakeoffConfirmations()) {
            if (context.operatorId().equals(record.getOperatorId())) {
                return pass();
            }
        }
        return block("operator takeoff confirmation record missing");
    }
}
