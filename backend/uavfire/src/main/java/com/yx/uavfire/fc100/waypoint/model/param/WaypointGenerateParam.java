package com.yx.uavfire.fc100.waypoint.model.param;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class WaypointGenerateParam {
    @NotBlank private String operatorId;
    @NotNull private Double takeoffLat;
    @NotNull private Double takeoffLng;
    @NotNull private Double takeoffAlt;
    @NotNull private Double fireLat;
    @NotNull private Double fireLng;
    @NotNull private Double fireAlt;
    /** 风从这个方向来；气象学惯例 [0,360) */
    @NotNull @DecimalMin("0.0") @DecimalMax("359.9999")
    private Double windDirectionDeg;
    @NotNull @DecimalMin("0.0")
    private Double windSpeed;
    /** 可选覆盖配置 */
    private Double cruiseAlt;
    private Double dropAltAgl;
    private Double approachDistance;
    private Double exitDistance;
    private Double speed;
}
