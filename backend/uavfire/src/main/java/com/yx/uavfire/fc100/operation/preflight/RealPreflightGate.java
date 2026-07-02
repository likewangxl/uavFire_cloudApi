package com.yx.uavfire.fc100.operation.preflight;

import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.service.PreflightGate;
import org.springframework.stereotype.Service;

@Service
public class RealPreflightGate implements PreflightGate {
    private final PreflightExecutionService service;

    public RealPreflightGate(PreflightExecutionService service) {
        this.service = service;
    }

    @Override
    public PreflightResult check(OperationIncidentEntity incident, OperationAssignmentEntity deliveryPrimary,
                                 String operatorId) {
        PreflightResult result = service.run(incident, deliveryPrimary, operatorId);
        if (result.getStatus() == PreflightStatus.BLOCK) {
            throw new PreflightBlockedException(result);
        }
        return result;
    }
}
