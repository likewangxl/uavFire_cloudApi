package com.dji.sample.wayline.model.entity;

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

    @TableField("gateway_sn")
    private String gatewaySn;

    @TableField("aircraft_sn")
    private String aircraftSn;

    @TableField("default_height")
    private Double defaultHeight;

    @TableField("max_speed")
    private Double maxSpeed;

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
