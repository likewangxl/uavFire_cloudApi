package com.yx.uavfire.fc100.operation.model.dto;

import lombok.Data;

@Data
public class OperationIncidentDTO {
    private Long id;
    private String incidentNo;
    private Long fireEventId;
    private String level;
    private String status;
    private Double centerLat;
    private Double centerLng;
    private Double riskRadiusM;
    private String createdBy;
    private String confirmedBy;
    private Long closedAt;
    private Long createTime;
    private Long updateTime;
}
