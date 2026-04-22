package com.dji.sample.wayline.model.param;

import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class CreatePlannedWaylineParam {

    @NotBlank
    private String name;

    @NotBlank
    private String aircraftModelKey;

    @NotBlank
    private String gatewaySn;

    @NotBlank
    private String aircraftSn;

    @NotNull
    private Double defaultHeight;

    @NotNull
    private Double maxSpeed;

    @NotEmpty
    @Valid
    private List<PlannedWaypointDTO> waypoints;
}
