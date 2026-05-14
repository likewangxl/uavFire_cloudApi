package com.dji.sample.wayline.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
