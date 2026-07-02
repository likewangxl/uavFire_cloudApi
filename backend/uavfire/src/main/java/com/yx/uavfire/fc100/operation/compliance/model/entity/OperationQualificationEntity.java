package com.yx.uavfire.fc100.operation.compliance.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_qualification_record")
public class OperationQualificationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String qualificationType;
    private String qualificationNo;
    private String issuer;
    private Long validFrom;
    private Long validTo;
    private String materialUrl;
    private String status;
    private String operatorId;
    private Long createTime;
    private Long updateTime;
}
