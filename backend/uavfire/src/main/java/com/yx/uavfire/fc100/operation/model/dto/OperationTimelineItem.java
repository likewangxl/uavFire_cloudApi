package com.yx.uavfire.fc100.operation.model.dto;

import lombok.Data;

@Data
public class OperationTimelineItem {
    private String type;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String resourceSn;
    private String role;
    private String status;
    private String operatorId;
    private String description;
    private Long createTime;
}
