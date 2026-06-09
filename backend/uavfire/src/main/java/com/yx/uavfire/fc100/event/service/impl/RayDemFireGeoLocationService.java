package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.model.dto.FireGeoSnapshotDTO;
import com.yx.uavfire.fc100.event.service.FireGeoLocationResult;
import com.yx.uavfire.fc100.event.service.FireGeoLocationService;
import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class RayDemFireGeoLocationService implements FireGeoLocationService {
    public static final String METHOD = "RAY_DEM_RTK";
    public static final String QUALITY_AUTO_WAYPOINT_READY = "AUTO_WAYPOINT_READY";
    public static final double AUTO_WAYPOINT_MAX_ERROR_RADIUS_M = 10.0;
    private static final double EARTH_RADIUS_M = 6378137.0;

    private final TerrainElevationService terrainElevationService;

    public RayDemFireGeoLocationService(TerrainElevationService terrainElevationService) {
        this.terrainElevationService = terrainElevationService;
    }

    @Override
    public FireGeoLocationResult resolve(FireGeoSnapshotDTO snapshot) {
        FireGeoLocationResult result = new FireGeoLocationResult()
            .setGeoMethod(METHOD)
            .setGeoSourceTs(snapshot == null ? null : snapshot.getSourceTs());
        if (snapshot == null) {
            return result.setGeoQuality("GEO_SNAPSHOT_MISSING");
        }
        if (!isRtkFixed(snapshot.getRtkStatus())) {
            return result.setGeoQuality("RTK_NOT_FIXED");
        }
        FireGeoSnapshotDTO.AircraftPosition position = snapshot.getAircraftPosition();
        FireGeoSnapshotDTO.Attitude gimbal = snapshot.getGimbalAttitude();
        FireGeoSnapshotDTO.CameraModel camera = snapshot.getCameraModel();
        FireGeoSnapshotDTO.ThermalRoi roi = snapshot.getThermalRoi();
        if (position == null || position.getLat() == null || position.getLng() == null || position.getAlt() == null
            || gimbal == null || gimbal.getPitch() == null
            || camera == null || camera.getHorizontalFovDeg() == null || camera.getVerticalFovDeg() == null
            || roi == null) {
            return result.setGeoQuality("GEO_SNAPSHOT_INCOMPLETE");
        }

        Direction direction = cameraRay(snapshot);
        if (direction.up >= -0.01) {
            return result.setGeoQuality("RAY_NO_GROUND_INTERSECTION");
        }

        double lat = position.getLat();
        double lng = position.getLng();
        double alt = position.getAlt();
        Optional<Double> dem = terrainElevationService.queryElevation(lat, lng);
        if (dem.isEmpty()) {
            return result.setGeoQuality("DEM_MISSING");
        }

        double groundLat = lat;
        double groundLng = lng;
        double groundAlt = dem.get();
        double rangeMeters = 0.0;
        for (int i = 0; i < 4; i++) {
            double t = (groundAlt - alt) / direction.up;
            if (t <= 0.0 || Double.isNaN(t) || Double.isInfinite(t)) {
                return result.setGeoQuality("RAY_NO_GROUND_INTERSECTION");
            }
            double east = direction.east * t;
            double north = direction.north * t;
            rangeMeters = Math.sqrt(east * east + north * north + Math.pow(direction.up * t, 2));
            groundLat = lat + Math.toDegrees(north / EARTH_RADIUS_M);
            groundLng = lng + Math.toDegrees(east / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))));
            Optional<Double> nextDem = terrainElevationService.queryElevation(groundLat, groundLng);
            if (nextDem.isEmpty()) {
                return result.setGeoQuality("DEM_MISSING");
            }
            if (Math.abs(nextDem.get() - groundAlt) < 0.05) {
                groundAlt = nextDem.get();
                break;
            }
            groundAlt = nextDem.get();
        }

        double errorRadiusM = estimateErrorRadiusMeters(rangeMeters, snapshot);
        return result
            .setLat(groundLat)
            .setLng(groundLng)
            .setAlt(groundAlt)
            .setGeoErrorRadiusM(errorRadiusM)
            .setGeoQuality(errorRadiusM <= AUTO_WAYPOINT_MAX_ERROR_RADIUS_M
                ? QUALITY_AUTO_WAYPOINT_READY
                : "LOW_ACCURACY");
    }

    private Direction cameraRay(FireGeoSnapshotDTO snapshot) {
        FireGeoSnapshotDTO.ThermalRoi roi = snapshot.getThermalRoi();
        FireGeoSnapshotDTO.CameraModel camera = snapshot.getCameraModel();
        FireGeoSnapshotDTO.Attitude aircraft = snapshot.getAircraftAttitude();
        FireGeoSnapshotDTO.Attitude gimbal = snapshot.getGimbalAttitude();
        double centerX = roi.getX() + roi.getWidth() / 2.0;
        double centerY = roi.getY() + roi.getHeight() / 2.0;
        double yawOffset = Math.toDegrees(Math.atan((centerX - 0.5) * 2.0
            * Math.tan(Math.toRadians(camera.getHorizontalFovDeg()) / 2.0)));
        double pitchOffset = -Math.toDegrees(Math.atan((centerY - 0.5) * 2.0
            * Math.tan(Math.toRadians(camera.getVerticalFovDeg()) / 2.0)));
        double yaw = value(aircraft == null ? null : aircraft.getYaw())
            + value(gimbal.getYaw())
            + yawOffset;
        double pitch = value(aircraft == null ? null : aircraft.getPitch())
            + gimbal.getPitch()
            + pitchOffset;
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRad);
        return new Direction(
            horizontal * Math.sin(yawRad),
            horizontal * Math.cos(yawRad),
            Math.sin(pitchRad));
    }

    private double estimateErrorRadiusMeters(double rangeMeters, FireGeoSnapshotDTO snapshot) {
        FireGeoSnapshotDTO.ThermalRoi roi = snapshot.getThermalRoi();
        FireGeoSnapshotDTO.CameraModel camera = snapshot.getCameraModel();
        double roiMax = Math.max(value(roi.getWidth()), value(roi.getHeight()));
        double fovMax = Math.max(camera.getHorizontalFovDeg(), camera.getVerticalFovDeg());
        double roiFootprint = rangeMeters * Math.tan(Math.toRadians(fovMax * roiMax) / 2.0);
        return Math.max(3.0, 3.0 + rangeMeters * 0.03 + roiFootprint * 0.25);
    }

    private boolean isRtkFixed(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return "FIXED".equals(normalized) || "RTK_FIXED".equals(normalized) || "FIX".equals(normalized);
    }

    private double value(Double value) {
        return value == null ? 0.0 : value;
    }

    private static class Direction {
        private final double east;
        private final double north;
        private final double up;

        private Direction(double east, double north, double up) {
            this.east = east;
            this.north = north;
            this.up = up;
        }
    }
}
