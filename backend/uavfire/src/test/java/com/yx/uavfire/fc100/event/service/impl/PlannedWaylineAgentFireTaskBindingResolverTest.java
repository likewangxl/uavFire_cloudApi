package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.dao.AgentFlightExecutionBindingMapper;
import com.yx.uavfire.fc100.event.model.entity.AgentFlightExecutionBindingEntity;
import com.yx.uavfire.fc100.event.service.AgentFireTaskBindingResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannedWaylineAgentFireTaskBindingResolverTest {
    private final AgentFlightExecutionBindingMapper mapper = mock(AgentFlightExecutionBindingMapper.class);
    private final PlannedWaylineAgentFireTaskBindingResolver resolver =
        new PlannedWaylineAgentFireTaskBindingResolver(mapper);

    @Test void lockingLedgerReadReturnsImmutableWorkspaceAndDroneDuringExecution() {
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(binding("flight-A", "executing", 10_000L, null));
        AgentFireTaskBindingResolver.Binding result = resolver.resolveForInitialReport("flight-A", "drone-A", 12_000L);
        assertNotNull(result); assertEquals("workspace-A", result.getWorkspaceId());
        assertEquals("flight-A", result.getFlightId()); assertEquals("drone-A", result.getDroneSn());
    }

    @Test void finishedFlightSurvivesReprepareBecauseResolverNeverReadsMutablePlannedWayline() {
        AgentFlightExecutionBindingEntity flightA = binding("flight-A", "finished", 10_000L, 20_000L);
        AgentFlightExecutionBindingEntity flightB = binding("flight-B", "executing", 30_000L, null);
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(flightA);
        when(mapper.selectByFlightIdForUpdate("flight-B")).thenReturn(flightB);
        assertNotNull(resolver.resolveForInitialReport("flight-B", "drone-A", 31_000L));
        assertNotNull(resolver.resolveForInitialReport("flight-A", "drone-A", 15_000L));
        verify(mapper).selectByFlightIdForUpdate("flight-A");
    }

    @Test void rejectsPreExecutionStalePostTerminalMutationAndCrossIdentity() {
        AgentFlightExecutionBindingEntity row = binding("flight-A", "finished", 10_000L, 20_000L);
        row.setUpdateTime(9_999_999L); // later metadata writes cannot widen terminal_at
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(row);
        long tolerance = PlannedWaylineAgentFireTaskBindingResolver.EXECUTION_CLOCK_TOLERANCE_MILLIS;
        assertNull(resolver.resolveForInitialReport("flight-A", "drone-A", 10_000L - tolerance - 1));
        assertNull(resolver.resolveForInitialReport("flight-A", "drone-A", 20_000L + tolerance + 1));
        assertNull(resolver.resolveForInitialReport("flight-A", "other-drone", 15_000L));
        row.setWorkspaceId(""); assertNull(resolver.resolveForInitialReport("flight-A", "drone-A", 15_000L));
    }

    @Test void legacyBackfillAndPreparedRowsFailClosedWithoutImmutableExecutionStart() {
        AgentFlightExecutionBindingEntity legacy = binding("flight-A", "LEGACY_UNBOUND", null, null);
        when(mapper.selectByFlightIdForUpdate("flight-A")).thenReturn(legacy);
        assertNull(resolver.resolveForInitialReport("flight-A", "drone-A", 15_000L));
    }

    private AgentFlightExecutionBindingEntity binding(String flightId, String status, Long start, Long terminal) {
        AgentFlightExecutionBindingEntity row = new AgentFlightExecutionBindingEntity();
        row.setFlightId(flightId); row.setPlannedWaylinePk(7); row.setPlannedWaylineId("plan-A");
        row.setWorkspaceId("workspace-A"); row.setAssignedDroneSn("drone-A"); row.setPreparedAt(9_000L);
        row.setExecutionStartedAt(start); row.setTerminalAt(terminal); row.setExecutionStatus(status);
        row.setUpdateTime(terminal != null ? terminal : 10_000L); return row;
    }
}
