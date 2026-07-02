package com.yx.uavfire.fc100.operation;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.operation.compliance.controller.OperationComplianceController;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.param.PreflightCheckParam;
import com.yx.uavfire.fc100.operation.compliance.model.param.QualificationParam;
import com.yx.uavfire.fc100.operation.compliance.service.OperationComplianceService;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationComplianceControllerTest {

    @Test
    void runPreflightDelegatesAndReturnsResult() {
        OperationComplianceService service = mock(OperationComplianceService.class);
        OperationComplianceController controller = new OperationComplianceController(service);
        PreflightCheckParam param = new PreflightCheckParam();
        param.setIncidentId(501L);
        param.setOperatorId("operator-1");
        PreflightResult result = PreflightResult.from(501L, "operator-1", List.of(), 1770000000000L);
        when(service.runPreflight(param)).thenReturn(result);

        ApiResult<PreflightResult> response = controller.runPreflight(param);

        assertEquals(0, response.getCode());
        assertEquals(501L, response.getData().getIncidentId());
        verify(service).runPreflight(param);
    }

    @Test
    void createQualificationDelegatesAndReturnsRecord() {
        OperationComplianceService service = mock(OperationComplianceService.class);
        OperationComplianceController controller = new OperationComplianceController(service);
        QualificationParam param = new QualificationParam();
        param.setQualificationType("CLUSTER_FLIGHT_PERMIT");
        param.setQualificationNo("Q-001");
        param.setOperatorId("operator-1");
        OperationQualificationEntity entity = new OperationQualificationEntity();
        entity.setQualificationType("CLUSTER_FLIGHT_PERMIT");
        when(service.createQualification(param)).thenReturn(entity);

        ApiResult<OperationQualificationEntity> response = controller.createQualification(param);

        assertEquals("CLUSTER_FLIGHT_PERMIT", response.getData().getQualificationType());
        verify(service).createQualification(param);
    }
}
