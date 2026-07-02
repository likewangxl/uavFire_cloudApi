package com.yx.uavfire.fc100.operation.compliance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_landing_report_record")
public class OperationLandingReportEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private String reportNo;
    private String materialUrl;
    private String operatorId;
    private Long landedAt;
    private Long createTime;
}
