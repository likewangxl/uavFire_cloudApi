package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class AirdropApprovalRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R03"; }
    public String description() { return "投放审批记录有效"; }
    public RuleCheckResult check(PreflightContext context) {
        if (context.mission() == null || !hasAirdropPayload(context)) {
            return pass();
        }
        for (OperationQualificationEntity q : context.getQualifications()) {
            if ("AIRDROP_APPROVAL".equals(q.getQualificationType()) && "VALID".equals(q.getStatus())
                && (q.getValidFrom() == null || q.getValidFrom() <= context.now())
                && (q.getValidTo() == null || q.getValidTo() >= context.now())) {
                return pass();
            }
        }
        return block("airdrop payload requires valid AIRDROP_APPROVAL record");
    }

    private boolean hasAirdropPayload(PreflightContext context) {
        return context.mission().getPayloadType() != null || context.mission().getPayloadId() != null
            || context.mission().getWaterLoadLiters() != null;
    }
}
