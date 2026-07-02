package com.yx.uavfire.fc100.operation.compliance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_compliance_record")
public class OperationComplianceRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private String recordType;
    private String status;
    private String operatorId;
    private String blockingItemsJson;
    private String detailsJson;
    private Long createTime;
}
