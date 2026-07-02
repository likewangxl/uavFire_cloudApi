package com.yx.uavfire.fc100.operation.service;

import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;

public interface PreflightGate {

    default PreflightResult check(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary) {
        return check(incident, deliveryPrimary, null);
    }

    PreflightResult check(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary, String operatorId);

    class NoopPreflightGate implements PreflightGate {
        @Override
        public PreflightResult check(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary,
                                     String operatorId) {
            long now = System.currentTimeMillis();
            return PreflightResult.from(incident == null ? null : incident.getId(), operatorId, java.util.List.of(), now);
        }
    }
}
