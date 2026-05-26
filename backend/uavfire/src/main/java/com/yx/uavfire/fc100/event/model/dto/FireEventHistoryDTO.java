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
    private Double thermalTemperature;
    private String temperatureUnit;
    private String thermalImageUrl;
    private String visibleImageUrl;
    private Long eventTimestamp;
    private String action;
    private Long createTime;
}
