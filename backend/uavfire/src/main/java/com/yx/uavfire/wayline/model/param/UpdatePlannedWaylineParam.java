package com.yx.uavfire.wayline.model.param;

import com.yx.uavfire.wayline.model.dto.PlannedWaypointDTO;
import com.yx.uavfire.wayline.model.dto.PlannedAreaVertexDTO;
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

    @JsonAlias({"payloadModelKey", "payload_model_key"})
    private String payloadModelKey;

    @JsonAlias({"payloadPositionIndex", "payload_position_index"})
    private Integer payloadPositionIndex;

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

    @JsonAlias({"routeKind", "route_kind"})
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private String routeKind = "waypoint";

    @Valid
    @JsonAlias({"areaPolygon", "area_polygon"})
    private List<PlannedAreaVertexDTO> areaPolygon;

    @JsonAlias({"areaCameraKey", "area_camera_key"})
    private String areaCameraKey;

    @JsonAlias({"areaFrontOverlap", "area_front_overlap"})
    private Integer areaFrontOverlap;

    @JsonAlias({"areaSideOverlap", "area_side_overlap"})
    private Integer areaSideOverlap;

    @JsonAlias({"areaHeadingDeg", "area_heading_deg"})
    private Double areaHeadingDeg;

    // ---- L1 mission 配置(可选,缺省走 DB 列默认值)。契约见 WAYLINE_L1_L2_CONTRACT.md 2.3 ----

    @JsonAlias({"finishAction", "finish_action"})
    private String finishAction;

    @JsonAlias({"exitOnRcLost", "exit_on_rc_lost"})
    private String exitOnRcLost;

    @JsonAlias({"rcLostAction", "rc_lost_action"})
    private String rcLostAction;

    @JsonAlias({"takeoffSecurityHeight", "takeoff_security_height"})
    private Integer takeoffSecurityHeight;

    @JsonAlias({"globalTransitionalSpeed", "global_transitional_speed"})
    private Double globalTransitionalSpeed;

    @JsonAlias({"rthAltitude", "rth_altitude"})
    private Integer rthAltitude;

    @NotEmpty
    @Valid
    private List<PlannedWaypointDTO> waypoints;
}
