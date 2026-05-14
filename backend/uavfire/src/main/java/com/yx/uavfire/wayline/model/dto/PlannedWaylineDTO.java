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

    private String gatewaySn;

    private String aircraftSn;

    private Double defaultHeight;

    private Double maxSpeed;

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

    private Long preparedTime;

    private Long executedTime;

    private String creator;

    private String publisher;

    private Long publishTime;

    private Long createTime;

    private Long updateTime;
}
