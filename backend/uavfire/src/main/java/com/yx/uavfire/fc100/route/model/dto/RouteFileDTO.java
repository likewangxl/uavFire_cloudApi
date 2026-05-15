package com.yx.uavfire.fc100.route.model.dto;

import lombok.Data;

@Data
public class RouteFileDTO {
    private Long id;
    private String missionNo;
    private String fileType;       // KML / KMZ
    private String schemaVersion;
    private String fileName;
    private String objectKey;
    private String sign;
    private Long size;
    private String generatorVersion;
    private Long createTime;
}
