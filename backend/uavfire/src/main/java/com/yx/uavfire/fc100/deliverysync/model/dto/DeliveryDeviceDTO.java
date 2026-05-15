package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryDeviceDTO {
    private String deviceSn;
    private String deviceType;     // FC100 / FC30
    private String online;         // ONLINE / OFFLINE
    private String bindStatus;     // BOUND / UNBOUND
}
