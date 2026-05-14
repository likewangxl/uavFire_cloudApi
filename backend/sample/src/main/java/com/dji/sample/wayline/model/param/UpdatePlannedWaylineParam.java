package com.dji.sample.wayline.model.param;

import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
public class UpdatePlannedWaylineParam {

    @NotBlank
    private String name;

    @NotBlank
    @JsonAlias({"aircraftModelKey", "aircraft_model_key", "droneModelKey", "drone_model_key"})
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private String aircraftModelKey = "M30T";

    @JsonAlias({"gatewaySn", "gateway_sn"})
    private String gatewaySn;

    @JsonAlias({"aircraftSn", "aircraft_sn"})
    private String aircraftSn;

    @NotNull
    @JsonAlias({"defaultHeight", "default_height"})
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Double defaultHeight = 30.0;

    @NotNull
    @JsonAlias({"maxSpeed", "max_speed"})
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Double maxSpeed = 5.0;

    @NotEmpty
    @Valid
    private List<PlannedWaypointDTO> waypoints;
}
