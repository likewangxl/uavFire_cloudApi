package com.yx.uavfire.fc100.operation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_incident")
public class OperationIncidentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String incidentNo;
    private Long fireEventId;
    private String level;
    private String status;
    private Double centerLat;
    private Double centerLng;
    private Double riskRadiusM;
    private String createdBy;
    private String confirmedBy;
    private Integer recommendedRecheck;
    private String recheckReason;
    private Long closedAt;
    private Long createTime;
    private Long updateTime;
}
