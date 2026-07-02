package com.yx.uavfire.fc100.operation.preflight;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class PreflightResult {
    private Long id;
    private Long incidentId;
    private String operatorId;
    private PreflightStatus status;
    private List<RuleCheckResult> items = new ArrayList<>();
    private Long createTime;

    public static PreflightResult from(Long incidentId, String operatorId, List<RuleCheckResult> items, long now) {
        PreflightResult result = new PreflightResult();
        result.incidentId = incidentId;
        result.operatorId = operatorId;
        result.items = items == null ? List.of() : new ArrayList<>(items);
        result.status = result.items.stream().anyMatch(i -> i.getStatus() == RuleStatus.BLOCK)
            ? PreflightStatus.BLOCK : PreflightStatus.PASS;
        result.createTime = now;
        return result;
    }

    public List<RuleCheckResult> blockingItems() {
        return items.stream()
            .filter(i -> i.getStatus() == RuleStatus.BLOCK)
            .collect(Collectors.toList());
    }
}
