package com.dji.sample.wayline.model.dto;

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

    private String creator;

    private Long createTime;

    private Long updateTime;
}
