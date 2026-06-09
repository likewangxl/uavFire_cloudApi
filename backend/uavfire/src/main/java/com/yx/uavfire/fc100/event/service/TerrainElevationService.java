package com.yx.uavfire.fc100.event.service;

import java.util.Optional;

@FunctionalInterface
public interface TerrainElevationService {
    Optional<Double> queryElevation(double lat, double lng);
}
