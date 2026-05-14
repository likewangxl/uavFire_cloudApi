package com.dji.sample.control.service.impl;

import com.dji.sdk.cloudapi.control.Point;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlServiceImplHeightNormalizationTest {

    @Test
    void normalizeLegacyTakeoffTargetHeightConvertsRelativeTargetToAbsolute() {
        Float normalized = ControlServiceImpl.normalizeLegacyTakeoffTargetHeight(30.0, 353.7f, 30.0f);
        assertEquals(383.7f, normalized);
    }

    @Test
    void normalizeLegacyTakeoffTargetHeightKeepsAbsoluteTargetUntouched() {
        Float normalized = ControlServiceImpl.normalizeLegacyTakeoffTargetHeight(383.7, 353.7f, 30.0f);
        assertEquals(383.7f, normalized);
    }

    @Test
    void normalizeLegacyFlyToPointHeightsConvertsRelativeTargetsToAbsolute() {
        List<Point> points = List.of(new Point().setLatitude(34.0f).setLongitude(109.0f).setHeight(30.0f));
        List<Point> normalized = ControlServiceImpl.normalizeLegacyFlyToPointHeights(points, 353.7f);
        assertEquals(383.7f, normalized.get(0).getHeight());
    }

    @Test
    void normalizeLegacyFlyToPointHeightsKeepsAbsoluteTargetsUntouched() {
        List<Point> points = List.of(new Point().setLatitude(34.0f).setLongitude(109.0f).setHeight(383.7f));
        List<Point> normalized = ControlServiceImpl.normalizeLegacyFlyToPointHeights(points, 353.7f);
        assertEquals(383.7f, normalized.get(0).getHeight());
    }
}
