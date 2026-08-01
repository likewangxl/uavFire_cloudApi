package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.dao.AgentFlightExecutionBindingMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFlightExecutionBindingEntity;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentFlightExecutionBindingLedgerImplTest {
    private final AgentFlightExecutionBindingMapper mapper = mock(AgentFlightExecutionBindingMapper.class);
    private final AgentFlightExecutionBindingLedgerImpl ledger = new AgentFlightExecutionBindingLedgerImpl(mapper);

    @Test void prepareCreatesOneImmutableRowPerFlightSoReprepareDoesNotOverwriteFlightA() {
        PlannedWaylineEntity plan = task("flight-A", "drone-A");
        ledger.recordPrepared(plan, 1_000L);
        plan.setFlightId("flight-B");
        ledger.recordPrepared(plan, 2_000L);
        ArgumentCaptor<AgentFlightExecutionBindingEntity> rows = ArgumentCaptor.forClass(AgentFlightExecutionBindingEntity.class);
        verify(mapper, times(2)).insert(rows.capture());
        assertEquals("flight-A", rows.getAllValues().get(0).getFlightId());
        assertEquals("flight-B", rows.getAllValues().get(1).getFlightId());
        assertEquals("workspace-A", rows.getAllValues().get(0).getWorkspaceId());
    }

    @Test void executionFinalizesDroneAndStartOnceAndRejectsLaterCrossDroneMutation() {
        PlannedWaylineEntity task = task("flight-A", "drone-A");
        AgentFlightExecutionBindingEntity row = row("flight-A", "drone-A", null, null);
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(row);
        when(mapper.markStarted("flight-A", "drone-A", 10_000L)).thenReturn(1);
        ledger.recordExecutionStarted(task, 10_000L);
        verify(mapper).markStarted("flight-A", "drone-A", 10_000L);

        row.setExecutionStartedAt(10_000L); row.setAssignedDroneSn("drone-A");
        task.setDroneSn("drone-B"); task.setAircraftSn("drone-B");
        assertThrows(IllegalStateException.class, () -> ledger.recordExecutionStarted(task, 11_000L));
    }

    @Test void executionCannotReopenFinishedStoppedCanceledOrFailedFlight() {
        for (String status : new String[]{"finished", "stopped", "canceled", "failed"}) {
            reset(mapper);
            AgentFlightExecutionBindingEntity terminal = row("flight-A", "drone-A", 10_000L, 20_000L);
            terminal.setExecutionStatus(status);
            terminal.setTerminalStatus(status);
            when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(terminal);

            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ledger.recordExecutionStarted(task("flight-A", "drone-A"), 30_000L), status);
            assertTrue(error.getMessage().contains("new flight"), status);
            verify(mapper, never()).markStarted(anyString(), anyString(), anyLong());
        }
    }

    @Test void cancelBeforeFirstStartCannotBeExecutedAndZeroRowStartFailsClosed() {
        AgentFlightExecutionBindingEntity canceledBeforeStart = row("flight-A", "drone-A", null, 20_000L);
        canceledBeforeStart.setExecutionStatus("canceled");
        canceledBeforeStart.setTerminalStatus("canceled");
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(canceledBeforeStart);
        assertThrows(IllegalStateException.class,
            () -> ledger.recordExecutionStarted(task("flight-A", "drone-A"), 30_000L));

        reset(mapper);
        when(mapper.selectByFlightIdForUpdate("flight-A"))
            .thenReturn(row("flight-A", "drone-A", null, null));
        when(mapper.markStarted("flight-A", "drone-A", 30_000L)).thenReturn(0);
        assertThrows(IllegalStateException.class,
            () -> ledger.recordExecutionStarted(task("flight-A", "drone-A"), 30_000L));
    }

    @Test void firstTerminalTimestampIsImmutableAndLaterStatusMutationCannotWidenIt() {
        AgentFlightExecutionBindingEntity row = row("flight-A", "drone-A", 10_000L, null);
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(row);
        ledger.recordRuntimeStatus("flight-A", "drone-A", "finished", 20_000L);
        ledger.recordRuntimeStatus("flight-A", "drone-A", "failed", 99_000L);
        verify(mapper).markTerminal("flight-A", "finished", 20_000L);
        verify(mapper).markTerminal("flight-A", "failed", 99_000L);
        // SQL has terminal_at IS NULL; the second update is a no-op in production.
        org.apache.ibatis.annotations.Update sql;
        try {
            sql = AgentFlightExecutionBindingMapper.class
                .getMethod("markTerminal", String.class, String.class, long.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        assertTrue(String.join(" ", sql.value()).toLowerCase().contains("terminal_at is null"));
    }

    @Test void runtimeCrossDroneIsRejectedBeforeLedgerMutation() {
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(row("flight-A", "drone-A", 10_000L, null));
        assertThrows(IllegalStateException.class,
            () -> ledger.recordRuntimeStatus("flight-A", "drone-B", "executing", 12_000L));
        verify(mapper, never()).markActive(any(), any(), anyLong());
    }

    private PlannedWaylineEntity task(String flightId, String drone) {
        return PlannedWaylineEntity.builder().id(7).plannedWaylineId("plan-A").workspaceId("workspace-A")
            .flightId(flightId).droneSn(drone).aircraftSn(drone).build();
    }
    private AgentFlightExecutionBindingEntity row(String flightId, String drone, Long start, Long terminal) {
        AgentFlightExecutionBindingEntity row = new AgentFlightExecutionBindingEntity();
        row.setFlightId(flightId); row.setPlannedWaylinePk(7); row.setPlannedWaylineId("plan-A");
        row.setWorkspaceId("workspace-A"); row.setAssignedDroneSn(drone); row.setExecutionStartedAt(start);
        row.setTerminalAt(terminal); row.setExecutionStatus(terminal == null ? "executing" : "finished"); return row;
    }
}
