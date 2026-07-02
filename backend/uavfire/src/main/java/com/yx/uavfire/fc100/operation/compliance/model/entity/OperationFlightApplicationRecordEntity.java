package com.yx.uavfire.fc100.operation.compliance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_flight_application_record")
public class OperationFlightApplicationRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private String applicationNo;
    private String approvalNo;
    private Long validFrom;
    private Long validTo;
    private String materialUrl;
    private String operatorId;
    private Long createTime;
    private Long updateTime;
}
