package com.yx.uavfire.fc100.operation.preflight;

import com.yx.uavfire.fc100.operation.preflight.rules.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PreflightRuleEngine {
    private final List<PreflightRule> rules;
    private final PreflightProperties properties;

    // 规则实例是普通对象而非 Spring Bean：容器装配必须走单参构造器，
    // 若把 @Autowired 标到双参构造器上，Spring 会注入空规则列表导致门禁全部放行。
    @Autowired
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
