package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryWaylineImportResult {
    private String waylineId;
    private String name;
    private String key;
}
