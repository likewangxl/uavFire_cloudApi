package com.yx.uavfire.fc100.safety.model.dto;

import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SafetyCheckIssue {
    private String item;
    private SafetyCheckLevel level;
    private String message;
}
