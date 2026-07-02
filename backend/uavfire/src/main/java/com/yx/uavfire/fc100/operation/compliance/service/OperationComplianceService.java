package com.yx.uavfire.fc100.operation.compliance.service;

import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationLandingReportEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.param.*;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;

import java.util.List;

public interface OperationComplianceService {
    PreflightResult runPreflight(PreflightCheckParam param);
    PreflightResult getPreflight(Long id);
    OperationFlightApplicationRecordEntity recordFlightApplication(RecordFlightApplicationParam param);
    OperationTakeoffConfirmationEntity recordTakeoffConfirmation(RecordTakeoffConfirmationParam param);
    OperationLandingReportEntity recordLandingReport(RecordLandingReportParam param);
    OperationQualificationEntity createQualification(QualificationParam param);
    List<OperationQualificationEntity> listQualifications(String type);
}
