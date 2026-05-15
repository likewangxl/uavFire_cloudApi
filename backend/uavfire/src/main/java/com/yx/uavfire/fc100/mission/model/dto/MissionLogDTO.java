package com.yx.uavfire.fc100.mission.model.dto;

import lombok.Data;

@Data
public class MissionLogDTO {
    private Long id;
    private Long missionId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String operatorId;
    private String operatorRole;
    private String clientIp;
    private String requestId;
    private String idempotencyKey;
    private String remark;
    private Long createTime;
}
