package com.yx.uavfire.msdk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MsdkDeviceStateDTO {

    private String gatewaySn;

    private String aircraftSn;

    private Boolean online;

    private String connectionState;

    private String deviceName;

    private String model;

    private String mode;

    private Double latitude;

    private Double longitude;

    private Double height;

    private Double elevation;

    private Double homeDistance;

    private Double horizontalSpeed;

    private Double verticalSpeed;

    private Double windSpeed;

    private Integer batteryPercent;

    private Integer gpsCount;

    private Integer rtkCount;

    private Boolean positionFixed;

    private Long updatedAt;

    private Map<String, Boolean> capabilities;
}
