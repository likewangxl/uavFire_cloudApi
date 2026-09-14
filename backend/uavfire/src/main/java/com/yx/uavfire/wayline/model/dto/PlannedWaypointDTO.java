package com.yx.uavfire.wayline.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlannedWaypointDTO {

    @NotNull
    @Min(1)
    private Integer order;

    @NotNull
    @JsonAlias({"gcjLng", "gcj_lng", "gcjLongitude"})
    private Double gcjLng;

    @NotNull
    @JsonAlias({"gcjLat", "gcj_lat", "gcjLatitude"})
    private Double gcjLat;

    @NotNull
    @JsonAlias({"wgsLng", "wgs_lng", "wgs84Lng", "wgs84_lng", "wgsLongitude"})
    private Double wgsLng;

    @NotNull
    @JsonAlias({"wgsLat", "wgs_lat", "wgs84Lat", "wgs84_lat", "wgsLatitude"})
    private Double wgsLat;

    @NotNull
    private Double height;

    // ---- L1 per-航点定制字段(全部可空,缺省走全局)。契约见 WAYLINE_L1_L2_CONTRACT.md 2.1 ----

    /** 覆盖全局自动飞行速度(m/s);null = 用 entity.maxSpeed。 */
    private Double speed;

    /** 云台俯仰角度(绝对角,°,范围 -90~30);null = -45(航线火情识别默认前下视)。 */
    private Double gimbalPitch;

    /** 云台偏航角度(绝对角,°,范围 -180~180);null = 跟随机头。 */
    private Double gimbalYaw;

    /** followWayline | smoothTransition | fixed | towardPOI;null = followWayline。 */
    private String headingMode;

    /** headingMode=fixed 时的角度(°);null = 0。 */
    private Double headingAngle;

    private Double poiLng;
    private Double poiLat;
    private Double poiAlt;

    /**
     * 5 个值之一:coordinateTurn | toPointAndStopWithDiscontinuityCurvature |
     * toPointAndStopWithContinuityCurvature | toPointAndPassWithContinuityCurvature |
     * toPointAndPassWithContinuityCurvatureAndCustomDamping。
     * null = toPointAndPassWithContinuityCurvature。
     */
    private String turnMode;

    /** 转弯阻尼距离(米);null = 10。 */
    private Double turnDamping;

    /** 航点动作列表;null 或空 = 不挂动作。 */
    @Valid
    private List<WaypointActionDTO> actions;

    @JsonSetter(nulls = Nulls.SKIP)
    public PlannedWaypointDTO setGcjLng(Double gcjLng) {
        this.gcjLng = gcjLng;
        return this;
    }

    @JsonSetter(nulls = Nulls.SKIP)
    public PlannedWaypointDTO setGcjLat(Double gcjLat) {
        this.gcjLat = gcjLat;
        return this;
    }

    @JsonSetter(nulls = Nulls.SKIP)
    public PlannedWaypointDTO setWgsLng(Double wgsLng) {
        this.wgsLng = wgsLng;
        return this;
    }

    @JsonSetter(nulls = Nulls.SKIP)
    public PlannedWaypointDTO setWgsLat(Double wgsLat) {
        this.wgsLat = wgsLat;
        return this;
    }

    @JsonAlias({"lng", "longitude"})
    @JsonSetter(nulls = Nulls.SKIP)
    public PlannedWaypointDTO setLegacyLng(Double lng) {
        if (gcjLng == null) {
            gcjLng = lng;
        }
        if (wgsLng == null) {
            wgsLng = lng;
        }
        return this;
    }

    @JsonAlias({"lat", "latitude"})
    @JsonSetter(nulls = Nulls.SKIP)
    public PlannedWaypointDTO setLegacyLat(Double lat) {
        if (gcjLat == null) {
            gcjLat = lat;
        }
        if (wgsLat == null) {
            wgsLat = lat;
        }
        return this;
    }
}
