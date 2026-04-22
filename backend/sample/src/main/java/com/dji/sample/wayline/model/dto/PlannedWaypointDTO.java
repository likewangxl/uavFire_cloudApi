package com.dji.sample.wayline.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@Accessors(chain = true)
public class PlannedWaypointDTO {

    @NotNull
    @Min(1)
    private Integer order;

    @NotNull
    private Double gcjLng;

    @NotNull
    private Double gcjLat;

    @NotNull
    private Double wgsLng;

    @NotNull
    private Double wgsLat;

    @NotNull
    private Double height;
}
