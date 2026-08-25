package com.yx.uavfire.wayline.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("planned_wayline")
public class PlannedWaylineEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("planned_wayline_id")
    private String plannedWaylineId;

    @TableField("workspace_id")
    private String workspaceId;

    @TableField("name")
    private String name;

    @TableField("aircraft_model_key")
    private String aircraftModelKey;

    @TableField("payload_model_key")
    private String payloadModelKey;

    @TableField("payload_position_index")
    private Integer payloadPositionIndex;

    @TableField("gateway_sn")
    private String gatewaySn;

    @TableField("aircraft_sn")
    private String aircraftSn;

    @TableField("default_height")
    private Double defaultHeight;

    @TableField("max_speed")
    private Double maxSpeed;

    // ---- L1 全局 mission 配置(KMZ <wpml:missionConfig>) ----
    @TableField("finish_action")
    private String finishAction;

    @TableField("exit_on_rc_lost")
    private String exitOnRcLost;

    @TableField("rc_lost_action")
    private String rcLostAction;

    @TableField("takeoff_security_height")
    private Integer takeoffSecurityHeight;

    @TableField("global_transitional_speed")
    private Double globalTransitionalSpeed;

    @TableField("rth_altitude")
    private Integer rthAltitude;

    @TableField("waypoints_json")
    private String waypointsJson;

    @TableField("status")
    private String status;

    @TableField("published_wayline_id")
    private String publishedWaylineId;

    @TableField("kmz_url")
    private String kmzUrl;

    @TableField("kmz_md5")
    private String kmzMd5;

    @TableField("kmz_object_key")
    private String kmzObjectKey;

    @TableField("file_generated_time")
    private Long fileGeneratedTime;

    @TableField("flight_id")
    private String flightId;

    @TableField("dock_sn")
    private String dockSn;

    @TableField("drone_sn")
    private String droneSn;

    @TableField("task_status")
    private String taskStatus;

    @TableField("task_status_reason")
    private String taskStatusReason;

    @TableField("task_progress")
    private Integer taskProgress;

    // ---- L2 实时任务进度(agent / dock 共用) ----
    @TableField("wayline_mission_state")
    private Integer waylineMissionState;

    @TableField("current_waypoint_index")
    private Integer currentWaypointIndex;

    @TableField("total_waypoints")
    private Integer totalWaypoints;

    @TableField("media_count")
    private Integer mediaCount;

    @TableField("break_point_json")
    private String breakPointJson;

    @TableField("last_progress_time")
    private Long lastProgressTime;

    @TableField("prepared_time")
    private Long preparedTime;

    @TableField("executed_time")
    private Long executedTime;

    @TableField("creator")
    private String creator;

    @TableField("publisher")
    private String publisher;

    @TableField("publish_time")
    private Long publishTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Long createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Long updateTime;
}
