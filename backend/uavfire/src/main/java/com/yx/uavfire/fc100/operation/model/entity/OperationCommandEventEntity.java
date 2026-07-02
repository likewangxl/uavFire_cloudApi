package com.yx.uavfire.fc100.operation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_command_event")
public class OperationCommandEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String commandId;
    private String targetSn;
    private String commandType;
    private String missionNo;
    private String payloadJson;
    private String status;
    private String idempotencyKey;
    private String operatorId;
    private Integer retryCount;
    private Long sentAt;
    private Long ackAt;
    private Long nextAttemptAt;
    private String errorMessage;
    private Long createTime;
    private Long updateTime;
}
