package com.yx.uavfire.wayline.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 面状航线测区顶点。GCJ02 用于前端地图编辑，WGS84 用于 DJI mapping2d KMZ。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class PlannedAreaVertexDTO {

    // The backend ObjectMapper uses SNAKE_CASE globally, while the web client sends camelCase.
    // Keep both spellings explicit here (as PlannedWaypointDTO already does), otherwise camelCase
    // area vertices are silently deserialized as four null coordinates.
    @JsonAlias({"gcjLng", "gcj_lng", "gcjLongitude"})
    private Double gcjLng;

    @JsonAlias({"gcjLat", "gcj_lat", "gcjLatitude"})
    private Double gcjLat;

    @JsonAlias({"wgsLng", "wgs_lng", "wgs84Lng", "wgs84_lng", "wgsLongitude"})
    private Double wgsLng;

    @JsonAlias({"wgsLat", "wgs_lat", "wgs84Lat", "wgs84_lat", "wgsLatitude"})
    private Double wgsLat;
}
