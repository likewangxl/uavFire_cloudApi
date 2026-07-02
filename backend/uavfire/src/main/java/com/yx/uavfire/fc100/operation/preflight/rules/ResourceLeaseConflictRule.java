package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class ResourceLeaseConflictRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R11"; }
    public String description() { return "资源锁无冲突"; }
    public RuleCheckResult check(PreflightContext context) {
        Long incidentId = context.incident() == null ? null : context.incident().getId();
        for (OperationResourceLeaseEntity lease : context.getActiveLeases()) {
            boolean active = "ACTIVE".equals(lease.getStatus())
                && (lease.getExpiresAt() == null || lease.getExpiresAt() > context.now());
            boolean sameOwner = "INCIDENT".equals(lease.getOwnerType()) && incidentId != null
                && incidentId.equals(lease.getOwnerId());
            if (active && !sameOwner) {
                return block("resource lease conflict: " + lease.getResourceSn());
            }
        }
        return pass();
    }
}
