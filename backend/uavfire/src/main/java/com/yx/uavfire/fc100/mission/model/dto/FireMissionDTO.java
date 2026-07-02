package com.yx.uavfire.fc100.mission.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class FireMissionDTO {
    private Long id;
    private String missionNo;
    private String workspaceId;
    private String status;
    private Long version;
    private Long fireEventId;
    private Long parentMissionId;
    private Integer attemptIndex;
    private String aircraftSn;
    private String payloadId;
    private String payloadType;
    private Double waterLoadLiters;
    private Double takeoffLat;
    private Double takeoffLng;
    private Double takeoffAlt;
    private Double windSpeedAtApproval;
    private Double windDirectionDeg;
    private String djiTaskId;
    private Long latestRouteFileId;
    private String releasePolicy;
    private String releaseExecutionMode;
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
    private Long createTime;
    private Long updateTime;
    /** spec §4.7 — 前端按钮可见性数据，FireMissionEvent.name() 列表 */
    private List<String> availableActions;
}
