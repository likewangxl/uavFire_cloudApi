package com.yx.uavfire.fc100.operation.compliance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_takeoff_confirmation_record")
public class OperationTakeoffConfirmationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private String operatorId;
    private String confirmationNo;
    private String materialUrl;
    private Long confirmedAt;
    private Long createTime;
}
