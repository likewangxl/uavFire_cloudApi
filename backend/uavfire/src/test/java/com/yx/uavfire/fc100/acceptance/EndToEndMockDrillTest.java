package com.yx.uavfire.fc100.acceptance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.controller.DeliveryController;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDecisionResult;
import com.yx.uavfire.fc100.event.model.dto.FireEventRecheckResultDTO;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.param.FireEventActionParam;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.model.param.FireEventRecheckResultParam;
import com.yx.uavfire.fc100.event.service.impl.Fc100ThermalProperties;
import com.yx.uavfire.fc100.event.service.impl.FireEventServiceImpl;
import com.yx.uavfire.fc100.mission.dao.FireMissionLogMapper;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionLogEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.enums.ReleaseExecutionMode;
import com.yx.uavfire.fc100.mission.model.enums.ReleasePolicy;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.mission.service.impl.MissionStateMachineImpl;
import com.yx.uavfire.fc100.operation.command.CommandQueueProperties;
import com.yx.uavfire.fc100.operation.command.impl.CommandQueueServiceImpl;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationComplianceRecordMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationFlightApplicationRecordMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationQualificationMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationTakeoffConfirmationMapper;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationComplianceRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.dao.OperationAssignmentMapper;
import com.yx.uavfire.fc100.operation.dao.OperationCommandEventMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentLogMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.dao.OperationResourceLeaseMapper;
import com.yx.uavfire.fc100.operation.lease.ResourceLeaseService;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentLogEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentRole;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentStatus;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.model.param.AssignOperationResourceParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.preflight.DeliveryHubHealthProbe;
import com.yx.uavfire.fc100.operation.preflight.PreflightBlockedException;
import com.yx.uavfire.fc100.operation.preflight.PreflightContextFactory;
import com.yx.uavfire.fc100.operation.preflight.PreflightExecutionService;
import com.yx.uavfire.fc100.operation.preflight.PreflightProperties;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;
import com.yx.uavfire.fc100.operation.preflight.PreflightRuleEngine;
import com.yx.uavfire.fc100.operation.preflight.RealPreflightGate;
import com.yx.uavfire.fc100.operation.preflight.boundary.BoundaryCheckResult;
import com.yx.uavfire.fc100.operation.preflight.boundary.UomBoundaryChecker;
import com.yx.uavfire.fc100.operation.service.IncidentNoGenerator;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.service.impl.IncidentStateMachineImpl;
import com.yx.uavfire.fc100.operation.service.impl.OperationIncidentServiceImpl;
import com.yx.uavfire.fc100.payload.dao.PayloadEventMapper;
import com.yx.uavfire.fc100.payload.model.entity.PayloadEventEntity;
import com.yx.uavfire.fc100.payload.model.param.PayloadConfirmReleaseParam;
import com.yx.uavfire.fc100.payload.service.PayloadReleasePolicyService;
import com.yx.uavfire.fc100.payload.service.impl.PayloadServiceImpl;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EndToEndMockDrillTest {

    @Test
    void normalMockDrillCoversCandidateDispatchReleaseRecheckAndArchive() throws Exception {
        DrillFixture f = new DrillFixture();

        long startedAt = f.clock.now();
        FireEventCreateResponse created = f.fireEventService.create(f.thermalAlarm("S8-EVT-001", 560.0, 20.0));
        assertEquals("CANDIDATE", created.getStatus());
        assertFalse(Boolean.TRUE.equals(created.getMissionCreated()));
        assertTrue(f.store.missions.isEmpty(), "candidate event must not create a mission before manual confirmation");
        assertTrue(f.clock.now() - startedAt <= 5000L, "mock candidate chain latency should be within 5s");

        FireEventDecisionResult confirmed = f.fireEventService.confirm("S8-EVT-001", f.fireAction("commander-1"), f.request("REQ-CONFIRM"));
        assertEquals(OperationIncidentStatus.CONFIRMED.name(), confirmed.getIncident().getStatus());
        FireMissionEntity mission = f.onlyMission();
        assertEquals(FireMissionStatus.CREATED.name(), mission.getStatus());
        assertEquals(confirmed.getIncident().getId(), mission.getIncidentId());

        OperationAssignmentEntity assignment = f.incidentService.assignDelivery(confirmed.getIncident().getId(),
            f.assignDelivery("FC100-S8-001"));
        assertEquals(OperationAssignmentStatus.ACTIVE.name(), assignment.getStatus());
        assertEquals(1L, assignment.getLeaseId());

        PreflightBlockedException blocked = assertThrows(PreflightBlockedException.class,
            () -> f.incidentService.dispatch(confirmed.getIncident().getId(),
                f.operationAction("commander-1", null), f.request("REQ-DISPATCH-BLOCK")));
        Set<String> blockingIds = blocked.getResult().blockingItems().stream()
            .map(item -> item.getRuleId())
            .collect(Collectors.toSet());
        assertTrue(blockingIds.contains("R02"), blockingIds::toString);
        assertTrue(blockingIds.contains("R15"), blockingIds::toString);

        f.addComplianceRecords(confirmed.getIncident().getId());
        f.prepareMissionForDelivery(mission);
        PreflightResult passed = assertDoesNotThrow(() -> f.preflightService.run(
            f.store.incidents.get(confirmed.getIncident().getId()), assignment, "commander-1"));
        assertTrue(passed.blockingItems().isEmpty());

        OperationIncidentEntity dispatching = f.incidentService.dispatch(confirmed.getIncident().getId(),
            f.operationAction("commander-1", null), f.request("REQ-DISPATCH-PASS"));
        assertEquals(OperationIncidentStatus.DISPATCHING.name(), dispatching.getStatus());

        ApiResult<DeliveryTaskRef> taskRef = f.deliveryController.createTask(mission.getMissionNo(),
            f.createTask("commander-1"), f.request("REQ-CREATE-TASK"));
        assertEquals("TASK-S8-001", taskRef.getData().getTaskId());
        assertEquals("TASK-S8-001", mission.getDjiTaskId());
        mission.setStatus(FireMissionStatus.ACCEPTED_BY_PILOT.name());
        ApiResult<DeliveryTaskOperationResult> started = f.deliveryController.startTask(mission.getMissionNo(),
            f.createTask("commander-1"), f.request("REQ-START-TASK"));
        assertEquals(true, started.getData().getAccepted());
        f.incidentStateMachine.transit(IncidentTransitCommand.builder()
            .incidentId(confirmed.getIncident().getId())
            .event(OperationIncidentEvent.RESPOND)
            .operatorId("delivery-sync")
            .requestId("REQ-RESPOND")
            .build());
        assertEquals(OperationIncidentStatus.RESPONDING.name(), f.onlyIncident().getStatus());
        assertEquals(FireMissionStatus.IN_PROGRESS.name(), mission.getStatus());

        f.deliveryController.pollInProgressMissionsForAutoRelease();
        assertEquals(FireMissionStatus.PAYLOAD_RELEASE_PENDING.name(), mission.getStatus());
        assertNotNull(mission.getReleaseConfirmationToken());
        assertNotNull(mission.getReleaseTokenExpiresAt());

        PayloadConfirmReleaseParam wrongToken = f.confirmReleaseParam("WRONG-TOKEN");
        assertThrows(Fc100BusinessException.class,
            () -> f.payloadService.confirmRelease(mission.getMissionNo(), wrongToken, "127.0.0.1", "REQ-REL-DENY"));
        assertTrue(f.store.missionLogs.stream().anyMatch(log -> "PAYLOAD_RELEASE_TOKEN_DENIED".equals(log.getAction())));

        PayloadConfirmReleaseParam correctToken = f.confirmReleaseParam(mission.getReleaseConfirmationToken());
        correctToken.setRemoteHookRemark("pilot opened official FC100 remote hook");
        f.payloadService.confirmRelease(mission.getMissionNo(), correctToken, "127.0.0.1", "REQ-REL-OK");
        f.missionStateMachine.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.MARK_RETURNING)
            .operatorId("commander-1")
            .requestId("REQ-RETURN")
            .build());
        assertEquals(FireMissionStatus.RETURNING.name(), mission.getStatus());
        assertTrue(f.store.payloadEvents.stream().anyMatch(event ->
            "RELEASED".equals(event.getEventType())
                && event.getEventValue().startsWith(ReleaseExecutionMode.OFFICIAL_HOOK_MANUAL.name())
                && "operator-release".equals(event.getOperatorId())));

        FireEventRecheckResultDTO unresolved = f.fireEventService.recordRecheckResult("S8-EVT-001",
            f.recheck("operator-recheck", "RESOLVED", 430.0, 18.0, false), f.request("REQ-RECHECK-1"));
        assertFalse(unresolved.isResolved());
        assertEquals(OperationIncidentStatus.RESPONDING.name(), f.onlyIncident().getStatus());

        FireEventRecheckResultDTO resolved = f.fireEventService.recordRecheckResult("S8-EVT-001",
            f.recheck("operator-recheck", "RESOLVED", 120.0, 4.0, false), f.request("REQ-RECHECK-2"));
        assertTrue(resolved.isResolved());
        assertEquals(OperationIncidentStatus.RESOLVED.name(), f.onlyIncident().getStatus());

        f.missionStateMachine.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.MARK_RETURN_COMPLETED)
            .operatorId("pilot-1")
            .requestId("REQ-RETURN-DONE")
            .build());
        f.missionStateMachine.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.SUBMIT_REVIEW)
            .operatorId("operator-review")
            .requestId("REQ-REVIEW")
            .build());
        f.incidentService.close(confirmed.getIncident().getId(),
            f.operationAction("commander-1", "archive after recheck resolved"), f.request("REQ-CLOSE"));
        f.missionStateMachine.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.ARCHIVE)
            .operatorId("commander-1")
            .requestId("REQ-MISSION-ARCHIVE")
            .build());
        assertEquals(OperationIncidentStatus.ARCHIVED.name(), f.onlyIncident().getStatus());
        assertEquals(FireMissionStatus.ARCHIVED.name(), mission.getStatus());

        f.deliveryController.emergencyStop(mission.getMissionNo(), f.deviceCommand("commander-1"));
        assertTrue(f.store.commandEvents.stream().anyMatch(event ->
            "drone_emergency_stop".equals(event.getCommandType())
                && mission.getMissionNo().equals(event.getMissionNo())));

        f.assertIncidentAuditActions("CREATE", "ASSIGN_DELIVERY", "DISPATCH", "RESPOND",
            "START_RECHECK", "CONTINUE_RESPONSE", "START_RECHECK", "RESOLVE", "ARCHIVE");
        f.assertMissionAuditActions(FireMissionEvent.CREATE_DELIVERY_TASK.name(),
            FireMissionEvent.START_DELIVERY.name(),
            FireMissionEvent.MARK_RELEASE_PENDING.name(),
            "PAYLOAD_RELEASE_TOKEN_DENIED",
            FireMissionEvent.CONFIRM_RELEASE.name(),
            FireMissionEvent.MARK_RETURNING.name(),
            FireMissionEvent.MARK_RETURN_COMPLETED.name(),
            FireMissionEvent.SUBMIT_REVIEW.name(),
            FireMissionEvent.ARCHIVE.name());
    }

    @Test
    void exceptionMockDrillCoversDeliveryHubFailureTakeoverAndReleaseTimeoutReturn() throws Exception {
        DrillFixture f = new DrillFixture();
        FireMissionEntity mission = f.seedMission("MISSION-S8-EXCEPTION", FireMissionStatus.ROUTE_EXPORTED);
        f.stubRoute(mission);
        when(f.adapter.createTask(any(CreateTaskRequest.class)))
            .thenThrow(new RuntimeException("DeliveryHub unreachable"));

        for (int i = 1; i <= 3; i++) {
            assertThrows(RuntimeException.class,
                () -> f.deliveryController.createTask(mission.getMissionNo(),
                    f.createTask("commander-1"), f.request("REQ-HUB-" + UUID.randomUUID())));
        }
        assertEquals(FireMissionStatus.MANUAL_TAKEOVER.name(), mission.getStatus());
        assertTrue(f.store.missionLogs.stream().anyMatch(log ->
            FireMissionEvent.TAKEOVER.name().equals(log.getAction())
                && log.getRemark() != null
                && log.getRemark().contains("DeliveryHub unreachable")));

        DrillFixture timeoutFixture = new DrillFixture();
        FireMissionEntity pending = timeoutFixture.seedMission("MISSION-S8-TIMEOUT", FireMissionStatus.PAYLOAD_RELEASE_PENDING);
        pending.setReleaseTokenExpiresAt(1L);
        timeoutFixture.deliveryController.scanReleasePendingTimeouts();
        assertEquals(FireMissionStatus.RETURNING.name(), pending.getStatus());
        assertTrue(timeoutFixture.store.commandEvents.stream().anyMatch(event ->
            "return_home".equals(event.getCommandType())
                && pending.getMissionNo().equals(event.getMissionNo())));
        assertTrue(timeoutFixture.store.missionLogs.stream().anyMatch(log ->
            FireMissionEvent.MARK_RETURNING.name().equals(log.getAction())
                && "RELEASE_PENDING_TIMEOUT_AUTO_RETURN".equals(log.getRemark())));
    }

    private static class DrillFixture {
        private static final long NOW = 1770000000000L;

        private final MutableClock clock = new MutableClock(NOW);
        private final Store store = new Store();
        private final ObjectMapper objectMapper = new ObjectMapper();

        private final FireEventMapper fireEventMapper = mock(FireEventMapper.class);
        private final FireEventHistoryMapper fireEventHistoryMapper = mock(FireEventHistoryMapper.class);
        private final FireMissionMapper fireMissionMapper = mock(FireMissionMapper.class);
        private final FireMissionLogMapper fireMissionLogMapper = mock(FireMissionLogMapper.class);
        private final OperationIncidentMapper incidentMapper = mock(OperationIncidentMapper.class);
        private final OperationIncidentLogMapper incidentLogMapper = mock(OperationIncidentLogMapper.class);
        private final OperationAssignmentMapper assignmentMapper = mock(OperationAssignmentMapper.class);
        private final OperationResourceLeaseMapper leaseMapper = mock(OperationResourceLeaseMapper.class);
        private final OperationCommandEventMapper commandMapper = mock(OperationCommandEventMapper.class);
        private final OperationComplianceRecordMapper complianceRecordMapper = mock(OperationComplianceRecordMapper.class);
        private final OperationFlightApplicationRecordMapper flightApplicationMapper = mock(OperationFlightApplicationRecordMapper.class);
        private final OperationTakeoffConfirmationMapper takeoffConfirmationMapper = mock(OperationTakeoffConfirmationMapper.class);
        private final OperationQualificationMapper qualificationMapper = mock(OperationQualificationMapper.class);
        private final PayloadEventMapper payloadEventMapper = mock(PayloadEventMapper.class);
        private final DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        private final RouteExportService routeService = mock(RouteExportService.class);

        private final IncidentStateMachine incidentStateMachine;
        private final MissionStateMachine missionStateMachine;
        private final PreflightExecutionService preflightService;
        private final OperationIncidentServiceImpl incidentService;
        private final FireEventServiceImpl fireEventService;
        private final PayloadServiceImpl payloadService;
        private final DeliveryController deliveryController;

        private DrillFixture() {
            stubMappers();
            this.incidentStateMachine = new IncidentStateMachineImpl(incidentMapper, incidentLogMapper, clock);
            this.missionStateMachine = new MissionStateMachineImpl(fireMissionMapper, fireMissionLogMapper, clock);

            PreflightProperties properties = new PreflightProperties();
            PreflightContextFactory contextFactory = new PreflightContextFactory(
                fireEventMapper,
                fireMissionMapper,
                adapter,
                flightApplicationMapper,
                takeoffConfirmationMapper,
                qualificationMapper,
                leaseMapper,
                commandMapper,
                context -> BoundaryCheckResult.inside(),
                new DeliveryHubHealthProbe(adapter),
                properties,
                clock);
            this.preflightService = new PreflightExecutionService(
                incidentMapper,
                assignmentMapper,
                complianceRecordMapper,
                contextFactory,
                new PreflightRuleEngine(properties),
                objectMapper);

            this.incidentService = new OperationIncidentServiceImpl(
                incidentMapper,
                assignmentMapper,
                incidentLogMapper,
                fireMissionLogMapper,
                fireEventMapper,
                fireMissionMapper,
                incidentStateMachine,
                new FixedIncidentNoGenerator(clock),
                new RealPreflightGate(preflightService),
                resourceLeaseService(),
                clock);

            this.fireEventService = new FireEventServiceImpl(
                fireEventMapper,
                fireEventHistoryMapper,
                fireMissionMapper,
                new FixedMissionNoGenerator(clock),
                clock,
                null,
                null,
                incidentService,
                incidentMapper,
                incidentStateMachine,
                new Fc100ThermalProperties());

            PayloadReleasePolicyService releasePolicyService =
                new PayloadReleasePolicyService(fireMissionLogMapper, clock);
            this.payloadService = new PayloadServiceImpl(
                fireMissionMapper,
                payloadEventMapper,
                missionStateMachine,
                objectMapper,
                clock,
                releasePolicyService);

            CommandQueueServiceImpl commandQueue = new CommandQueueServiceImpl(
                commandMapper,
                List.of(),
                objectMapper,
                new CommandQueueProperties(),
                clock);

            DeliverySyncProperties deliveryProps = new DeliverySyncProperties();
            deliveryProps.setManualTakeoverFailureThreshold(3);
            this.deliveryController = new DeliveryController(adapter, deliveryProps,
                fireMissionMapper, routeService, missionStateMachine,
                null, null, fireEventMapper, null, null, releasePolicyService, commandQueue);

            ReflectionTestUtils.setField(deliveryController, "releasePendingTimeout", Duration.ofMinutes(5));

            DeliveryDeviceProperties device = new DeliveryDeviceProperties();
            device.setDeviceSn("FC100-S8-001");
            device.setOnlineStatus(true);
            device.setBatteryPercent(80);
            device.setRtkStatus("FIX");
            device.setLatitude(22.120000);
            device.setLongitude(113.650000);
            device.setWindSpeed(6.0);
            when(adapter.getDeviceProperties(any())).thenReturn(device);
            when(adapter.importWayline(any(WaylineImportRequest.class))).thenReturn(DeliveryWaylineImportResult.builder()
                .waylineId("WAYLINE-S8-001")
                .name("S8 route")
                .build());
            when(adapter.createTask(any(CreateTaskRequest.class))).thenReturn(new DeliveryTaskRef("TASK-S8-001", "2"));
            when(adapter.startTask("TASK-S8-001")).thenReturn(DeliveryTaskOperationResult.builder()
                .operation("startTask")
                .taskId("TASK-S8-001")
                .accepted(true)
                .status("ACCEPTED")
                .build());
            DeliveryTaskStatus atDrop = new DeliveryTaskStatus();
            atDrop.setTaskId("TASK-S8-001");
            atDrop.setAccepted(true);
            atDrop.setStatus("ARRIVED");
            atDrop.setPhase("HOVERING");
            when(adapter.queryTaskStatus("TASK-S8-001")).thenReturn(atDrop);
        }

        private void stubMappers() {
            when(fireEventMapper.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
                FireEventEntity entity = inv.getArgument(0);
                entity.setId(store.nextFireEventId++);
                store.fireEvents.put(entity.getId(), entity);
                return 1;
            });
            when(fireEventMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> store.fireEvents.values().stream().findFirst().orElse(null));
            when(fireEventMapper.selectById(any())).thenAnswer(inv -> store.fireEvents.get(asLong(inv.getArgument(0))));
            when(fireEventMapper.updateById(any(FireEventEntity.class))).thenAnswer(inv -> {
                merge(store.fireEvents.get(inv.<FireEventEntity>getArgument(0).getId()), inv.getArgument(0));
                return 1;
            });
            when(fireEventHistoryMapper.insert(any(FireEventHistoryEntity.class))).thenAnswer(inv -> {
                store.fireEventHistories.add(inv.getArgument(0));
                return 1;
            });

            when(fireMissionMapper.insert(any(FireMissionEntity.class))).thenAnswer(inv -> {
                FireMissionEntity entity = inv.getArgument(0);
                entity.setId(store.nextMissionId++);
                store.missions.put(entity.getId(), entity);
                return 1;
            });
            when(fireMissionMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> store.missions.values().stream().findFirst().orElse(null));
            when(fireMissionMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.missions.values()));
            when(fireMissionMapper.update(any(), any(UpdateWrapper.class))).thenAnswer(inv -> {
                Object updateEntity = inv.getArgument(0);
                UpdateWrapper<?> wrapper = inv.getArgument(1);
                store.missions.values().forEach(mission -> {
                    if (updateEntity instanceof FireMissionEntity) {
                        merge(mission, updateEntity);
                    }
                    applyWrapperSet(mission, wrapper);
                });
                return store.missions.isEmpty() ? 0 : 1;
            });
            when(fireMissionMapper.updateById(any(FireMissionEntity.class))).thenAnswer(inv -> {
                FireMissionEntity entity = inv.getArgument(0);
                merge(store.missions.get(entity.getId()), entity);
                return 1;
            });
            when(fireMissionLogMapper.insert(any(FireMissionLogEntity.class))).thenAnswer(inv -> {
                store.missionLogs.add(inv.getArgument(0));
                return 1;
            });
            when(fireMissionLogMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> new ArrayList<>(store.missionLogs));

            when(incidentMapper.insert(any(OperationIncidentEntity.class))).thenAnswer(inv -> {
                OperationIncidentEntity entity = inv.getArgument(0);
                entity.setId(store.nextIncidentId++);
                store.incidents.put(entity.getId(), entity);
                return 1;
            });
            when(incidentMapper.selectById(any())).thenAnswer(inv -> store.incidents.get(asLong(inv.getArgument(0))));
            when(incidentMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.incidents.values()));
            when(incidentMapper.update(any(), any(UpdateWrapper.class))).thenAnswer(inv -> {
                store.incidents.values().forEach(incident -> applyWrapperSet(incident, inv.getArgument(1)));
                return store.incidents.isEmpty() ? 0 : 1;
            });
            when(incidentMapper.updateById(any(OperationIncidentEntity.class))).thenAnswer(inv -> {
                OperationIncidentEntity entity = inv.getArgument(0);
                merge(store.incidents.get(entity.getId()), entity);
                return 1;
            });
            when(incidentLogMapper.insert(any(OperationIncidentLogEntity.class))).thenAnswer(inv -> {
                store.incidentLogs.add(inv.getArgument(0));
                return 1;
            });
            when(incidentLogMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> new ArrayList<>(store.incidentLogs));

            when(assignmentMapper.insert(any(OperationAssignmentEntity.class))).thenAnswer(inv -> {
                OperationAssignmentEntity entity = inv.getArgument(0);
                entity.setId(store.nextAssignmentId++);
                store.assignments.put(entity.getId(), entity);
                return 1;
            });
            when(assignmentMapper.selectById(any())).thenAnswer(inv -> store.assignments.get(asLong(inv.getArgument(0))));
            when(assignmentMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.assignments.values()));
            when(assignmentMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> store.assignments.values().stream().findFirst().orElse(null));
            when(assignmentMapper.updateById(any(OperationAssignmentEntity.class))).thenAnswer(inv -> {
                merge(store.assignments.get(inv.<OperationAssignmentEntity>getArgument(0).getId()), inv.getArgument(0));
                return 1;
            });

            when(leaseMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.leases.values()));
            when(commandMapper.insert(any(OperationCommandEventEntity.class))).thenAnswer(inv -> {
                OperationCommandEventEntity entity = inv.getArgument(0);
                entity.setId(store.nextCommandId++);
                store.commandEvents.add(entity);
                return 1;
            });
            when(commandMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.commandEvents));
            when(commandMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> store.commandEvents.stream().findFirst().orElse(null));
            when(commandMapper.updateById(any(OperationCommandEventEntity.class))).thenReturn(1);
            when(commandMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

            when(complianceRecordMapper.insert(any(OperationComplianceRecordEntity.class))).thenAnswer(inv -> {
                OperationComplianceRecordEntity entity = inv.getArgument(0);
                entity.setId(store.nextComplianceId++);
                store.preflightRecords.add(entity);
                return 1;
            });
            when(flightApplicationMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.flightApplications));
            when(takeoffConfirmationMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.takeoffConfirmations));
            when(qualificationMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> new ArrayList<>(store.qualifications));

            when(payloadEventMapper.insert(any(PayloadEventEntity.class))).thenAnswer(inv -> {
                store.payloadEvents.add(inv.getArgument(0));
                return 1;
            });
        }

        private ResourceLeaseService resourceLeaseService() {
            return new ResourceLeaseService() {
                @Override
                public OperationResourceLeaseEntity acquire(String resourceSn, String leaseType, String ownerType,
                                                            Long ownerId, Duration ttl) {
                    OperationResourceLeaseEntity lease = new OperationResourceLeaseEntity();
                    lease.setId(store.nextLeaseId++);
                    lease.setResourceSn(resourceSn);
                    lease.setLeaseType(leaseType);
                    lease.setOwnerType(ownerType);
                    lease.setOwnerId(ownerId);
                    lease.setStatus("ACTIVE");
                    lease.setExpiresAt(clock.now() + ttl.toMillis());
                    store.leases.put(lease.getId(), lease);
                    return lease;
                }

                @Override
                public void renew(Long leaseId, String ownerType, Long ownerId, Duration ttl) {
                }

                @Override
                public void release(Long leaseId, String ownerType, Long ownerId) {
                    OperationResourceLeaseEntity lease = store.leases.get(leaseId);
                    if (lease != null) {
                        lease.setStatus("RELEASED");
                    }
                }

                @Override
                public int expireStale() {
                    return 0;
                }
            };
        }

        private FireEventCreateParam thermalAlarm(String eventId, double maxTemp, double hotArea) {
            FireEventCreateParam param = new FireEventCreateParam();
            param.setEventId(eventId);
            param.setSource("M4T");
            param.setDeviceSn("M4T-S8-001");
            param.setConfidence(new BigDecimal("0.96"));
            param.setFireLevel("HIGH");
            param.setLat(22.123456);
            param.setLng(113.654321);
            param.setAlt(55.0);
            param.setGeoMethod("TRIANGULATION");
            param.setGeoQuality("PRECISE");
            param.setGeoErrorRadiusM(5.0);
            param.setThermalTemperature(maxTemp);
            param.setThermalRoi("{\"areaM2\":" + hotArea + "}");
            param.setTemperatureUnit("C");
            param.setTimestamp("2026-07-02T02:00:00Z");
            param.setWorkspaceId("WS-S8");
            return param;
        }

        private FireEventActionParam fireAction(String operatorId) {
            FireEventActionParam param = new FireEventActionParam();
            param.setOperatorId(operatorId);
            param.setReason("confirmed by command center");
            return param;
        }

        private AssignOperationResourceParam assignDelivery(String resourceSn) {
            AssignOperationResourceParam param = new AssignOperationResourceParam();
            param.setResourceSn(resourceSn);
            param.setRole(OperationAssignmentRole.DELIVERY_PRIMARY.name());
            param.setOperatorId("commander-1");
            return param;
        }

        private OperationActionParam operationAction(String operatorId, String reason) {
            OperationActionParam param = new OperationActionParam();
            param.setOperatorId(operatorId);
            param.setReason(reason);
            return param;
        }

        private DeliveryController.CreateTaskParam createTask(String operatorId) {
            DeliveryController.CreateTaskParam param = new DeliveryController.CreateTaskParam();
            param.setOperatorId(operatorId);
            param.setTaskName("S8 acceptance task");
            return param;
        }

        private DeliveryController.DeviceCommandParam deviceCommand(String operatorId) {
            DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
            param.setOperatorId(operatorId);
            return param;
        }

        private PayloadConfirmReleaseParam confirmReleaseParam(String token) {
            PayloadConfirmReleaseParam param = new PayloadConfirmReleaseParam();
            param.setOperatorId("operator-release");
            param.setConfirmedArrival(true);
            param.setConfirmedNoPeopleRisk(true);
            param.setConfirmedWindOk(true);
            param.setConfirmedPayloadReady(true);
            param.setConfirmedRelease(true);
            param.setConfirmationToken(token);
            return param;
        }

        private FireEventRecheckResultParam recheck(String operatorId, String suggestion,
                                                    double maxTemp, double area, boolean flameVisible) {
            FireEventRecheckResultParam param = new FireEventRecheckResultParam();
            param.setOperatorId(operatorId);
            param.setSuggestion(suggestion);
            param.setMaxTemp(maxTemp);
            param.setHotAreaM2(area);
            param.setFlameVisible(flameVisible);
            param.setRemark("S8 recheck");
            return param;
        }

        private void addComplianceRecords(Long incidentId) {
            OperationFlightApplicationRecordEntity flight = new OperationFlightApplicationRecordEntity();
            flight.setIncidentId(incidentId);
            flight.setApplicationNo("FA-S8-001");
            flight.setApprovalNo("APPROVAL-S8-001");
            flight.setValidFrom(NOW - 10_000L);
            flight.setValidTo(NOW + 86_400_000L);
            store.flightApplications.add(flight);

            OperationTakeoffConfirmationEntity takeoff = new OperationTakeoffConfirmationEntity();
            takeoff.setIncidentId(incidentId);
            takeoff.setOperatorId("commander-1");
            takeoff.setConfirmedAt(NOW);
            store.takeoffConfirmations.add(takeoff);

            store.qualifications.add(qualification("CLUSTER_FLIGHT_PERMIT"));
            store.qualifications.add(qualification("AIRDROP_APPROVAL"));
            store.qualifications.add(qualification("AIRWORTHINESS"));
            store.qualifications.add(qualification("JOINT_OPERATION_AGREEMENT"));
        }

        private OperationQualificationEntity qualification(String type) {
            OperationQualificationEntity qualification = new OperationQualificationEntity();
            qualification.setQualificationType(type);
            qualification.setQualificationNo(type + "-S8");
            qualification.setIssuer("authority");
            qualification.setValidFrom(NOW - 10_000L);
            qualification.setValidTo(NOW + 86_400_000L);
            qualification.setStatus("VALID");
            return qualification;
        }

        private void prepareMissionForDelivery(FireMissionEntity mission) throws Exception {
            mission.setStatus(FireMissionStatus.ROUTE_EXPORTED.name());
            mission.setVersion(0L);
            mission.setAircraftSn("FC100-S8-001");
            mission.setPayloadType("LIFTING_DUAL_BATTERY");
            mission.setEstimatedTotalWeightKg(80.0);
            mission.setWaterLoadLiters(20.0);
            mission.setTakeoffLat(22.120000);
            mission.setTakeoffLng(113.650000);
            mission.setTakeoffAlt(30.0);
            mission.setWindSpeedAtApproval(6.0);
            mission.setWindDirectionDeg(45.0);
            stubRoute(mission);
        }

        private void stubRoute(FireMissionEntity mission) throws Exception {
            RouteFileDTO route = new RouteFileDTO();
            route.setId(10L);
            route.setObjectKey("s8.kmz");
            route.setSign("sha256:s8");
            when(routeService.getLatest(mission.getMissionNo())).thenReturn(route);
            when(routeService.downloadById(10L)).thenReturn(kmzWithTemplate("<kml><Document><name>S8</name></Document></kml>"));
        }

        private FireMissionEntity seedMission(String no, FireMissionStatus status) {
            FireMissionEntity mission = new FireMissionEntity();
            mission.setId(store.nextMissionId++);
            mission.setMissionNo(no);
            mission.setWorkspaceId("WS-S8");
            mission.setStatus(status.name());
            mission.setAircraftSn("FC100-S8-001");
            mission.setVersion(0L);
            mission.setDeleted(0);
            mission.setCreateTime(clock.now());
            mission.setUpdateTime(clock.now());
            mission.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
            mission.setReleaseExecutionMode(ReleaseExecutionMode.OFFICIAL_HOOK_MANUAL.name());
            store.missions.put(mission.getId(), mission);
            return mission;
        }

        private OperationIncidentEntity onlyIncident() {
            return store.incidents.values().stream().findFirst().orElseThrow();
        }

        private FireMissionEntity onlyMission() {
            return store.missions.values().stream().findFirst().orElseThrow();
        }

        private void assertIncidentAuditActions(String... actions) {
            List<String> actual = store.incidentLogs.stream().map(OperationIncidentLogEntity::getAction).collect(Collectors.toList());
            for (String action : actions) {
                assertTrue(actual.contains(action), () -> "missing incident audit action " + action + " in " + actual);
            }
        }

        private void assertMissionAuditActions(String... actions) {
            List<String> actual = store.missionLogs.stream().map(FireMissionLogEntity::getAction).collect(Collectors.toList());
            for (String action : actions) {
                assertTrue(actual.contains(action), () -> "missing mission audit action " + action + " in " + actual);
            }
        }

        private HttpServletRequest request(String requestId) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("127.0.0.1");
            when(request.getHeader("X-Request-Id")).thenReturn(requestId);
            when(request.getHeader("X-Idempotency-Key")).thenReturn(requestId + "-IDEMP");
            return request;
        }

        private byte[] kmzWithTemplate(String templateKml) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("wpmz/template.kml"));
                zip.write(templateKml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        }
    }

    private static void applyWrapperSet(Object target, UpdateWrapper<?> wrapper) {
        if (target == null || wrapper == null || wrapper.getSqlSet() == null) {
            return;
        }
        String sqlSet = wrapper.getSqlSet();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        int paramIndex = 1;
        for (String assignment : sqlSet.split(",")) {
            String[] parts = assignment.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String column = parts[0].trim();
            Object value;
            if (parts[1].contains("paramNameValuePairs")) {
                value = params.get("MPGENVAL" + paramIndex++);
            } else if ("null".equalsIgnoreCase(parts[1].trim())) {
                value = null;
            } else {
                value = parts[1].trim();
            }
            setByColumn(target, column, value);
        }
    }

    private static void setByColumn(Object target, String column, Object value) {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("incident_id", "incidentId");
        mapping.put("update_time", "updateTime");
        mapping.put("updated_by", "updatedBy");
        mapping.put("status", "status");
        mapping.put("version", "version");
        mapping.put("closed_at", "closedAt");
        mapping.put("recommended_recheck", "recommendedRecheck");
        mapping.put("recheck_reason", "recheckReason");
        mapping.put("dji_task_id", "djiTaskId");
        mapping.put("started_at", "startedAt");
        mapping.put("release_confirmation_token", "releaseConfirmationToken");
        mapping.put("release_pending_started_at", "releasePendingStartedAt");
        mapping.put("release_token_expires_at", "releaseTokenExpiresAt");
        mapping.put("release_token_used_at", "releaseTokenUsedAt");
        mapping.put("payload_released_at", "payloadReleasedAt");
        mapping.put("release_operator_id", "releaseOperatorId");
        mapping.put("completed_at", "completedAt");
        mapping.put("reviewer_id", "reviewerId");
        mapping.put("archived_at", "archivedAt");
        mapping.put("failed_reason", "failedReason");
        mapping.put("approved_at", "approvedAt");
        mapping.put("approver_id", "approverId");
        mapping.put("aircraft_sn", "aircraftSn");
        mapping.put("payload_id", "payloadId");
        mapping.put("water_load_liters", "waterLoadLiters");
        mapping.put("wind_speed_at_approval", "windSpeedAtApproval");
        mapping.put("wind_direction_deg", "windDirectionDeg");
        mapping.put("takeoff_lat", "takeoffLat");
        mapping.put("takeoff_lng", "takeoffLng");
        mapping.put("takeoff_alt", "takeoffAlt");
        setProperty(target, mapping.getOrDefault(column, column), value);
    }

    private static void merge(Object target, Object source) {
        if (target == null || source == null) {
            return;
        }
        try {
            for (PropertyDescriptor pd : Introspector.getBeanInfo(source.getClass()).getPropertyDescriptors()) {
                if (pd.getReadMethod() == null || pd.getWriteMethod() == null || "class".equals(pd.getName())) {
                    continue;
                }
                Object value = pd.getReadMethod().invoke(source);
                if (value != null) {
                    setProperty(target, pd.getName(), value);
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void setProperty(Object target, String property, Object value) {
        try {
            for (PropertyDescriptor pd : Introspector.getBeanInfo(target.getClass()).getPropertyDescriptors()) {
                if (pd.getName().equals(property) && pd.getWriteMethod() != null) {
                    pd.getWriteMethod().invoke(target, value);
                    return;
                }
            }
        } catch (Exception e) {
            throw new AssertionError("failed to set " + property + " on " + target.getClass(), e);
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static class MutableClock implements Clock {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long now() {
            return now;
        }
    }

    private static class FixedMissionNoGenerator extends MissionNoGenerator {
        private int count = 1;

        private FixedMissionNoGenerator(Clock clock) {
            super(clock);
        }

        @Override
        public String next() {
            return "MISSION-S8-" + String.format("%03d", count++);
        }
    }

    private static class FixedIncidentNoGenerator extends IncidentNoGenerator {
        private int count = 1;

        private FixedIncidentNoGenerator(Clock clock) {
            super(clock);
        }

        @Override
        public String next() {
            return "INCIDENT-S8-" + String.format("%03d", count++);
        }
    }

    private static class Store {
        private long nextFireEventId = 10L;
        private long nextIncidentId = 501L;
        private long nextMissionId = 601L;
        private long nextAssignmentId = 701L;
        private long nextLeaseId = 1L;
        private long nextCommandId = 801L;
        private long nextComplianceId = 901L;

        private final Map<Long, FireEventEntity> fireEvents = new LinkedHashMap<>();
        private final List<FireEventHistoryEntity> fireEventHistories = new ArrayList<>();
        private final Map<Long, OperationIncidentEntity> incidents = new LinkedHashMap<>();
        private final List<OperationIncidentLogEntity> incidentLogs = new ArrayList<>();
        private final Map<Long, FireMissionEntity> missions = new LinkedHashMap<>();
        private final List<FireMissionLogEntity> missionLogs = new ArrayList<>();
        private final Map<Long, OperationAssignmentEntity> assignments = new LinkedHashMap<>();
        private final Map<Long, OperationResourceLeaseEntity> leases = new LinkedHashMap<>();
        private final List<OperationCommandEventEntity> commandEvents = new ArrayList<>();
        private final List<OperationComplianceRecordEntity> preflightRecords = new ArrayList<>();
        private final List<OperationFlightApplicationRecordEntity> flightApplications = new ArrayList<>();
        private final List<OperationTakeoffConfirmationEntity> takeoffConfirmations = new ArrayList<>();
        private final List<OperationQualificationEntity> qualifications = new ArrayList<>();
        private final List<PayloadEventEntity> payloadEvents = new ArrayList<>();
    }
}
