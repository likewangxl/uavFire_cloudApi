package com.yx.uavfire.fc100.operation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_incident_log")
public class OperationIncidentLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
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
