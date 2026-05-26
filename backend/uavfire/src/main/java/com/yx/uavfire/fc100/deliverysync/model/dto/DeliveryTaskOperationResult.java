package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryTaskOperationResult {
    private String operation;
    private Boolean accepted;
    private Integer apiCode;
    private String apiMessage;
    private String displayMessage;
    private String taskId;
    private String status;
    private String deviceSn;
    private String missionId;
    private String taskName;
    private String reason;
    private Long updateTime;
}
