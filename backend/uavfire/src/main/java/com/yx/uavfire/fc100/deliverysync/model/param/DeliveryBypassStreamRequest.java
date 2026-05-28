package com.yx.uavfire.fc100.deliverysync.model.param;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeliveryBypassStreamRequest {
    private String deviceSn;
    private String region;
    private String rtmpUrl;
    private String camera;
    private String video;
    private Long expireTs;
    private Integer videoQuality;
}
