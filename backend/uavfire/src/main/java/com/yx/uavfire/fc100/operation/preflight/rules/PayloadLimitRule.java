package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class PayloadLimitRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R07"; }
    public String description() { return "载荷不超过 FC100 双电 85kg 含吊具口径"; }
    public RuleCheckResult check(PreflightContext context) {
        if (context.mission() == null) {
            return block("FC100 mission draft missing");
        }
        Double total = context.mission().getEstimatedTotalWeightKg();
        if (total != null && total > context.properties().getMaxTotalWeightKg()) {
            return block("estimated total weight " + total + "kg exceeds "
                + context.properties().getMaxTotalWeightKg() + "kg");
        }
        Double waterLoad = context.mission().getWaterLoadLiters();
        if (waterLoad != null && waterLoad > context.properties().getMaxNetPayloadKg()) {
            return block("net payload " + waterLoad + "kg exceeds "
                + context.properties().getMaxNetPayloadKg() + "kg");
        }
        return pass();
    }
}
