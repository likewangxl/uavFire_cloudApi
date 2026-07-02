package com.yx.uavfire.fc100.operation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_assignment")
public class OperationAssignmentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private String resourceSn;
    private String role;
    private String status;
    private Long leaseId;
    private Long assignedAt;
    private Long releasedAt;
}
