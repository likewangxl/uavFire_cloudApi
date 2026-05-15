package com.yx.uavfire.fc100.mission.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fc100_mission_log")
public class FireMissionLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long missionId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String operatorId;
    private String operatorRole;
    private String clientIp;
    private String requestId;
    private String idempotencyKey;
    private String remark;
    private Long createTime;
}
