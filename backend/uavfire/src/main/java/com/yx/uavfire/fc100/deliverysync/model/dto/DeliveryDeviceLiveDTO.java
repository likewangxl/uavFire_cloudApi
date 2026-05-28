package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryDeviceLiveDTO {
    private String deviceSn;
    private String streamStatus;
    private String playUrl;
    private String source;
    private String message;
}
