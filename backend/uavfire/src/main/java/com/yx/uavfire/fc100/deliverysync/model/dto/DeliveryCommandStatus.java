package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DeliveryCommandStatus {
    private String deviceSn;
    private Map<String, Object> services;
}
