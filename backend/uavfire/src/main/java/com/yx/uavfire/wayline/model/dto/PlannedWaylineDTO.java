package com.yx.uavfire.wayline.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlannedWaylineDTO {

    private String plannedWaylineId;

    private String workspaceId;

    private String name;

    private String aircraftModelKey;

    private String payloadModelKey;

    private Integer payloadPositionIndex;

    private String gatewaySn;

    private String aircraftSn;

    private Double defaultHeight;

    private Double maxSpeed;

    // L1 mission 配置
    private String finishAction;
    private String exitOnRcLost;
    private String rcLostAction;
    private Integer takeoffSecurityHeight;
    private Double globalTransitionalSpeed;
    private Integer rthAltitude;

    private List<PlannedWaypointDTO> waypoints;

    private String status;

    private String publishedWaylineId;

    private String kmzUrl;

    private String kmzMd5;

    private String kmzObjectKey;

    private Long fileGeneratedTime;

    private String flightId;

    private String dockSn;

    private String droneSn;

    private String taskStatus;

    private String taskStatusReason;

    private Integer taskProgress;

    // L2 实时任务进度
    private Integer waylineMissionState;
    private Integer currentWaypointIndex;
    private Integer totalWaypoints;
    private Integer mediaCount;
    private String breakPointJson;
    private Long lastProgressTime;

    // Realtime aircraft position for MSDK/agent wayline execution.
    private Double aircraftLng;
    private Double aircraftLat;
    private Double aircraftGcjLng;
    private Double aircraftGcjLat;
    private Double aircraftHeight;
    private Long aircraftUpdatedAt;

    private Long preparedTime;

    private Long executedTime;

    private String creator;

    private String publisher;

    private Long publishTime;

    private Long createTime;

    private Long updateTime;
}
