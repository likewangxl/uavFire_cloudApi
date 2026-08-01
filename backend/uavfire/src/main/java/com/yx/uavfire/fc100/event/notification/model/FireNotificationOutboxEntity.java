package com.yx.uavfire.fc100.event.notification.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fire_notification_outbox")
public class FireNotificationOutboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Integer notificationVersion;
    private String notificationId;
    private String workspaceId;
    private String notificationType;
    private String payloadSha256;
    private String payload;
    private String status;
    private Integer attempts;
    private Long nextAttemptTime;
    private String leaseToken;
    private Long leaseExpiresAt;
    private String lastError;
    private Long sentTime;
    private Long createTime;
    private Long updateTime;
}
