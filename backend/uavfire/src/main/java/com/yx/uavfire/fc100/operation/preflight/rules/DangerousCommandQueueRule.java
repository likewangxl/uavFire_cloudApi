package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

import java.util.Set;

public class DangerousCommandQueueRule extends AbstractPreflightRule implements PreflightRule {
    private static final Set<String> DANGEROUS_TYPES = Set.of(
        "emergency_stop", "drone_landing", "return_home", "hoist_hook_control",
        "flight_authority_release", "control_authority_release");
    private static final Set<String> OPEN_STATUSES = Set.of("PENDING", "SENDING", "WAIT_ACK");

    public String id() { return "R12"; }
    public String description() { return "指令队列无未完成危险指令"; }
    public RuleCheckResult check(PreflightContext context) {
        for (OperationCommandEventEntity command : context.getCommandEvents()) {
            if (DANGEROUS_TYPES.contains(command.getCommandType())
                && OPEN_STATUSES.contains(command.getStatus())) {
                return block("dangerous command still open: " + command.getCommandType());
            }
        }
        return pass();
    }
}
