package com.yx.uavfire.fc100.waypoint.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fc100_mission_waypoint")
public class MissionWaypointEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long missionId;
    private Integer waypointVersion;
    private Integer waypointIndex;
    private String waypointType;
    private Double lat;
    private Double lng;
    private Double alt;
    private String altitudeReference;
    private Double speed;
    private String action;
    private String remark;
    private Long createTime;
    private Long updateTime;
}
