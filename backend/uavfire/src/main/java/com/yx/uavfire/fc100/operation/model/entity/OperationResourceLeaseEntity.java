package com.yx.uavfire.fc100.operation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_resource_lease")
public class OperationResourceLeaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceSn;
    private String leaseType;
    private String ownerType;
    private Long ownerId;
    private Long expiresAt;
    private Long heartbeatAt;
    private String status;
}
