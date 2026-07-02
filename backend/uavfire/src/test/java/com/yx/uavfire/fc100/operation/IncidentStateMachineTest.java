package com.yx.uavfire.fc100.operation;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentLogMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentLogEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.service.impl.IncidentStateMachineImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentStateMachineTest {

    @Test
    void acceptsEveryDefinedLegalTransitionAndWritesAuditLog() {
        Object[][] legal = new Object[][] {
            {OperationIncidentStatus.CANDIDATE, OperationIncidentEvent.CONFIRM, OperationIncidentStatus.CONFIRMED},
            {OperationIncidentStatus.CANDIDATE, OperationIncidentEvent.MARK_FALSE_ALARM, OperationIncidentStatus.FALSE_ALARM},
            {OperationIncidentStatus.CONFIRMED, OperationIncidentEvent.DISPATCH, OperationIncidentStatus.DISPATCHING},
            {OperationIncidentStatus.CONFIRMED, OperationIncidentEvent.MARK_FALSE_ALARM, OperationIncidentStatus.FALSE_ALARM},
            {OperationIncidentStatus.CONFIRMED, OperationIncidentEvent.ABORT, OperationIncidentStatus.ABORTED},
            {OperationIncidentStatus.DISPATCHING, OperationIncidentEvent.RESPOND, OperationIncidentStatus.RESPONDING},
            {OperationIncidentStatus.DISPATCHING, OperationIncidentEvent.ABORT, OperationIncidentStatus.ABORTED},
            {OperationIncidentStatus.RESPONDING, OperationIncidentEvent.START_RECHECK, OperationIncidentStatus.RECHECKING},
            {OperationIncidentStatus.RESPONDING, OperationIncidentEvent.RESOLVE, OperationIncidentStatus.RESOLVED},
            {OperationIncidentStatus.RESPONDING, OperationIncidentEvent.ABORT, OperationIncidentStatus.ABORTED},
            {OperationIncidentStatus.RECHECKING, OperationIncidentEvent.CONTINUE_RESPONSE, OperationIncidentStatus.RESPONDING},
            {OperationIncidentStatus.RECHECKING, OperationIncidentEvent.RESOLVE, OperationIncidentStatus.RESOLVED},
            {OperationIncidentStatus.RECHECKING, OperationIncidentEvent.ABORT, OperationIncidentStatus.ABORTED},
            {OperationIncidentStatus.RESOLVED, OperationIncidentEvent.ARCHIVE, OperationIncidentStatus.ARCHIVED},
            {OperationIncidentStatus.FALSE_ALARM, OperationIncidentEvent.ARCHIVE, OperationIncidentStatus.ARCHIVED},
            {OperationIncidentStatus.ABORTED, OperationIncidentEvent.ARCHIVE, OperationIncidentStatus.ARCHIVED}
        };

        for (Object[] row : legal) {
            OperationIncidentStatus from = (OperationIncidentStatus) row[0];
            OperationIncidentEvent event = (OperationIncidentEvent) row[1];
            OperationIncidentStatus to = (OperationIncidentStatus) row[2];
            OperationIncidentMapper incidentMapper = mock(OperationIncidentMapper.class);
            OperationIncidentLogMapper logMapper = mock(OperationIncidentLogMapper.class);
            IncidentStateMachine stateMachine = new IncidentStateMachineImpl(incidentMapper, logMapper, fixedClock());
            OperationIncidentEntity incident = incident(100L, from);
            when(incidentMapper.selectById(100L)).thenReturn(incident);
            when(incidentMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

            OperationIncidentEntity result = stateMachine.transit(IncidentTransitCommand.builder()
                .incidentId(100L)
                .event(event)
                .operatorId("operator-1")
                .requestId("REQ-1")
                .idempotencyKey("IDEMP-1")
                .remark("ok")
                .build());

            assertEquals(to.name(), result.getStatus());
            verify(logMapper).insert(argThat(log ->
                event.name().equals(log.getAction())
                    && from.name().equals(log.getFromStatus())
                    && to.name().equals(log.getToStatus())
                    && "operator-1".equals(log.getOperatorId())
                    && Long.valueOf(1770000000000L).equals(log.getCreateTime())));
        }

        IncidentStateMachine stateMachine = new IncidentStateMachineImpl(
            mock(OperationIncidentMapper.class), mock(OperationIncidentLogMapper.class), fixedClock());
        Map<OperationIncidentStatus, Set<OperationIncidentEvent>> expectedAllowed = Map.of(
            OperationIncidentStatus.CANDIDATE, Set.of(OperationIncidentEvent.CONFIRM, OperationIncidentEvent.MARK_FALSE_ALARM),
            OperationIncidentStatus.CONFIRMED, Set.of(OperationIncidentEvent.DISPATCH, OperationIncidentEvent.MARK_FALSE_ALARM, OperationIncidentEvent.ABORT),
            OperationIncidentStatus.DISPATCHING, Set.of(OperationIncidentEvent.RESPOND, OperationIncidentEvent.ABORT),
            OperationIncidentStatus.RESPONDING, Set.of(OperationIncidentEvent.START_RECHECK, OperationIncidentEvent.RESOLVE, OperationIncidentEvent.ABORT),
            OperationIncidentStatus.RECHECKING, Set.of(OperationIncidentEvent.CONTINUE_RESPONSE, OperationIncidentEvent.RESOLVE, OperationIncidentEvent.ABORT),
            OperationIncidentStatus.RESOLVED, Set.of(OperationIncidentEvent.ARCHIVE),
            OperationIncidentStatus.FALSE_ALARM, Set.of(OperationIncidentEvent.ARCHIVE),
            OperationIncidentStatus.ABORTED, Set.of(OperationIncidentEvent.ARCHIVE),
            OperationIncidentStatus.ARCHIVED, Set.of()
        );
        for (OperationIncidentStatus status : OperationIncidentStatus.values()) {
            assertEquals(expectedAllowed.get(status), stateMachine.allowedEvents(status), status.name());
        }
    }

    @Test
    void rejectsIllegalTransitionsAndWritesDeniedAuditLog() {
        Object[][] illegal = new Object[][] {
            {OperationIncidentStatus.CONFIRMED, OperationIncidentEvent.ARCHIVE},
            {OperationIncidentStatus.ARCHIVED, OperationIncidentEvent.ABORT},
            {OperationIncidentStatus.RESPONDING, OperationIncidentEvent.DISPATCH},
            {OperationIncidentStatus.FALSE_ALARM, OperationIncidentEvent.DISPATCH},
            {OperationIncidentStatus.RECHECKING, OperationIncidentEvent.MARK_FALSE_ALARM},
            {OperationIncidentStatus.ABORTED, OperationIncidentEvent.ABORT},
            {OperationIncidentStatus.ABORTED, OperationIncidentEvent.MARK_FALSE_ALARM},
            {OperationIncidentStatus.CANDIDATE, OperationIncidentEvent.ABORT}
        };

        for (Object[] row : illegal) {
            OperationIncidentStatus from = (OperationIncidentStatus) row[0];
            OperationIncidentEvent event = (OperationIncidentEvent) row[1];
            OperationIncidentMapper incidentMapper = mock(OperationIncidentMapper.class);
            OperationIncidentLogMapper logMapper = mock(OperationIncidentLogMapper.class);
            IncidentStateMachine stateMachine = new IncidentStateMachineImpl(incidentMapper, logMapper, fixedClock());
            when(incidentMapper.selectById(100L)).thenReturn(incident(100L, from));

            Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
                () -> stateMachine.transit(IncidentTransitCommand.builder()
                    .incidentId(100L)
                    .event(event)
                    .operatorId("operator-1")
                    .remark("denied")
                    .build()));

            assertEquals(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN, ex.getErrorCode());
            verify(logMapper).insert(argThat(log ->
                event.name().equals(log.getAction())
                    && from.name().equals(log.getFromStatus())
                    && log.getToStatus() == null
                    && log.getRemark().contains("denied")));
        }
    }

    private OperationIncidentEntity incident(Long id, OperationIncidentStatus status) {
        OperationIncidentEntity entity = new OperationIncidentEntity();
        entity.setId(id);
        entity.setIncidentNo("INCIDENT-1");
        entity.setStatus(status.name());
        return entity;
    }

    private Clock fixedClock() {
        return () -> 1770000000000L;
    }
}
