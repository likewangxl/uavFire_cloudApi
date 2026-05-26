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
    private Boolean accepted;
    private Integer apiCode;
    private String apiMessage;
    private String displayMessage;
    private String deviceSn;
    private String missionId;
    private String taskName;
    private String reason;
    private Integer taskCode;
    private Long startTime;
    private Long endTime;
    private Integer estimateTime;
}
