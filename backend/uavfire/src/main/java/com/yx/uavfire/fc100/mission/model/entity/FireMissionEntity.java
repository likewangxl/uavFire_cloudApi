package com.yx.uavfire.fc100.mission.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

@Data
@TableName("fc100_fire_mission")
public class FireMissionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String missionNo;
    private String workspaceId;
    private Long fireEventId;
    private Long incidentId;
    private Long parentMissionId;
    private Integer attemptIndex;
    private String aircraftSn;
    private String payloadId;
    private String payloadType;
    private Double waterLoadLiters;
    private Double estimatedTotalWeightKg;
    private Double takeoffLat;
    private Double takeoffLng;
    private Double takeoffAlt;
    private Double windSpeedAtApproval;
    private Double windDirectionDeg;
    private String djiTaskId;
    private Long latestRouteFileId;
    private String releasePolicy;
    private String releaseExecutionMode;
    private String releaseConfirmationToken;
    private Long releasePendingStartedAt;
    private Long releaseTokenExpiresAt;
    private Long releaseTokenUsedAt;
    private String status;
    @Version
    private Long version;
    private Integer isHighConfidence;
    private String createdBy;
    private String approverId;
    private String releaseOperatorId;
    private String reviewerId;
    private Long approvedAt;
    private Long startedAt;
    private Long payloadReleasedAt;
    private Long completedAt;
    private Long archivedAt;
    private String failedReason;
    private Integer deleted;
    private String updatedBy;
    private Long createTime;
    private Long updateTime;
}
