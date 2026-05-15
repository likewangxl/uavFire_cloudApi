package com.yx.uavfire.fc100.payload.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fc100_payload_event")
public class PayloadEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long missionId;
    private String payloadId;
    private String eventType;
    private String eventValue;
    /** JSON column: 5 项 checklist 各自勾选时间戳 */
    private String preReleaseChecklist;
    private String operatorId;
    private Long createTime;
}
