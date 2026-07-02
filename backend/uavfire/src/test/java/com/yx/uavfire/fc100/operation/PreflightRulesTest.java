package com.yx.uavfire.fc100.operation;

import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightProperties;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;
import com.yx.uavfire.fc100.operation.preflight.RuleStatus;
import com.yx.uavfire.fc100.operation.preflight.boundary.BoundaryCheckResult;
import com.yx.uavfire.fc100.operation.preflight.rules.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreflightRulesTest {

    @Test
    void r01BlocksUnconfirmedFireEventAndPassesConfirmed() {
        assertBlocked(new FireConfirmedRule().check(ctx(c -> c.fireEvent().setConfirmedStatus("PENDING"))));
        assertPassed(new FireConfirmedRule().check(ctx()));
    }

    @Test
    void r02BlocksWhenNoValidFlightApplicationAndPassesValidRecord() {
        assertBlocked(new AirspaceApprovalRule().check(ctx(c -> c.flightApplications(List.of()))));
        assertPassed(new AirspaceApprovalRule().check(ctx()));
    }

    @Test
    void r03BlocksAirdropWithoutApprovalAndPassesWithApproval() {
        assertBlocked(new AirdropApprovalRule().check(ctx(c -> c.qualifications(validBaseQualificationsWithoutAirdrop()))));
        assertPassed(new AirdropApprovalRule().check(ctx()));
    }

    @Test
    void r04BlocksOfflineDeviceAndPassesOnlineDevice() {
        assertBlocked(new DeviceOnlineRule().check(ctx(c -> c.deviceProperties().setOnlineStatus(false))));
        assertPassed(new DeviceOnlineRule().check(ctx()));
    }

    @Test
    void r05BlocksBatteryBelowThresholdAndPassesAtThreshold() {
        assertBlocked(new BatteryThresholdRule().check(ctx(c -> c.deviceProperties().setBatteryPercent(29))));
        assertPassed(new BatteryThresholdRule().check(ctx(c -> c.deviceProperties().setBatteryPercent(30))));
    }

    @Test
    void r06WarnsWhenWindMissingBlocksOverLimitAndPassesWithinLimit() {
        assertWarn(new WindLimitRule().check(ctx(c -> {
            c.mission().setWindSpeedAtApproval(null);
            c.deviceProperties().setWindSpeed(null);
        })));
        assertBlocked(new WindLimitRule().check(ctx(c -> c.mission().setWindSpeedAtApproval(13.0))));
        assertPassed(new WindLimitRule().check(ctx(c -> c.mission().setWindSpeedAtApproval(12.0))));
    }

    @Test
    void r07BlocksPayloadOverLimitAndPassesWithinLimit() {
        assertBlocked(new PayloadLimitRule().check(ctx(c -> c.mission().setEstimatedTotalWeightKg(86.0))));
        assertPassed(new PayloadLimitRule().check(ctx(c -> c.mission().setEstimatedTotalWeightKg(85.0))));
    }

    @Test
    void r08BlocksImpreciseLocationAndPassesPreciseLocation() {
        assertBlocked(new LocationQualityRule().check(ctx(c -> c.fireEvent().setGeoQuality("ESTIMATED"))));
        assertPassed(new LocationQualityRule().check(ctx()));
    }

    @Test
    void r09WarnsWithoutReferenceLayerBlocksOutsideLayerAndPassesInsideLayer() {
        assertWarn(new BoundaryRule().check(ctx(c -> c.boundaryCheck(BoundaryCheckResult.noReferenceLayer()))));
        assertBlocked(new BoundaryRule().check(ctx(c -> c.boundaryCheck(BoundaryCheckResult.outside("drop point outside UOM layer")))));
        assertPassed(new BoundaryRule().check(ctx(c -> c.boundaryCheck(BoundaryCheckResult.inside()))));
    }

    @Test
    void r10BlocksWithoutOperatorConfirmationAndPassesConfirmedOperator() {
        assertBlocked(new OperatorConfirmationRule().check(ctx(c -> c.takeoffConfirmations(List.of()))));
        assertPassed(new OperatorConfirmationRule().check(ctx()));
    }

    @Test
    void r11BlocksConflictingLeaseAndPassesOwnLeaseOnly() {
        assertBlocked(new ResourceLeaseConflictRule().check(ctx(c -> c.activeLeases(List.of(lease(99L))))));
        assertPassed(new ResourceLeaseConflictRule().check(ctx(c -> c.activeLeases(List.of(lease(501L))))));
    }

    @Test
    void r12BlocksPendingDangerousCommandAndPassesWithoutDangerousQueueBacklog() {
        assertBlocked(new DangerousCommandQueueRule().check(ctx(c -> c.commandEvents(List.of(command("drone_landing", "PENDING"))))));
        assertPassed(new DangerousCommandQueueRule().check(ctx(c -> c.commandEvents(List.of(command("MOCK", "PENDING"))))));
    }

    @Test
    void r13BlocksWhenTimeBudgetExceedsBatteryEnduranceAndPassesOtherwise() {
        assertBlocked(new TimeEnergyBudgetRule().check(ctx(c -> {
            c.mission().setTakeoffLat(22.000000);
            c.mission().setTakeoffLng(113.000000);
            c.incident().setCenterLat(22.100000);
            c.incident().setCenterLng(113.100000);
            c.deviceProperties().setBatteryPercent(31);
        })));
        assertPassed(new TimeEnergyBudgetRule().check(ctx()));
    }

    @Test
    void r14BlocksUnhealthyDeliveryHubAndPassesHealthyProbe() {
        assertBlocked(new DeliveryHubConnectivityRule().check(ctx(c -> c.deliveryHubReachable(false))));
        assertPassed(new DeliveryHubConnectivityRule().check(ctx()));
    }

    @Test
    void r15BlocksExpiredQualificationAndPassesAllRequiredValidQualifications() {
        assertBlocked(new QualificationValidityRule().check(ctx(c -> {
            OperationQualificationEntity expired = qualification("AIRWORTHINESS");
            expired.setValidTo(1769999999999L);
            c.qualifications(List.of(qualification("CLUSTER_FLIGHT_PERMIT"), qualification("AIRDROP_APPROVAL"),
                expired, qualification("JOINT_OPERATION_AGREEMENT")));
        })));
        assertPassed(new QualificationValidityRule().check(ctx()));
    }

    @Test
    void r15TreatsQualificationValidToEqualNowAsStillValid() {
        assertPassed(new QualificationValidityRule().check(ctx(c -> {
            OperationQualificationEntity boundary = qualification("AIRWORTHINESS");
            boundary.setValidTo(1770000000000L);
            c.qualifications(List.of(qualification("CLUSTER_FLIGHT_PERMIT"), qualification("AIRDROP_APPROVAL"),
                boundary, qualification("JOINT_OPERATION_AGREEMENT")));
        })));
    }

    private PreflightContext ctx() {
        return ctx(c -> {});
    }

    private PreflightContext ctx(ContextMutator mutator) {
        PreflightContext.Builder builder = PreflightContext.builder()
            .now(1770000000000L)
            .properties(new PreflightProperties())
            .incident(incident())
            .fireEvent(fireEvent())
            .mission(mission())
            .deliveryPrimary(assignment())
            .deviceProperties(deviceProperties())
            .flightApplications(List.of(flightApplication()))
            .takeoffConfirmations(List.of(takeoffConfirmation()))
            .qualifications(validQualifications())
            .activeLeases(List.of(lease(501L)))
            .commandEvents(List.of())
            .boundaryCheck(BoundaryCheckResult.inside())
            .deliveryHubReachable(true)
            .operatorId("operator-1");
        PreflightContext context = builder.build();
        mutator.accept(context);
        return context;
    }

    private com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity incident() {
        com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity incident =
            new com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity();
        incident.setId(501L);
        incident.setFireEventId(10L);
        incident.setCenterLat(22.000500);
        incident.setCenterLng(113.000500);
        return incident;
    }

    private FireEventEntity fireEvent() {
        FireEventEntity fireEvent = new FireEventEntity();
        fireEvent.setId(10L);
        fireEvent.setConfirmedStatus("CONFIRMED");
        fireEvent.setGeoQuality("PRECISE");
        fireEvent.setLat(22.000500);
        fireEvent.setLng(113.000500);
        return fireEvent;
    }

    private FireMissionEntity mission() {
        FireMissionEntity mission = new FireMissionEntity();
        mission.setId(601L);
        mission.setIncidentId(501L);
        mission.setMissionNo("M-001");
        mission.setPayloadType("LIFTING_DUAL_BATTERY");
        mission.setEstimatedTotalWeightKg(80.0);
        mission.setWaterLoadLiters(20.0);
        mission.setTakeoffLat(22.000000);
        mission.setTakeoffLng(113.000000);
        mission.setWindSpeedAtApproval(6.0);
        return mission;
    }

    private OperationAssignmentEntity assignment() {
        OperationAssignmentEntity assignment = new OperationAssignmentEntity();
        assignment.setIncidentId(501L);
        assignment.setRole("DELIVERY_PRIMARY");
        assignment.setResourceSn("FC100-SN-001");
        assignment.setLeaseId(801L);
        return assignment;
    }

    private DeliveryDeviceProperties deviceProperties() {
        DeliveryDeviceProperties properties = new DeliveryDeviceProperties();
        properties.setDeviceSn("FC100-SN-001");
        properties.setOnlineStatus(true);
        properties.setBatteryPercent(80);
        properties.setRtkStatus("FIX");
        properties.setWindSpeed(6.0);
        return properties;
    }

    private OperationFlightApplicationRecordEntity flightApplication() {
        OperationFlightApplicationRecordEntity record = new OperationFlightApplicationRecordEntity();
        record.setIncidentId(501L);
        record.setApplicationNo("FA-001");
        record.setApprovalNo("APPROVAL-001");
        record.setValidFrom(1769900000000L);
        record.setValidTo(1770100000000L);
        return record;
    }

    private OperationTakeoffConfirmationEntity takeoffConfirmation() {
        OperationTakeoffConfirmationEntity record = new OperationTakeoffConfirmationEntity();
        record.setIncidentId(501L);
        record.setOperatorId("operator-1");
        record.setConfirmedAt(1769999990000L);
        return record;
    }

    private List<OperationQualificationEntity> validQualifications() {
        return List.of(qualification("CLUSTER_FLIGHT_PERMIT"), qualification("AIRDROP_APPROVAL"),
            qualification("AIRWORTHINESS"), qualification("JOINT_OPERATION_AGREEMENT"));
    }

    private List<OperationQualificationEntity> validBaseQualificationsWithoutAirdrop() {
        return List.of(qualification("CLUSTER_FLIGHT_PERMIT"), qualification("AIRWORTHINESS"),
            qualification("JOINT_OPERATION_AGREEMENT"));
    }

    private OperationQualificationEntity qualification(String type) {
        OperationQualificationEntity qualification = new OperationQualificationEntity();
        qualification.setQualificationType(type);
        qualification.setQualificationNo(type + "-001");
        qualification.setIssuer("authority");
        qualification.setValidFrom(1769900000000L);
        qualification.setValidTo(1770100000000L);
        qualification.setStatus("VALID");
        return qualification;
    }

    private OperationResourceLeaseEntity lease(Long ownerId) {
        OperationResourceLeaseEntity lease = new OperationResourceLeaseEntity();
        lease.setResourceSn("FC100-SN-001");
        lease.setLeaseType("DELIVERY_PRIMARY");
        lease.setOwnerType("INCIDENT");
        lease.setOwnerId(ownerId);
        lease.setStatus("ACTIVE");
        lease.setExpiresAt(1770100000000L);
        return lease;
    }

    private OperationCommandEventEntity command(String type, String status) {
        OperationCommandEventEntity command = new OperationCommandEventEntity();
        command.setTargetSn("FC100-SN-001");
        command.setCommandType(type);
        command.setStatus(status);
        return command;
    }

    private void assertPassed(RuleCheckResult result) {
        assertEquals(RuleStatus.PASS, result.getStatus(), result.toString());
    }

    private void assertBlocked(RuleCheckResult result) {
        assertEquals(RuleStatus.BLOCK, result.getStatus(), result.toString());
    }

    private void assertWarn(RuleCheckResult result) {
        assertEquals(RuleStatus.WARN, result.getStatus(), result.toString());
    }

    private interface ContextMutator {
        void accept(PreflightContext context);
    }
}
