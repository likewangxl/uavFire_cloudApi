package com.yx.uavfire.fc100.operation.preflight;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationFlightApplicationRecordMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationQualificationMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationTakeoffConfirmationMapper;
import com.yx.uavfire.fc100.operation.dao.OperationCommandEventMapper;
import com.yx.uavfire.fc100.operation.dao.OperationResourceLeaseMapper;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import com.yx.uavfire.fc100.operation.preflight.boundary.BoundaryCheckResult;
import com.yx.uavfire.fc100.operation.preflight.boundary.UomBoundaryChecker;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PreflightContextFactory {
    private static final List<String> OPEN_COMMAND_STATUSES = List.of("PENDING", "SENDING", "WAIT_ACK");

    private final FireEventMapper fireEventMapper;
    private final FireMissionMapper fireMissionMapper;
    private final DeliverySyncAdapter deliverySyncAdapter;
    private final OperationFlightApplicationRecordMapper flightApplicationMapper;
    private final OperationTakeoffConfirmationMapper takeoffConfirmationMapper;
    private final OperationQualificationMapper qualificationMapper;
    private final OperationResourceLeaseMapper resourceLeaseMapper;
    private final OperationCommandEventMapper commandEventMapper;
    private final UomBoundaryChecker boundaryChecker;
    private final DeliveryHubHealthProbe healthProbe;
    private final PreflightProperties properties;
    private final Clock clock;

    public PreflightContextFactory(FireEventMapper fireEventMapper,
                                   FireMissionMapper fireMissionMapper,
                                   DeliverySyncAdapter deliverySyncAdapter,
                                   OperationFlightApplicationRecordMapper flightApplicationMapper,
                                   OperationTakeoffConfirmationMapper takeoffConfirmationMapper,
                                   OperationQualificationMapper qualificationMapper,
                                   OperationResourceLeaseMapper resourceLeaseMapper,
                                   OperationCommandEventMapper commandEventMapper,
                                   UomBoundaryChecker boundaryChecker,
                                   DeliveryHubHealthProbe healthProbe,
                                   PreflightProperties properties,
                                   Clock clock) {
        this.fireEventMapper = fireEventMapper;
        this.fireMissionMapper = fireMissionMapper;
        this.deliverySyncAdapter = deliverySyncAdapter;
        this.flightApplicationMapper = flightApplicationMapper;
        this.takeoffConfirmationMapper = takeoffConfirmationMapper;
        this.qualificationMapper = qualificationMapper;
        this.resourceLeaseMapper = resourceLeaseMapper;
        this.commandEventMapper = commandEventMapper;
        this.boundaryChecker = boundaryChecker;
        this.healthProbe = healthProbe;
        this.properties = properties;
        this.clock = clock;
    }

    public PreflightContext create(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary,
                                   String operatorId) {
        long now = clock.now();
        FireEventEntity fireEvent = incident == null || incident.getFireEventId() == null
            ? null : fireEventMapper.selectById(incident.getFireEventId());
        FireMissionEntity mission = incident == null ? null : fireMissionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>()
                .eq("incident_id", incident.getId())
                .eq("deleted", 0)
                .orderByDesc("id")
                .last("limit 1"));
        DeliveryDeviceProperties deviceProperties = deviceProperties(deliveryPrimary);

        PreflightContext context = PreflightContext.builder()
            .incident(incident)
            .fireEvent(fireEvent)
            .mission(mission)
            .deliveryPrimary(deliveryPrimary)
            .deviceProperties(deviceProperties)
            .flightApplications(incident == null ? List.of() : flightApplicationMapper.selectList(
                new QueryWrapper<com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity>()
                    .eq("incident_id", incident.getId())))
            .takeoffConfirmations(incident == null ? List.of() : takeoffConfirmationMapper.selectList(
                new QueryWrapper<com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity>()
                    .eq("incident_id", incident.getId())))
            .qualifications(qualificationMapper.selectList(new QueryWrapper<>()))
            .activeLeases(activeLeases(deliveryPrimary))
            .commandEvents(commandEvents(deliveryPrimary))
            .deliveryHubReachable(healthProbe.reachable(mission))
            .operatorId(operatorId)
            .now(now)
            .properties(properties)
            .build();
        BoundaryCheckResult boundary = boundaryChecker.check(context);
        context.boundaryCheck(boundary);
        return context;
    }

    private DeliveryDeviceProperties deviceProperties(OperationAssignmentEntity assignment) {
        if (assignment == null || assignment.getResourceSn() == null) {
            return null;
        }
        try {
            return deliverySyncAdapter.getDeviceProperties(assignment.getResourceSn());
        } catch (Exception ex) {
            return null;
        }
    }

    private List<OperationResourceLeaseEntity> activeLeases(OperationAssignmentEntity assignment) {
        if (assignment == null || assignment.getResourceSn() == null) {
            return List.of();
        }
        return resourceLeaseMapper.selectList(new QueryWrapper<OperationResourceLeaseEntity>()
            .eq("resource_sn", assignment.getResourceSn())
            .eq("lease_type", "DELIVERY_PRIMARY")
            .eq("status", "ACTIVE"));
    }

    private List<OperationCommandEventEntity> commandEvents(OperationAssignmentEntity assignment) {
        if (assignment == null || assignment.getResourceSn() == null) {
            return List.of();
        }
        return commandEventMapper.selectList(new QueryWrapper<OperationCommandEventEntity>()
            .eq("target_sn", assignment.getResourceSn())
            .in("status", OPEN_COMMAND_STATUSES));
    }
}
