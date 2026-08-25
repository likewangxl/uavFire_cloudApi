package com.yx.uavfire.msdk.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PayloadCapabilityDTO {
    private String payloadModelKey;
    private Integer payloadPositionIndex;
    private Boolean visibleSupported;
    private Boolean thermalSupported;
    private Boolean laserSupported;
    private Boolean tapZoomSupported;
    private Boolean liveStreamSupported;
    private Boolean waylineSupported;
}
