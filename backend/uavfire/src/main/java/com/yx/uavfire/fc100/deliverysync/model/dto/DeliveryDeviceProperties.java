package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.Data;

@Data
public class DeliveryDeviceProperties {
    private String deviceSn;
    private Integer batteryPercent;
    private String rtkStatus;       // FIX / FLOAT / NONE
    private Double latitude;
    private Double longitude;
    private Double altitude;
    private Long osdTimestamp;
}
