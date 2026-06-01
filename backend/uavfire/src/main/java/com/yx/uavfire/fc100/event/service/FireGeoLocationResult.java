package com.yx.uavfire.fc100.event.service;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class FireGeoLocationResult {
    private Double lat;
    private Double lng;
    private Double alt;
    private String geoMethod;
    private Double geoErrorRadiusM;
    private String geoQuality;
    private Long geoSourceTs;
}
