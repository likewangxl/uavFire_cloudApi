package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.Data;

@Data
public class DeliveryDeviceProperties {
    private String deviceSn;
    private Boolean onlineStatus;
    private Integer batteryPercent;
    private String rtkStatus;       // FIX / FLOAT / NONE
    private Double latitude;
    private Double longitude;
    private Double altitude;
    private Integer aircraftMode;
    private Boolean flying;
    private Double horizontalSpeed;
    private Double verticalSpeed;
    private Double homeDistance;
    private Double windSpeed;
    private Long osdTimestamp;
}
