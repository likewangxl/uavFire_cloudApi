package com.yx.uavfire.fc100.operation.preflight;

import com.yx.uavfire.fc100.operation.preflight.rules.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PreflightRuleEngine {
    private final List<PreflightRule> rules;
    private final PreflightProperties properties;

    public PreflightRuleEngine(PreflightProperties properties) {
        this(properties, List.of(
            new FireConfirmedRule(),
            new AirspaceApprovalRule(),
            new AirdropApprovalRule(),
            new DeviceOnlineRule(),
            new BatteryThresholdRule(),
            new WindLimitRule(),
            new PayloadLimitRule(),
            new LocationQualityRule(),
            new BoundaryRule(),
            new OperatorConfirmationRule(),
            new ResourceLeaseConflictRule(),
            new DangerousCommandQueueRule(),
            new TimeEnergyBudgetRule(),
            new DeliveryHubConnectivityRule(),
            new QualificationValidityRule()
        ));
    }

    public PreflightRuleEngine(PreflightProperties properties, List<PreflightRule> rules) {
        this.properties = properties;
        this.rules = rules;
    }

    public PreflightResult evaluate(PreflightContext context) {
        List<RuleCheckResult> items = new ArrayList<>();
        for (PreflightRule rule : rules) {
            if (properties.isRuleEnabled(rule.id())) {
                items.add(rule.check(context));
            }
        }
        Long incidentId = context.incident() == null ? null : context.incident().getId();
        return PreflightResult.from(incidentId, context.operatorId(), items, context.now());
    }
}
