package com.yx.uavfire.video;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class VideoPolicyDecision {
    private final int protocolVersion;
    private final String droneSn;
    private final String instanceId;
    private final String profile;
    private final int bitrateBps;
    private final long validForMs;
    private final String leaseId;
    private final String reason;
}
