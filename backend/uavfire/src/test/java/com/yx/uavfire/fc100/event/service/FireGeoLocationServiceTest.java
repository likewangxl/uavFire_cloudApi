package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.fc100.event.model.dto.FireGeoSnapshotDTO;
import com.yx.uavfire.fc100.event.service.impl.RayDemFireGeoLocationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireGeoLocationServiceTest {

    @Test
    void nadirCenterPixelIntersectsDemBelowAircraft() {
        RayDemFireGeoLocationService service = new RayDemFireGeoLocationService((lat, lng) -> Optional.of(100.0));

        FireGeoLocationResult result = service.resolve(snapshot(34.0, 109.0, 200.0, -90.0, roi(0.45, 0.45, 0.10, 0.10)));

        assertEquals("RAY_DEM_RTK", result.getGeoMethod());
        assertEquals("AUTO_WAYPOINT_READY", result.getGeoQuality());
        assertEquals(34.0, result.getLat(), 1e-6);
        assertEquals(109.0, result.getLng(), 1e-6);
        assertEquals(100.0, result.getAlt(), 1e-6);
        assertTrue(result.getGeoErrorRadiusM() <= 10.0);
    }

    @Test
    void marksMissingDemAsNotAutoWaypointReady() {
        RayDemFireGeoLocationService service = new RayDemFireGeoLocationService((lat, lng) -> Optional.empty());

        FireGeoLocationResult result = service.resolve(snapshot(34.0, 109.0, 200.0, -90.0, roi(0.45, 0.45, 0.10, 0.10)));

        assertEquals("DEM_MISSING", result.getGeoQuality());
        assertEquals("RAY_DEM_RTK", result.getGeoMethod());
    }

    @Test
    void marksMissingRtkAsManualReview() {
        RayDemFireGeoLocationService service = new RayDemFireGeoLocationService((lat, lng) -> Optional.of(100.0));
        FireGeoSnapshotDTO snapshot = snapshot(34.0, 109.0, 200.0, -90.0, roi(0.45, 0.45, 0.10, 0.10));
        snapshot.setRtkStatus("FLOAT");

        FireGeoLocationResult result = service.resolve(snapshot);

        assertEquals("RTK_NOT_FIXED", result.getGeoQuality());
    }

    private FireGeoSnapshotDTO snapshot(double lat, double lng, double alt, double gimbalPitch, FireGeoSnapshotDTO.ThermalRoi roi) {
        return new FireGeoSnapshotDTO()
            .setSourceTs(1779163200000L)
            .setRtkStatus("FIXED")
            .setAircraftPosition(new FireGeoSnapshotDTO.AircraftPosition()
                .setLat(lat)
                .setLng(lng)
                .setAlt(alt))
            .setAircraftAttitude(new FireGeoSnapshotDTO.Attitude()
                .setYaw(0.0)
                .setPitch(0.0)
                .setRoll(0.0))
            .setGimbalAttitude(new FireGeoSnapshotDTO.Attitude()
                .setYaw(0.0)
                .setPitch(gimbalPitch)
                .setRoll(0.0))
            .setCameraModel(new FireGeoSnapshotDTO.CameraModel()
                .setHorizontalFovDeg(60.0)
                .setVerticalFovDeg(45.0))
            .setFrameSize(new FireGeoSnapshotDTO.FrameSize()
                .setWidth(640)
                .setHeight(512))
            .setThermalRoi(roi);
    }

    private FireGeoSnapshotDTO.ThermalRoi roi(double x, double y, double width, double height) {
        return new FireGeoSnapshotDTO.ThermalRoi()
            .setX(x)
            .setY(y)
            .setWidth(width)
            .setHeight(height);
    }
}
