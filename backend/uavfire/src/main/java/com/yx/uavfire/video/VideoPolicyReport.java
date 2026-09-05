package com.yx.uavfire.video;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class VideoPolicyReport {
    private Integer protocolVersion;
    private String instanceId;
    private String appliedProfile;
    private Boolean streaming;
    private Integer configuredBitrateBps;
    // MSDK's vbps unit is retained as raw until calibrated against media ingress.
    private Integer sdkVbps;
    private Integer sdkFps;
    private Integer sdkWidth;
    private Integer sdkHeight;
    private Long sdkSampleAgeMs;
    private String error;
}
