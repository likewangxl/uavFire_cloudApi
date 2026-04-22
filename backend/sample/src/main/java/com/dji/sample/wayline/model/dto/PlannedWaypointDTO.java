package com.dji.sample.wayline.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
public class PlannedWaypointDTO {

    private Integer order;

    private Double gcjLng;

    private Double gcjLat;

    private Double wgsLng;

    private Double wgsLat;

    private Double height;
}
