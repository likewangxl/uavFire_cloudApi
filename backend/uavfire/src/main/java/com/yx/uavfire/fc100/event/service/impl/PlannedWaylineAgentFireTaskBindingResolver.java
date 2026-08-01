package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.dao.AgentFlightExecutionBindingMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFlightExecutionBindingEntity;
import com.yx.uavfire.fc100.event.service.AgentFireTaskBindingResolver;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Resolves only the immutable per-flight ledger; mutable planned_wayline state is never authoritative here. */
@Service
public class PlannedWaylineAgentFireTaskBindingResolver implements AgentFireTaskBindingResolver {
    static final long EXECUTION_CLOCK_TOLERANCE_MILLIS = 5_000L;
    private static final Set<String> ACTIVE = new HashSet<>(Arrays.asList("executing", "paused"));
    private final AgentFlightExecutionBindingMapper bindings;

    public PlannedWaylineAgentFireTaskBindingResolver(AgentFlightExecutionBindingMapper bindings) {
        this.bindings = bindings;
    }

    @Override
    public Binding resolveForInitialReport(String flightId, String droneSn, long eventTimestamp) {
        AgentFlightExecutionBindingEntity row = bindings.selectByFlightIdForUpdate(flightId);
        if (row == null || blank(row.getWorkspaceId()) || blank(row.getAssignedDroneSn()) ||
            !row.getAssignedDroneSn().equals(droneSn) || row.getExecutionStartedAt() == null ||
            eventTimestamp < row.getExecutionStartedAt() - EXECUTION_CLOCK_TOLERANCE_MILLIS) return null;
        if (row.getTerminalAt() != null) {
            if (eventTimestamp > row.getTerminalAt() + EXECUTION_CLOCK_TOLERANCE_MILLIS) return null;
        } else {
            String status = row.getExecutionStatus() == null ? "" : row.getExecutionStatus().toLowerCase(Locale.ROOT);
            if (!ACTIVE.contains(status)) return null;
        }
        return new Binding(row.getWorkspaceId(), row.getFlightId(), row.getAssignedDroneSn());
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
