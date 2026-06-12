package com.yx.uavfire.fc100.event.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 批量地形高程查询（WGS84），供前端高度剖面图使用。走 manage 前缀（JWT 鉴权链路）。 */
@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/terrain")
public class TerrainController {

    private static final int MAX_POINTS = 500;

    private final TerrainElevationService terrainElevationService;

    public TerrainController(TerrainElevationService terrainElevationService) {
        this.terrainElevationService = terrainElevationService;
    }

    @PostMapping("/elevations")
    public HttpResultResponse elevations(@RequestBody List<Map<String, Double>> points) {
        if (points == null || points.isEmpty()) {
            return HttpResultResponse.error("points required");
        }
        if (points.size() > MAX_POINTS) {
            return HttpResultResponse.error("max " + MAX_POINTS + " points");
        }
        List<Double> elevations = new ArrayList<>(points.size());
        for (Map<String, Double> p : points) {
            Double lat = p.get("lat");
            Double lng = p.get("lng");
            if (lat == null || lng == null) {
                return HttpResultResponse.error("lat/lng required for every point");
            }
            elevations.add(terrainElevationService.queryElevation(lat, lng).orElse(null));
        }
        return HttpResultResponse.success(elevations);
    }
}
