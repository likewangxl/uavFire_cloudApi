package com.yx.uavfire.fc100.mission.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class MissionApproveParam {
    @NotBlank private String operatorId;
    private String aircraftSn;
    private String payloadId;
    private Double waterLoadLiters;
    @NotNull private Double takeoffLat;
    @NotNull private Double takeoffLng;
    private Double takeoffAlt;
    @NotNull private Double windSpeed;
    @NotNull private Double windDirectionDeg;
    private String remark;
}
