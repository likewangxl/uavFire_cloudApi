package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MissingTerrainElevationService implements TerrainElevationService {
    @Override
    public Optional<Double> queryElevation(double lat, double lng) {
        return Optional.empty();
    }
}
