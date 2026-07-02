package com.yx.uavfire.fc100.operation.compliance.controller;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationLandingReportEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.param.*;
import com.yx.uavfire.fc100.operation.compliance.service.OperationComplianceService;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * Compliance endpoints record manually reported approvals and confirmations only.
 * They do not call UOM/USS or any external airspace service automatically.
 */
@RestController
@RequestMapping("/api/compliance")
public class OperationComplianceController {
    private final OperationComplianceService service;

    public OperationComplianceController(OperationComplianceService service) {
        this.service = service;
    }

    @PostMapping("/preflight-checks")
    public ApiResult<PreflightResult> runPreflight(@Valid @RequestBody PreflightCheckParam param) {
        return ApiResult.success(service.runPreflight(param));
    }

    @GetMapping("/preflight-checks/{id}")
    public ApiResult<PreflightResult> getPreflight(@PathVariable("id") Long id) {
        return ApiResult.success(service.getPreflight(id));
    }

    @PostMapping("/record-flight-application")
    public ApiResult<OperationFlightApplicationRecordEntity> recordFlightApplication(
            @Valid @RequestBody RecordFlightApplicationParam param) {
        return ApiResult.success(service.recordFlightApplication(param));
    }

    @PostMapping("/record-takeoff-confirmation")
    public ApiResult<OperationTakeoffConfirmationEntity> recordTakeoffConfirmation(
            @Valid @RequestBody RecordTakeoffConfirmationParam param) {
        return ApiResult.success(service.recordTakeoffConfirmation(param));
    }

    @PostMapping("/record-landing-report")
    public ApiResult<OperationLandingReportEntity> recordLandingReport(
            @Valid @RequestBody RecordLandingReportParam param) {
        return ApiResult.success(service.recordLandingReport(param));
    }

    @PostMapping("/qualifications")
    public ApiResult<OperationQualificationEntity> createQualification(@Valid @RequestBody QualificationParam param) {
        return ApiResult.success(service.createQualification(param));
    }

    @GetMapping("/qualifications")
    public ApiResult<List<OperationQualificationEntity>> listQualifications(
            @RequestParam(value = "type", required = false) String type) {
        return ApiResult.success(service.listQualifications(type));
    }
}
