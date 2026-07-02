package com.yx.uavfire.fc100.operation.service;

import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import org.springframework.stereotype.Service;

public interface PreflightGate {

    void check(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary);

    @Service
    class NoopPreflightGate implements PreflightGate {
        @Override
        public void check(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary) {
            // TODO S5: plug compliance, airspace, weather, and release-policy gates here.
        }
    }
}
