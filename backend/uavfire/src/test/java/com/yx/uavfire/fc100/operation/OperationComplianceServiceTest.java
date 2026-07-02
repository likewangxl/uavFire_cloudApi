package com.yx.uavfire.fc100.operation;

import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationFlightApplicationRecordMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationLandingReportMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationQualificationMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationTakeoffConfirmationMapper;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.param.QualificationParam;
import com.yx.uavfire.fc100.operation.compliance.model.param.RecordFlightApplicationParam;
import com.yx.uavfire.fc100.operation.compliance.service.impl.OperationComplianceServiceImpl;
import com.yx.uavfire.fc100.operation.preflight.PreflightExecutionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationComplianceServiceTest {

    @Test
    void recordFlightApplicationStoresOperatorAndApprovalValidity() {
        Fixture f = fixture();
        when(f.flightApplicationMapper.insert(any(OperationFlightApplicationRecordEntity.class))).thenAnswer(inv -> {
            OperationFlightApplicationRecordEntity entity = inv.getArgument(0);
            entity.setId(1L);
            return 1;
        });
        RecordFlightApplicationParam param = new RecordFlightApplicationParam();
        param.setIncidentId(501L);
        param.setApplicationNo("FA-001");
        param.setApprovalNo("APPROVAL-001");
        param.setValidFrom(1769900000000L);
        param.setValidTo(1770100000000L);
        param.setMaterialUrl("https://materials.local/fa-001.pdf");
        param.setOperatorId("operator-1");

        OperationFlightApplicationRecordEntity result = f.service.recordFlightApplication(param);

        assertEquals(1L, result.getId());
        assertEquals("operator-1", result.getOperatorId());
        assertEquals(1770000000000L, result.getCreateTime());
        verify(f.flightApplicationMapper).insert(any(OperationFlightApplicationRecordEntity.class));
    }

    @Test
    void createQualificationDefaultsStatusToValidAndRecordsOperator() {
        Fixture f = fixture();
        when(f.qualificationMapper.insert(any(OperationQualificationEntity.class))).thenAnswer(inv -> {
            OperationQualificationEntity entity = inv.getArgument(0);
            entity.setId(2L);
            return 1;
        });
        QualificationParam param = new QualificationParam();
        param.setQualificationType("AIRWORTHINESS");
        param.setQualificationNo("AW-001");
        param.setIssuer("authority");
        param.setValidTo(1770000000000L);
        param.setOperatorId("operator-2");

        OperationQualificationEntity result = f.service.createQualification(param);

        assertEquals("VALID", result.getStatus());
        assertEquals("operator-2", result.getOperatorId());
        assertEquals(1770000000000L, result.getUpdateTime());
    }

    private Fixture fixture() {
        PreflightExecutionService preflightExecutionService = mock(PreflightExecutionService.class);
        OperationFlightApplicationRecordMapper flightApplicationMapper = mock(OperationFlightApplicationRecordMapper.class);
        OperationTakeoffConfirmationMapper takeoffConfirmationMapper = mock(OperationTakeoffConfirmationMapper.class);
        OperationLandingReportMapper landingReportMapper = mock(OperationLandingReportMapper.class);
        OperationQualificationMapper qualificationMapper = mock(OperationQualificationMapper.class);
        OperationComplianceServiceImpl service = new OperationComplianceServiceImpl(
            preflightExecutionService, flightApplicationMapper, takeoffConfirmationMapper,
            landingReportMapper, qualificationMapper, fixedClock());
        return new Fixture(flightApplicationMapper, qualificationMapper, service);
    }

    private Clock fixedClock() {
        return () -> 1770000000000L;
    }

    private static class Fixture {
        private final OperationFlightApplicationRecordMapper flightApplicationMapper;
        private final OperationQualificationMapper qualificationMapper;
        private final OperationComplianceServiceImpl service;

        private Fixture(OperationFlightApplicationRecordMapper flightApplicationMapper,
                        OperationQualificationMapper qualificationMapper,
                        OperationComplianceServiceImpl service) {
            this.flightApplicationMapper = flightApplicationMapper;
            this.qualificationMapper = qualificationMapper;
            this.service = service;
        }
    }
}
