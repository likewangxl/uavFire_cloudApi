package com.yx.uavfire.fc100.operation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.operation.dao.OperationAssignmentMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentLogMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationTimelineItem;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentLogEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentRole;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentStatus;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.model.param.AssignOperationResourceParam;
import com.yx.uavfire.fc100.operation.model.param.CreateOperationIncidentParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.lease.ResourceLeaseService;
import com.yx.uavfire.fc100.operation.preflight.PreflightBlockedException;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;
import com.yx.uavfire.fc100.operation.service.IncidentNoGenerator;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.service.PreflightGate;
import com.yx.uavfire.fc100.operation.service.impl.IncidentStateMachineImpl;
import com.yx.uavfire.fc100.operation.service.impl.OperationIncidentServiceImpl;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationIncidentServiceTest {

    @Test
    void createFromConfirmedFireEventCreatesIncidentAndLinksFireEventAndDraftMission() {
        Fixture f = fixture();
        FireEventEntity fireEvent = confirmedFireEvent();
        when(f.fireEventMapper.selectById(10L)).thenReturn(fireEvent);
        when(f.incidentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(f.noGenerator.next()).thenReturn("INCIDENT-20260702-0001");
        when(f.incidentMapper.insert(any(OperationIncidentEntity.class))).thenAnswer(inv -> {
            OperationIncidentEntity entity = inv.getArgument(0);
            entity.setId(501L);
            return 1;
        });

        CreateOperationIncidentParam param = new CreateOperationIncidentParam();
        param.setFireEventId(10L);
        param.setCreatedBy("creator-1");
        param.setConfirmedBy("commander-1");

        OperationIncidentDTO result = f.service.create(param);

        assertEquals(501L, result.getId());
        assertEquals("INCIDENT-20260702-0001", result.getIncidentNo());
        assertEquals(OperationIncidentStatus.CONFIRMED.name(), result.getStatus());
        assertEquals("HIGH", result.getLevel());
        assertEquals(22.123456, result.getCenterLat());
        assertEquals(113.654321, result.getCenterLng());
        assertEquals(8.5, result.getRiskRadiusM());
        verify(f.fireEventMapper).updateById(argThat(updated ->
            Long.valueOf(501L).equals(updated.getLinkedIncidentId())
                && "CONFIRMED".equals(updated.getConfirmedStatus())));
        verify(f.fireMissionMapper).update(any(), any(UpdateWrapper.class));
        verify(f.logMapper).insert(argThat(log ->
            "CREATE".equals(log.getAction())
                && OperationIncidentStatus.CONFIRMED.name().equals(log.getToStatus())
                && "creator-1".equals(log.getOperatorId())));
    }

    @Test
    void createRejectsUnconfirmedFireEvent() {
        Fixture f = fixture();
        FireEventEntity fireEvent = confirmedFireEvent();
        fireEvent.setConfirmedStatus("PENDING");
        when(f.fireEventMapper.selectById(10L)).thenReturn(fireEvent);

        CreateOperationIncidentParam param = new CreateOperationIncidentParam();
        param.setFireEventId(10L);
        param.setCreatedBy("creator-1");

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> f.service.create(param));

        assertEquals(Fc100ErrorCode.INVALID_PARAM, ex.getErrorCode());
        verify(f.incidentMapper, never()).insert(any(OperationIncidentEntity.class));
    }

    @Test
    void createRejectsDuplicateActiveIncidentForSameFireEvent() {
        Fixture f = fixture();
        when(f.fireEventMapper.selectById(10L)).thenReturn(confirmedFireEvent());
        OperationIncidentEntity existing = incident(501L, OperationIncidentStatus.RESPONDING);
        when(f.incidentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        CreateOperationIncidentParam param = new CreateOperationIncidentParam();
        param.setFireEventId(10L);
        param.setCreatedBy("creator-1");

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> f.service.create(param));

        assertEquals(Fc100ErrorCode.DUPLICATE_FROM_EVENT, ex.getErrorCode());
        verify(f.incidentMapper, never()).insert(any(OperationIncidentEntity.class));
    }

    @Test
    void assignDeliveryRejectsSecondActivePrimary() {
        Fixture f = fixture();
        when(f.incidentMapper.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.CONFIRMED));
        OperationAssignmentEntity existing = assignment("FC100-001", OperationAssignmentRole.DELIVERY_PRIMARY);
        when(f.assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        AssignOperationResourceParam param = new AssignOperationResourceParam();
        param.setResourceSn("FC100-002");
        param.setRole(OperationAssignmentRole.DELIVERY_PRIMARY.name());
        param.setOperatorId("operator-1");

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> f.service.assignDelivery(501L, param));

        assertEquals(Fc100ErrorCode.INVALID_PARAM, ex.getErrorCode());
        verify(f.assignmentMapper, never()).insert(any(OperationAssignmentEntity.class));
    }

    @Test
    void assignDeliveryFailsWhenResourceLeaseIsAlreadyActiveElsewhere() {
        Fixture f = fixture();
        when(f.incidentMapper.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.CONFIRMED));
        when(f.assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(f.leaseService.acquire(any(), any(), any(), any(), any()))
            .thenThrow(new Fc100BusinessException(Fc100ErrorCode.RESOURCE_CONFLICT,
                "resource already leased: FC100-SN-001"));

        AssignOperationResourceParam param = new AssignOperationResourceParam();
        param.setResourceSn("FC100-SN-001");
        param.setRole(OperationAssignmentRole.DELIVERY_PRIMARY.name());
        param.setOperatorId("operator-1");

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> f.service.assignDelivery(501L, param));

        assertEquals(Fc100ErrorCode.RESOURCE_CONFLICT, ex.getErrorCode());
        verify(f.assignmentMapper, never()).insert(any(OperationAssignmentEntity.class));
    }

    @Test
    void dispatchRequiresActiveDeliveryPrimaryAssignmentBeforeStateChanges() {
        Fixture f = fixture();
        HttpServletRequest request = request();
        when(f.incidentMapper.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.CONFIRMED));
        when(f.assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> f.service.dispatch(501L, param, request));

        assertEquals(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN, ex.getErrorCode());
        verify(f.stateMachine, never()).transit(any(IncidentTransitCommand.class));
    }

    @Test
    void dispatchRejectsWhenPreflightBlocksAndDoesNotChangeIncidentStatus() {
        Fixture f = fixture();
        HttpServletRequest request = request();
        OperationIncidentEntity incident = incident(501L, OperationIncidentStatus.CONFIRMED);
        OperationAssignmentEntity primary = assignment("FC100-SN-001", OperationAssignmentRole.DELIVERY_PRIMARY);
        when(f.incidentMapper.selectById(501L)).thenReturn(incident);
        when(f.assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(primary));
        PreflightResult blocked = PreflightResult.from(501L, "commander-1",
            List.of(RuleCheckResult.block("R01", "火情已人工确认", "not confirmed")), fixedClock().now());
        when(f.preflightGate.check(incident, primary, "commander-1"))
            .thenThrow(new PreflightBlockedException(blocked));

        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");

        PreflightBlockedException ex = assertThrows(PreflightBlockedException.class,
            () -> f.service.dispatch(501L, param, request));

        assertEquals(1, ex.getResult().blockingItems().size());
        assertEquals(OperationIncidentStatus.CONFIRMED.name(), incident.getStatus());
        verify(f.stateMachine, never()).transit(any(IncidentTransitCommand.class));
    }

    @Test
    void dispatchPassesPreflightAndMovesIncidentToDispatchingOnly() {
        Fixture f = fixture();
        HttpServletRequest request = request();
        OperationIncidentEntity confirmed = incident(501L, OperationIncidentStatus.CONFIRMED);
        OperationIncidentEntity dispatching = incident(501L, OperationIncidentStatus.DISPATCHING);
        OperationAssignmentEntity primary = assignment("FC100-SN-001", OperationAssignmentRole.DELIVERY_PRIMARY);
        when(f.incidentMapper.selectById(501L)).thenReturn(confirmed);
        when(f.assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(primary));
        when(f.preflightGate.check(confirmed, primary, "commander-1"))
            .thenReturn(PreflightResult.from(501L, "commander-1", List.of(), fixedClock().now()));
        when(f.stateMachine.transit(any(IncidentTransitCommand.class))).thenReturn(dispatching);

        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");

        OperationIncidentEntity result = f.service.dispatch(501L, param, request);

        assertEquals(OperationIncidentStatus.DISPATCHING.name(), result.getStatus());
        verify(f.stateMachine).transit(argThat(cmd ->
            cmd.getEvent() == OperationIncidentEvent.DISPATCH
                && OperationIncidentStatus.CONFIRMED.equals(cmd.getExpectedFrom())));
        verify(f.stateMachine, never()).transit(argThat(cmd -> cmd.getEvent() == OperationIncidentEvent.RESPOND));
    }

    @Test
    void abortAndCloseUseStateMachineWithOperatorReasonAndRequestMetadata() {
        Fixture f = fixture();
        HttpServletRequest request = request();
        OperationIncidentEntity aborted = incident(501L, OperationIncidentStatus.ABORTED);
        OperationIncidentEntity archived = incident(501L, OperationIncidentStatus.ARCHIVED);
        when(f.stateMachine.transit(any(IncidentTransitCommand.class))).thenReturn(aborted, archived);
        when(f.incidentMapper.selectById(501L)).thenReturn(aborted);

        OperationActionParam abortParam = new OperationActionParam();
        abortParam.setOperatorId("commander-1");
        abortParam.setReason("weather unsafe");
        OperationIncidentEntity abortResult = f.service.abort(501L, abortParam, request);

        OperationActionParam closeParam = new OperationActionParam();
        closeParam.setOperatorId("commander-2");
        closeParam.setReason("filed");
        OperationIncidentEntity closeResult = f.service.close(501L, closeParam, request);

        assertEquals(OperationIncidentStatus.ABORTED.name(), abortResult.getStatus());
        assertEquals(OperationIncidentStatus.ARCHIVED.name(), closeResult.getStatus());
        verify(f.stateMachine).transit(argThat(cmd ->
            cmd.getEvent() == OperationIncidentEvent.ABORT
                && "commander-1".equals(cmd.getOperatorId())
                && "weather unsafe".equals(cmd.getRemark())
                && "REQ-1".equals(cmd.getRequestId())
                && "IDEMP-1".equals(cmd.getIdempotencyKey())));
        verify(f.stateMachine).transit(argThat(cmd ->
            cmd.getEvent() == OperationIncidentEvent.ARCHIVE
                && "commander-2".equals(cmd.getOperatorId())
                && "filed".equals(cmd.getRemark())));
    }

    @Test
    void markFalseAlarmMovesConfirmedIncidentToFalseAlarmAndWritesAuditLog() {
        Fixture f = fixtureWithRealStateMachine();
        HttpServletRequest request = request();
        when(f.incidentMapper.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.CONFIRMED));
        when(f.incidentMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");
        param.setReason("thermal source misread");

        OperationIncidentEntity result = f.service.markFalseAlarm(501L, param, request);

        assertEquals(OperationIncidentStatus.FALSE_ALARM.name(), result.getStatus());
        verify(f.logMapper).insert(argThat(log ->
            OperationIncidentEvent.MARK_FALSE_ALARM.name().equals(log.getAction())
                && OperationIncidentStatus.CONFIRMED.name().equals(log.getFromStatus())
                && OperationIncidentStatus.FALSE_ALARM.name().equals(log.getToStatus())
                && "commander-1".equals(log.getOperatorId())
                && "thermal source misread".equals(log.getRemark())
                && "REQ-1".equals(log.getRequestId())
                && "IDEMP-1".equals(log.getIdempotencyKey())));
    }

    @Test
    void abortMovesRespondingIncidentToAbortedAndWritesAuditLog() {
        Fixture f = fixtureWithRealStateMachine();
        HttpServletRequest request = request();
        when(f.incidentMapper.selectById(501L)).thenReturn(incident(501L, OperationIncidentStatus.RESPONDING));
        when(f.incidentMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");
        param.setReason("weather unsafe");

        OperationIncidentEntity result = f.service.abort(501L, param, request);

        assertEquals(OperationIncidentStatus.ABORTED.name(), result.getStatus());
        verify(f.logMapper).insert(argThat(log ->
            OperationIncidentEvent.ABORT.name().equals(log.getAction())
                && OperationIncidentStatus.RESPONDING.name().equals(log.getFromStatus())
                && OperationIncidentStatus.ABORTED.name().equals(log.getToStatus())
                && "commander-1".equals(log.getOperatorId())
                && "weather unsafe".equals(log.getRemark())
                && "REQ-1".equals(log.getRequestId())
                && "IDEMP-1".equals(log.getIdempotencyKey())));
    }

    @Test
    void abortRequiresReasonBeforeStateTransition() {
        Fixture f = fixture();
        HttpServletRequest request = request();
        OperationActionParam param = new OperationActionParam();
        param.setOperatorId("commander-1");
        param.setReason(" ");

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> f.service.abort(501L, param, request));

        assertEquals(Fc100ErrorCode.INVALID_PARAM, ex.getErrorCode());
        verify(f.stateMachine, never()).transit(any(IncidentTransitCommand.class));
    }

    @Test
    void timelineAggregatesStateLogsAndAssignmentsInAscendingTimeOrder() {
        Fixture f = fixture();
        OperationIncidentLogEntity created = log("CREATE", 1000L);
        OperationIncidentLogEntity dispatched = log("DISPATCH", 3000L);
        OperationAssignmentEntity assignment = assignment("FC100-001", OperationAssignmentRole.DELIVERY_PRIMARY);
        assignment.setAssignedAt(2000L);
        when(f.logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(dispatched, created));
        when(f.assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(assignment));

        List<OperationTimelineItem> timeline = f.service.timeline(501L);

        assertEquals(3, timeline.size());
        assertEquals("CREATE", timeline.get(0).getAction());
        assertEquals("ASSIGN_DELIVERY_PRIMARY", timeline.get(1).getAction());
        assertEquals("DISPATCH", timeline.get(2).getAction());
        assertTrue(timeline.get(1).getDescription().contains("FC100-001"));
    }

    private Fixture fixture() {
        OperationIncidentMapper incidentMapper = mock(OperationIncidentMapper.class);
        OperationAssignmentMapper assignmentMapper = mock(OperationAssignmentMapper.class);
        OperationIncidentLogMapper logMapper = mock(OperationIncidentLogMapper.class);
        FireEventMapper fireEventMapper = mock(FireEventMapper.class);
        FireMissionMapper fireMissionMapper = mock(FireMissionMapper.class);
        IncidentStateMachine stateMachine = mock(IncidentStateMachine.class);
        IncidentNoGenerator noGenerator = mock(IncidentNoGenerator.class);
        PreflightGate preflightGate = mock(PreflightGate.class);
        ResourceLeaseService leaseService = mock(ResourceLeaseService.class);
        OperationIncidentServiceImpl service = new OperationIncidentServiceImpl(
            incidentMapper, assignmentMapper, logMapper, fireEventMapper, fireMissionMapper,
            stateMachine, noGenerator, preflightGate, leaseService, fixedClock());
        return new Fixture(incidentMapper, assignmentMapper, logMapper, fireEventMapper, fireMissionMapper,
            stateMachine, noGenerator, preflightGate, leaseService, service);
    }

    private Fixture fixtureWithRealStateMachine() {
        OperationIncidentMapper incidentMapper = mock(OperationIncidentMapper.class);
        OperationAssignmentMapper assignmentMapper = mock(OperationAssignmentMapper.class);
        OperationIncidentLogMapper logMapper = mock(OperationIncidentLogMapper.class);
        FireEventMapper fireEventMapper = mock(FireEventMapper.class);
        FireMissionMapper fireMissionMapper = mock(FireMissionMapper.class);
        IncidentStateMachine stateMachine = new IncidentStateMachineImpl(incidentMapper, logMapper, fixedClock());
        IncidentNoGenerator noGenerator = mock(IncidentNoGenerator.class);
        PreflightGate preflightGate = mock(PreflightGate.class);
        ResourceLeaseService leaseService = mock(ResourceLeaseService.class);
        OperationIncidentServiceImpl service = new OperationIncidentServiceImpl(
            incidentMapper, assignmentMapper, logMapper, fireEventMapper, fireMissionMapper,
            stateMachine, noGenerator, preflightGate, leaseService, fixedClock());
        return new Fixture(incidentMapper, assignmentMapper, logMapper, fireEventMapper, fireMissionMapper,
            stateMachine, noGenerator, preflightGate, leaseService, service);
    }

    private FireEventEntity confirmedFireEvent() {
        FireEventEntity event = new FireEventEntity();
        event.setId(10L);
        event.setFireLevel("HIGH");
        event.setLat(22.123456);
        event.setLng(113.654321);
        event.setGeoErrorRadiusM(8.5);
        event.setConfidence(new BigDecimal("0.96"));
        event.setConfirmedStatus("CONFIRMED");
        return event;
    }

    private OperationIncidentEntity incident(Long id, OperationIncidentStatus status) {
        OperationIncidentEntity incident = new OperationIncidentEntity();
        incident.setId(id);
        incident.setIncidentNo("INCIDENT-20260702-0001");
        incident.setFireEventId(10L);
        incident.setStatus(status.name());
        incident.setLevel("HIGH");
        incident.setCenterLat(22.123456);
        incident.setCenterLng(113.654321);
        incident.setRiskRadiusM(8.5);
        incident.setCreateTime(1770000000000L);
        incident.setUpdateTime(1770000000000L);
        return incident;
    }

    private OperationAssignmentEntity assignment(String resourceSn, OperationAssignmentRole role) {
        OperationAssignmentEntity assignment = new OperationAssignmentEntity();
        assignment.setId(701L);
        assignment.setIncidentId(501L);
        assignment.setResourceSn(resourceSn);
        assignment.setRole(role.name());
        assignment.setStatus(OperationAssignmentStatus.ACTIVE.name());
        assignment.setAssignedAt(1770000001000L);
        return assignment;
    }

    private OperationIncidentLogEntity log(String action, long createTime) {
        OperationIncidentLogEntity log = new OperationIncidentLogEntity();
        log.setIncidentId(501L);
        log.setAction(action);
        log.setFromStatus(OperationIncidentStatus.CONFIRMED.name());
        log.setToStatus(OperationIncidentStatus.DISPATCHING.name());
        log.setOperatorId("operator-1");
        log.setCreateTime(createTime);
        return log;
    }

    private Clock fixedClock() {
        return () -> 1770000000000L;
    }

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Request-Id")).thenReturn("REQ-1");
        when(request.getHeader("X-Idempotency-Key")).thenReturn("IDEMP-1");
        return request;
    }

    private static class Fixture {
        private final OperationIncidentMapper incidentMapper;
        private final OperationAssignmentMapper assignmentMapper;
        private final OperationIncidentLogMapper logMapper;
        private final FireEventMapper fireEventMapper;
        private final FireMissionMapper fireMissionMapper;
        private final IncidentStateMachine stateMachine;
        private final IncidentNoGenerator noGenerator;
        private final PreflightGate preflightGate;
        private final ResourceLeaseService leaseService;
        private final OperationIncidentServiceImpl service;

        private Fixture(OperationIncidentMapper incidentMapper,
                        OperationAssignmentMapper assignmentMapper,
                        OperationIncidentLogMapper logMapper,
                        FireEventMapper fireEventMapper,
                        FireMissionMapper fireMissionMapper,
                        IncidentStateMachine stateMachine,
                        IncidentNoGenerator noGenerator,
                        PreflightGate preflightGate,
                        ResourceLeaseService leaseService,
                        OperationIncidentServiceImpl service) {
            this.incidentMapper = incidentMapper;
            this.assignmentMapper = assignmentMapper;
            this.logMapper = logMapper;
            this.fireEventMapper = fireEventMapper;
            this.fireMissionMapper = fireMissionMapper;
            this.stateMachine = stateMachine;
            this.noGenerator = noGenerator;
            this.preflightGate = preflightGate;
            this.leaseService = leaseService;
            this.service = service;
        }
    }
}
