package com.yx.uavfire.fc100.operation.preflight;

import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import com.yx.uavfire.fc100.operation.preflight.boundary.BoundaryCheckResult;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class PreflightContext {
    private OperationIncidentEntity incident;
    private FireEventEntity fireEvent;
    private FireMissionEntity mission;
    private OperationAssignmentEntity deliveryPrimary;
    private DeliveryDeviceProperties deviceProperties;
    private List<OperationFlightApplicationRecordEntity> flightApplications = new ArrayList<>();
    private List<OperationTakeoffConfirmationEntity> takeoffConfirmations = new ArrayList<>();
    private List<OperationQualificationEntity> qualifications = new ArrayList<>();
    private List<OperationResourceLeaseEntity> activeLeases = new ArrayList<>();
    private List<OperationCommandEventEntity> commandEvents = new ArrayList<>();
    private BoundaryCheckResult boundaryCheck = BoundaryCheckResult.noReferenceLayer();
    private boolean deliveryHubReachable;
    private String operatorId;
    private long now;
    private PreflightProperties properties = new PreflightProperties();

    public OperationIncidentEntity incident() { return incident; }
    public FireEventEntity fireEvent() { return fireEvent; }
    public FireMissionEntity mission() { return mission; }
    public OperationAssignmentEntity deliveryPrimary() { return deliveryPrimary; }
    public DeliveryDeviceProperties deviceProperties() { return deviceProperties; }
    public PreflightProperties properties() { return properties; }
    public long now() { return now; }
    public String operatorId() { return operatorId; }
    public boolean deliveryHubReachable() { return deliveryHubReachable; }
    public BoundaryCheckResult boundaryCheck() { return boundaryCheck; }

    public void flightApplications(List<OperationFlightApplicationRecordEntity> records) {
        this.flightApplications = records == null ? List.of() : records;
    }

    public void takeoffConfirmations(List<OperationTakeoffConfirmationEntity> records) {
        this.takeoffConfirmations = records == null ? List.of() : records;
    }

    public void qualifications(List<OperationQualificationEntity> records) {
        this.qualifications = records == null ? List.of() : records;
    }

    public void activeLeases(List<OperationResourceLeaseEntity> records) {
        this.activeLeases = records == null ? List.of() : records;
    }

    public void commandEvents(List<OperationCommandEventEntity> records) {
        this.commandEvents = records == null ? List.of() : records;
    }

    public void boundaryCheck(BoundaryCheckResult result) {
        this.boundaryCheck = result;
    }

    public void deliveryHubReachable(boolean reachable) {
        this.deliveryHubReachable = reachable;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final PreflightContext context = new PreflightContext();

        public Builder incident(OperationIncidentEntity incident) { context.incident = incident; return this; }
        public Builder fireEvent(FireEventEntity fireEvent) { context.fireEvent = fireEvent; return this; }
        public Builder mission(FireMissionEntity mission) { context.mission = mission; return this; }
        public Builder deliveryPrimary(OperationAssignmentEntity deliveryPrimary) { context.deliveryPrimary = deliveryPrimary; return this; }
        public Builder deviceProperties(DeliveryDeviceProperties deviceProperties) { context.deviceProperties = deviceProperties; return this; }
        public Builder flightApplications(List<OperationFlightApplicationRecordEntity> records) { context.flightApplications(records); return this; }
        public Builder takeoffConfirmations(List<OperationTakeoffConfirmationEntity> records) { context.takeoffConfirmations(records); return this; }
        public Builder qualifications(List<OperationQualificationEntity> records) { context.qualifications(records); return this; }
        public Builder activeLeases(List<OperationResourceLeaseEntity> records) { context.activeLeases(records); return this; }
        public Builder commandEvents(List<OperationCommandEventEntity> records) { context.commandEvents(records); return this; }
        public Builder boundaryCheck(BoundaryCheckResult boundaryCheck) { context.boundaryCheck = boundaryCheck; return this; }
        public Builder deliveryHubReachable(boolean deliveryHubReachable) { context.deliveryHubReachable = deliveryHubReachable; return this; }
        public Builder operatorId(String operatorId) { context.operatorId = operatorId; return this; }
        public Builder now(long now) { context.now = now; return this; }
        public Builder properties(PreflightProperties properties) { context.properties = properties == null ? new PreflightProperties() : properties; return this; }
        public PreflightContext build() { return context; }
    }
}
