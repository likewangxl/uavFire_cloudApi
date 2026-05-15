package com.yx.uavfire.fc100.deliverysync.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fc100_delivery_sync_log")
public class DeliverySyncLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long missionId;
    private String apiName;
    private String requestMethod;
    private String requestUrl;
    private String requestBody;
    private Integer responseCode;
    private String responseBody;
    private Integer success;
    private String errorMessage;
    private String akFingerprint;
    private String idempotencyKey;
    private Integer latencyMs;
    private Long createTime;
}
