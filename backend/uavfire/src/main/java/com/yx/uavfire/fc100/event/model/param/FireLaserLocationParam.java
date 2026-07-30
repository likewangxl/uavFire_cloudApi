package com.yx.uavfire.fc100.event.model.param;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;

@Data
@Accessors(chain = true)
public class FireLaserLocationParam {

    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private Double fireLat;

    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private Double fireLng;

    private Double fireAlt;

    @NotNull
    private Long sourceTs;

    private Double geoErrorRadiusM;
}
