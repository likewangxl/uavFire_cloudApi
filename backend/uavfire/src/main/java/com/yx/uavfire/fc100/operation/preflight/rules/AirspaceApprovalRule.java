package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class AirspaceApprovalRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R02"; }
    public String description() { return "空域申请批复记录有效"; }
    public RuleCheckResult check(PreflightContext context) {
        long now = context.now();
        for (OperationFlightApplicationRecordEntity record : context.getFlightApplications()) {
            if (notBlank(record.getApplicationNo()) && notBlank(record.getApprovalNo())
                && validWindow(record.getValidFrom(), record.getValidTo(), now)) {
                return pass();
            }
        }
        return block("no valid flight-application approval record");
    }

    private boolean validWindow(Long from, Long to, long now) {
        return (from == null || from <= now) && (to == null || to >= now);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
