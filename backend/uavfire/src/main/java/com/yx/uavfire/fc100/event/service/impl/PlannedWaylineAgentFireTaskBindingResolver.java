package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.service.AgentFireTaskBindingResolver;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Production binding against the same planned_wayline row that owns Agent flight execution. */
@Service
public class PlannedWaylineAgentFireTaskBindingResolver implements AgentFireTaskBindingResolver {
    private static final long TIMESTAMP_TOLERANCE_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> ACTIVE = new HashSet<>(Arrays.asList("executing", "paused"));
    private static final Set<String> HISTORICAL = new HashSet<>(Arrays.asList("finished", "stopped", "failed", "canceled"));
    private final IPlannedWaylineMapper plannedWaylines;

    public PlannedWaylineAgentFireTaskBindingResolver(IPlannedWaylineMapper plannedWaylines) {
        this.plannedWaylines = plannedWaylines;
    }

    @Override
    public Binding resolveForInitialReport(String flightId, String droneSn, long eventTimestamp) {
        PlannedWaylineEntity task = plannedWaylines.selectByFlightIdForUpdate(flightId);
        if (task == null || blank(task.getWorkspaceId()) || blank(task.getFlightId()) ||
            !flightId.equals(task.getFlightId())) return null;
        String assigned = assignedDrone(task);
        if (assigned == null || !assigned.equals(droneSn) || !allowedAt(task, eventTimestamp)) return null;
        return new Binding(task.getWorkspaceId(), task.getFlightId(), assigned);
    }

    private String assignedDrone(PlannedWaylineEntity task) {
        String taskDrone = trim(task.getDroneSn());
        String aircraft = trim(task.getAircraftSn());
        if (taskDrone != null && aircraft != null && !taskDrone.equals(aircraft)) return null;
        return taskDrone != null ? taskDrone : aircraft;
    }

    private boolean allowedAt(PlannedWaylineEntity task, long eventTimestamp) {
        String status = trim(task.getTaskStatus());
        if (status != null) status = status.toLowerCase(java.util.Locale.ROOT);
        if (ACTIVE.contains(status)) return true;
        if (!HISTORICAL.contains(status) || task.getExecutedTime() == null) return false;
        long terminalTime = task.getExecutedTime();
        if (task.getLastProgressTime() != null) terminalTime = Math.max(terminalTime, task.getLastProgressTime());
        if (task.getUpdateTime() != null) terminalTime = Math.max(terminalTime, task.getUpdateTime());
        return eventTimestamp >= task.getExecutedTime() - TIMESTAMP_TOLERANCE_MILLIS &&
            eventTimestamp <= terminalTime + TIMESTAMP_TOLERANCE_MILLIS;
    }

    private boolean blank(String value) { return trim(value) == null; }
    private String trim(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
