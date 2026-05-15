package com.yx.uavfire.fc100.waypoint.model.dto;

import lombok.Data;

@Data
public class MissionWaypointDTO {
    private Integer waypointIndex;
    private String waypointType;
    private Double lat;
    private Double lng;
    private Double alt;
    private String altitudeReference;
    private Double speed;
    private String action;
    private String remark;
}
