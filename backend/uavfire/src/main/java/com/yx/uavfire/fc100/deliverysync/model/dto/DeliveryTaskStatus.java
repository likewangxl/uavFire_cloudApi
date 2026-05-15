package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.Data;

@Data
public class DeliveryTaskStatus {
    private String taskId;
    private String status;
    private String phase;
    private Integer progressPercent;
    private String message;
    private Long updateTime;
}
