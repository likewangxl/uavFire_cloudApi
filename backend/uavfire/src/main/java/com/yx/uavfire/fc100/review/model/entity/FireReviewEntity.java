package com.yx.uavfire.fc100.review.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fc100_fire_review")
public class FireReviewEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long missionId;
    private Double beforeTemperature;
    private Double afterTemperature;
    private String temperatureUnit;
    private String afterThermalImageUrl;
    private String afterVisibleImageUrl;
    private Integer fireSuppressed;
    private Integer needSecondDrop;
    private String suggestion;
    private String reviewerId;
    private String remark;
    private Integer deleted;
    private Long createTime;
    private Long updateTime;
}
