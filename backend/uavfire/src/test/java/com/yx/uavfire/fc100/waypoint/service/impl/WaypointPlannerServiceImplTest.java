package com.yx.uavfire.fc100.waypoint.service.impl;

import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.waypoint.config.Fc100WaypointProperties;
import com.yx.uavfire.fc100.waypoint.dao.MissionWaypointMapper;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.model.param.WaypointGenerateParam;
import com.yx.uavfire.fc100.waypoint.policy.DropPointPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class WaypointPlannerServiceImplTest {

    @Test
    void nearFirePointUsesShortestDirectDropRouteWithoutReturnLeg() {
        WaypointPlannerServiceImpl planner = planner();
        WaypointGenerateParam param = baseParam();
        param.setTakeoffLat(34.66790354687964);
        param.setTakeoffLng(109.32668526317416);
        param.setFireLat(34.667795);
        param.setFireLng(109.326400);
        param.setWindSpeed(0.0);
        param.setWindDirectionDeg(0.0);

        List<MissionWaypointDTO> waypoints = planner.plan(param);

        assertEquals(2, waypoints.size());
        assertEquals("TAKEOFF", waypoints.get(0).getWaypointType());
        assertEquals("DROP", waypoints.get(1).getWaypointType());
        assertEquals(34.667795, waypoints.get(1).getLat());
        assertEquals(109.326400, waypoints.get(1).getLng());
        assertFalse(waypoints.stream().anyMatch(wp -> "RETURN".equals(wp.getWaypointType())));
    }

    @Test
    void fartherFirePointKeepsFullSafetyRoute() {
        WaypointPlannerServiceImpl planner = planner();
        WaypointGenerateParam param = baseParam();

        List<MissionWaypointDTO> waypoints = planner.plan(param);

        assertEquals(7, waypoints.size());
        assertEquals("RETURN", waypoints.get(6).getWaypointType());
    }

    private WaypointPlannerServiceImpl planner() {
        Fc100WaypointProperties props = new Fc100WaypointProperties();
        DropPointPolicy dropPointPolicy = new DropPointPolicy(props);
        return new WaypointPlannerServiceImpl(dropPointPolicy, props,
            mock(MissionWaypointMapper.class), mock(Clock.class));
    }

    private WaypointGenerateParam baseParam() {
        WaypointGenerateParam param = new WaypointGenerateParam();
        param.setOperatorId("operator-1");
        param.setTakeoffLat(34.66790354687964);
        param.setTakeoffLng(109.32668526317416);
        param.setTakeoffAlt(336.35296630859375);
        param.setFireLat(34.65862274169922);
        param.setFireLng(109.34061431884766);
        param.setFireAlt(0.0);
        param.setWindSpeed(0.0);
        param.setWindDirectionDeg(0.0);
        return param;
    }
}
