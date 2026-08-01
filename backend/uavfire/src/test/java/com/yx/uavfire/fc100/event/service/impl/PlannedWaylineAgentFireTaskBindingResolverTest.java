package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.service.AgentFireTaskBindingResolver;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannedWaylineAgentFireTaskBindingResolverTest {
    private final IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
    private final PlannedWaylineAgentFireTaskBindingResolver resolver =
        new PlannedWaylineAgentFireTaskBindingResolver(mapper);

    @Test void locksFlightIdAndReturnsRealWorkspaceForAssignedExecutingDrone() {
        when(mapper.selectByFlightIdForUpdate("flight-1")).thenReturn(task("executing", "drone-1"));
        AgentFireTaskBindingResolver.Binding binding =
            resolver.resolveForInitialReport("flight-1", "drone-1", 2_000L);
        assertNotNull(binding); assertEquals("workspace-7", binding.getWorkspaceId());
        assertEquals("flight-1", binding.getFlightId()); assertEquals("drone-1", binding.getDroneSn());
        verify(mapper).selectByFlightIdForUpdate("flight-1");
    }

    @Test void rejectsMissingWrongDroneInconsistentAssignmentAndNonExecutingTask() {
        assertNull(resolver.resolveForInitialReport("missing", "drone-1", 2_000L));
        when(mapper.selectByFlightIdForUpdate("flight-1")).thenReturn(task("executing", "drone-2"));
        assertNull(resolver.resolveForInitialReport("flight-1", "drone-1", 2_000L));
        PlannedWaylineEntity inconsistent = task("executing", "drone-1"); inconsistent.setAircraftSn("drone-2");
        when(mapper.selectByFlightIdForUpdate("flight-1")).thenReturn(inconsistent);
        assertNull(resolver.resolveForInitialReport("flight-1", "drone-1", 2_000L));
        when(mapper.selectByFlightIdForUpdate("flight-1")).thenReturn(task("ready", "drone-1"));
        assertNull(resolver.resolveForInitialReport("flight-1", "drone-1", 2_000L));
    }

    @Test void acceptsBoundHistoricalReplayOnlyInsideRecordedExecutionWindow() {
        PlannedWaylineEntity finished = task("finished", "drone-1");
        finished.setExecutedTime(1_000L); finished.setLastProgressTime(10_000L);
        when(mapper.selectByFlightIdForUpdate("flight-1")).thenReturn(finished);
        assertNotNull(resolver.resolveForInitialReport("flight-1", "drone-1", 5_000L));
        assertNull(resolver.resolveForInitialReport("flight-1", "drone-1", 1_000_000L));
    }

    @Test void mapperUsesAConcurrencySafeFlightIdCurrentRead() throws Exception {
        Method method = IPlannedWaylineMapper.class.getMethod("selectByFlightIdForUpdate", String.class);
        Select sql = method.getAnnotation(Select.class);
        assertNotNull(sql);
        String joined = String.join(" ", sql.value()).toLowerCase(java.util.Locale.ROOT);
        assertTrue(joined.contains("where flight_id=#{flightid}"));
        assertTrue(joined.contains("for update"));
        assertTrue(joined.contains("workspace_id")); assertTrue(joined.contains("drone_sn"));
    }

    private PlannedWaylineEntity task(String status, String droneSn) {
        return PlannedWaylineEntity.builder().plannedWaylineId("plan-1").workspaceId("workspace-7")
            .flightId("flight-1").droneSn(droneSn).aircraftSn(droneSn).taskStatus(status)
            .executedTime(1_000L).updateTime(10_000L).build();
    }
}
