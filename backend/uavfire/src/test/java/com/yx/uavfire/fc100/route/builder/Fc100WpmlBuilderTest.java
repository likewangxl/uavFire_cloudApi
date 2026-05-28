package com.yx.uavfire.fc100.route.builder;

import com.yx.uavfire.fc100.route.config.Fc100RouteProperties;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fc100WpmlBuilderTest {

    @Test
    void buildFc100CargoWaylineUsesConfiguredNoActionFinishAction() {
        Fc100RouteProperties props = new Fc100RouteProperties();
        props.setFinishAction("noAction");
        Fc100WpmlBuilder builder = new Fc100WpmlBuilder(props);
        WpmlBuildContext ctx = WpmlBuildContext.builder()
            .missionNo("M-FC100-001")
            .waypoints(List.of(waypoint(0, 31.0, 121.0, 80.0)))
            .createTimeMs(1000L)
            .build();

        String template = new String(builder.buildTemplateKml(ctx), StandardCharsets.UTF_8);
        String waylines = new String(builder.buildWaylinesWpml(ctx), StandardCharsets.UTF_8);

        assertTrue(template.contains("<wpml:finishAction>noAction</wpml:finishAction>"));
        assertTrue(waylines.contains("<wpml:finishAction>noAction</wpml:finishAction>"));
        assertFalse(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"));
        assertFalse(waylines.contains("<wpml:finishAction>goHome</wpml:finishAction>"));
    }

    private static MissionWaypointDTO waypoint(int index, double lat, double lng, double alt) {
        MissionWaypointDTO waypoint = new MissionWaypointDTO();
        waypoint.setWaypointIndex(index);
        waypoint.setLat(lat);
        waypoint.setLng(lng);
        waypoint.setAlt(alt);
        waypoint.setSpeed(5.0);
        return waypoint;
    }
}
