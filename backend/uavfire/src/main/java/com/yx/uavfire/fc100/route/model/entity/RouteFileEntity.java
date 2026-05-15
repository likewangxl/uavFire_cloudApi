package com.yx.uavfire.fc100.route.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("fc100_route_file")
public class RouteFileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long missionId;
    private String fileType;
    private String schemaVersion;
    private String fileName;
    private String objectKey;
    private String sign;
    private Long size;
    private String generatorVersion;
    private Integer isLatest;
    private String createdBy;
    private Long createTime;
    private Long updateTime;
}
