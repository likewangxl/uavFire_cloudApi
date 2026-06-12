package com.yx.uavfire.fc100.event.controller;

import com.dji.sdk.common.HttpResultResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TerrainControllerTest {

    private static Map<String, Double> pt(Double lat, Double lng) {
        Map<String, Double> m = new HashMap<>();
        if (lat != null) m.put("lat", lat);
        if (lng != null) m.put("lng", lng);
        return m;
    }

    @Test
    void returnsElevationsInOrderWithNullForMissing() {
        TerrainController c = new TerrainController((lat, lng) ->
                lat > 39 ? Optional.of(123.5) : Optional.empty());
        List<Map<String, Double>> body = new ArrayList<>();
        body.add(pt(39.5, 115.5));
        body.add(pt(38.5, 115.5));
        HttpResultResponse resp = c.elevations(body);
        assertEquals(HttpResultResponse.CODE_SUCCESS, resp.getCode());
        @SuppressWarnings("unchecked")
        List<Double> data = (List<Double>) resp.getData();
        assertEquals(123.5, data.get(0), 0.001);
        assertNull(data.get(1));
    }

    @Test
    void rejectsEmptyAndOversizedAndMalformed() {
        TerrainController c = new TerrainController((lat, lng) -> Optional.of(1.0));
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(null).getCode());
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(new ArrayList<>()).getCode());
        List<Map<String, Double>> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(pt(39.5, 115.5));
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(tooMany).getCode());
        List<Map<String, Double>> missingLng = new ArrayList<>();
        missingLng.add(pt(39.5, null));
        assertNotEquals(HttpResultResponse.CODE_SUCCESS, c.elevations(missingLng).getCode());
    }
}
