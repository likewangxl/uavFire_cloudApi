package com.yx.uavfire.fc100.event.model.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FireEventHistoryDTO {
    private Long id;
    private Long fireEventId;
    private String eventId;
    private String sourceEventId;
    private String workspaceId;
    private String source;
    private String deviceSn;
    private BigDecimal confidence;
    private String fireLevel;
    private Double lat;
    private Double lng;
    private Double alt;
    private String altitudeReference;
    private String geoMethod;
    private Double geoErrorRadiusM;
    private String geoQuality;
    private Long geoSourceTs;
    private Double aircraftLat;
    private Double aircraftLng;
    private Double aircraftAlt;
    private Double gimbalPitch;
    private Double gimbalYaw;
    private Double gimbalRoll;
    private String thermalRoi;
    private Double thermalTemperature;
    private String temperatureUnit;
    private String thermalImageUrl;
    private String visibleImageUrl;
    private Long eventTimestamp;
    private String detectionKind;
    private String detectionStatus;
    private String locationStatus;
    private String flightStatus;
    private String action;
    private Long createTime;
}
