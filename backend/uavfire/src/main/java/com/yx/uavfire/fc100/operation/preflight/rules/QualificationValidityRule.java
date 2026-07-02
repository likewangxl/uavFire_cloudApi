package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

import java.util.LinkedHashSet;
import java.util.Set;

public class QualificationValidityRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R15"; }
    public String description() { return "运行资质档案有效"; }
    public RuleCheckResult check(PreflightContext context) {
        Set<String> required = new LinkedHashSet<>();
        required.add("CLUSTER_FLIGHT_PERMIT");
        if (context.mission() != null && (context.mission().getPayloadType() != null
            || context.mission().getPayloadId() != null || context.mission().getWaterLoadLiters() != null)) {
            required.add("AIRDROP_APPROVAL");
        }
        required.add("AIRWORTHINESS");
        required.add("JOINT_OPERATION_AGREEMENT");

        for (String type : required) {
            if (!hasValid(context, type)) {
                return block("qualification missing or expired: " + type);
            }
        }
        return pass();
    }

    private boolean hasValid(PreflightContext context, String type) {
        long now = context.now();
        for (OperationQualificationEntity q : context.getQualifications()) {
            if (type.equals(q.getQualificationType()) && "VALID".equals(q.getStatus())
                && (q.getValidFrom() == null || q.getValidFrom() <= now)
                && (q.getValidTo() == null || q.getValidTo() >= now)) {
                return true;
            }
        }
        return false;
    }
}
