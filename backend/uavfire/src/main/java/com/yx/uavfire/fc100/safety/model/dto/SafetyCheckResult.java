package com.yx.uavfire.fc100.safety.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class SafetyCheckResult {
    private boolean passed;
    private List<SafetyCheckIssue> issues;
}
