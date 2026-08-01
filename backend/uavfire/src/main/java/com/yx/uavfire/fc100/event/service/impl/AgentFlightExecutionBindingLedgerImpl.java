package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.dao.AgentFlightExecutionBindingMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFlightExecutionBindingEntity;
import com.yx.uavfire.fc100.event.service.AgentFlightExecutionBindingLedger;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class AgentFlightExecutionBindingLedgerImpl implements AgentFlightExecutionBindingLedger {
    private static final Set<String> ACTIVE = new HashSet<>(Arrays.asList("executing", "paused"));
    private static final Set<String> TERMINAL = new HashSet<>(Arrays.asList("finished", "stopped", "failed", "canceled"));
    private final AgentFlightExecutionBindingMapper bindings;

    public AgentFlightExecutionBindingLedgerImpl(AgentFlightExecutionBindingMapper bindings) { this.bindings = bindings; }

    @Override @Transactional
    public void recordPrepared(PlannedWaylineEntity task, long preparedAt) {
        requireTaskIdentity(task);
        AgentFlightExecutionBindingEntity row = new AgentFlightExecutionBindingEntity();
        row.setFlightId(task.getFlightId()); row.setPlannedWaylinePk(task.getId());
        row.setPlannedWaylineId(task.getPlannedWaylineId()); row.setWorkspaceId(task.getWorkspaceId());
        row.setAssignedDroneSn(assignedDrone(task)); row.setPreparedAt(preparedAt);
        row.setExecutionStatus("prepared"); row.setCreateTime(preparedAt); row.setUpdateTime(preparedAt);
        try { bindings.insert(row); }
        catch (DuplicateKeyException duplicate) {
            AgentFlightExecutionBindingEntity existing = bindings.selectByFlightIdForUpdate(task.getFlightId());
            if (!sameIdentity(existing, task)) throw duplicate;
        }
    }

    @Override @Transactional
    public void recordExecutionStarted(PlannedWaylineEntity task, long startedAt) {
        requireTaskIdentity(task);
        String drone = assignedDrone(task);
        if (drone == null) throw new IllegalStateException("flight execution has no authoritative drone binding");
        AgentFlightExecutionBindingEntity row = bindings.selectByFlightIdForUpdate(task.getFlightId());
        if (!sameIdentity(row, task)) throw new IllegalStateException("flight execution binding identity mismatch");
        if (row.getTerminalAt() != null)
            throw new IllegalStateException("terminal flight cannot execute again; prepare a new flight first");
        if (row.getExecutionStartedAt() != null && !drone.equals(row.getAssignedDroneSn()))
            throw new IllegalStateException("flight execution drone binding is immutable");
        if (bindings.markStarted(task.getFlightId(), drone, startedAt) != 1)
            throw new IllegalStateException("flight execution binding could not start; prepare a new flight first");
    }

    @Override @Transactional
    public void recordRuntimeStatus(String flightId, String droneSn, String status, long observedAt) {
        AgentFlightExecutionBindingEntity row = bindings.selectByFlightIdForUpdate(flightId);
        if (row == null) return; // pre-migration/unknown flights fail closed at report binding
        if (row.getAssignedDroneSn() != null && droneSn != null && !row.getAssignedDroneSn().equals(droneSn))
            throw new IllegalStateException("runtime drone differs from immutable flight binding");
        String normalized = status == null ? null : status.toLowerCase(Locale.ROOT);
        if (TERMINAL.contains(normalized)) bindings.markTerminal(flightId, normalized, observedAt);
        else if (ACTIVE.contains(normalized)) {
            if (row.getExecutionStartedAt() == null) {
                if (droneSn == null) return;
                bindings.markStarted(flightId, droneSn, observedAt);
            }
            bindings.markActive(flightId, normalized, observedAt);
        }
    }

    private void requireTaskIdentity(PlannedWaylineEntity task) {
        if (task == null || blank(task.getFlightId()) || blank(task.getWorkspaceId()) ||
            blank(task.getPlannedWaylineId()) || task.getId() == null)
            throw new IllegalStateException("planned wayline cannot create an immutable flight binding");
    }
    private boolean sameIdentity(AgentFlightExecutionBindingEntity row, PlannedWaylineEntity task) {
        return row != null && eq(row.getPlannedWaylinePk(), task.getId()) &&
            eq(row.getPlannedWaylineId(), task.getPlannedWaylineId()) && eq(row.getWorkspaceId(), task.getWorkspaceId());
    }
    private String assignedDrone(PlannedWaylineEntity task) {
        String drone = trim(task.getDroneSn()), aircraft = trim(task.getAircraftSn());
        if (drone != null && aircraft != null && !drone.equals(aircraft)) return null;
        return drone != null ? drone : aircraft;
    }
    private boolean blank(String v) { return trim(v) == null; }
    private String trim(String v) { return v == null || v.trim().isEmpty() ? null : v.trim(); }
    private boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
}
