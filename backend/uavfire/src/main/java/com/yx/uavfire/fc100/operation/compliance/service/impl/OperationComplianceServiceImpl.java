package com.yx.uavfire.fc100.operation.compliance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationFlightApplicationRecordMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationLandingReportMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationQualificationMapper;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationTakeoffConfirmationMapper;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationFlightApplicationRecordEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationLandingReportEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationQualificationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationTakeoffConfirmationEntity;
import com.yx.uavfire.fc100.operation.compliance.model.param.*;
import com.yx.uavfire.fc100.operation.compliance.service.OperationComplianceService;
import com.yx.uavfire.fc100.operation.preflight.PreflightExecutionService;
import com.yx.uavfire.fc100.operation.preflight.PreflightResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OperationComplianceServiceImpl implements OperationComplianceService {
    private final PreflightExecutionService preflightExecutionService;
    private final OperationFlightApplicationRecordMapper flightApplicationMapper;
    private final OperationTakeoffConfirmationMapper takeoffConfirmationMapper;
    private final OperationLandingReportMapper landingReportMapper;
    private final OperationQualificationMapper qualificationMapper;
    private final Clock clock;

    public OperationComplianceServiceImpl(PreflightExecutionService preflightExecutionService,
                                          OperationFlightApplicationRecordMapper flightApplicationMapper,
                                          OperationTakeoffConfirmationMapper takeoffConfirmationMapper,
                                          OperationLandingReportMapper landingReportMapper,
                                          OperationQualificationMapper qualificationMapper,
                                          Clock clock) {
        this.preflightExecutionService = preflightExecutionService;
        this.flightApplicationMapper = flightApplicationMapper;
        this.takeoffConfirmationMapper = takeoffConfirmationMapper;
        this.landingReportMapper = landingReportMapper;
        this.qualificationMapper = qualificationMapper;
        this.clock = clock;
    }

    @Override
    public PreflightResult runPreflight(PreflightCheckParam param) {
        return preflightExecutionService.runByIncident(param.getIncidentId(), param.getOperatorId());
    }

    @Override
    public PreflightResult getPreflight(Long id) {
        return preflightExecutionService.get(id);
    }

    @Override
    @Transactional
    public OperationFlightApplicationRecordEntity recordFlightApplication(RecordFlightApplicationParam param) {
        long now = clock.now();
        OperationFlightApplicationRecordEntity entity = new OperationFlightApplicationRecordEntity();
        entity.setIncidentId(param.getIncidentId());
        entity.setApplicationNo(param.getApplicationNo());
        entity.setApprovalNo(param.getApprovalNo());
        entity.setValidFrom(param.getValidFrom());
        entity.setValidTo(param.getValidTo());
        entity.setMaterialUrl(param.getMaterialUrl());
        entity.setOperatorId(param.getOperatorId());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        flightApplicationMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public OperationTakeoffConfirmationEntity recordTakeoffConfirmation(RecordTakeoffConfirmationParam param) {
        long now = clock.now();
        OperationTakeoffConfirmationEntity entity = new OperationTakeoffConfirmationEntity();
        entity.setIncidentId(param.getIncidentId());
        entity.setOperatorId(param.getOperatorId());
        entity.setConfirmationNo(param.getConfirmationNo());
        entity.setMaterialUrl(param.getMaterialUrl());
        entity.setConfirmedAt(param.getConfirmedAt() == null ? now : param.getConfirmedAt());
        entity.setCreateTime(now);
        takeoffConfirmationMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public OperationLandingReportEntity recordLandingReport(RecordLandingReportParam param) {
        long now = clock.now();
        OperationLandingReportEntity entity = new OperationLandingReportEntity();
        entity.setIncidentId(param.getIncidentId());
        entity.setReportNo(param.getReportNo());
        entity.setMaterialUrl(param.getMaterialUrl());
        entity.setOperatorId(param.getOperatorId());
        entity.setLandedAt(param.getLandedAt() == null ? now : param.getLandedAt());
        entity.setCreateTime(now);
        landingReportMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public OperationQualificationEntity createQualification(QualificationParam param) {
        long now = clock.now();
        OperationQualificationEntity entity = new OperationQualificationEntity();
        entity.setQualificationType(param.getQualificationType());
        entity.setQualificationNo(param.getQualificationNo());
        entity.setIssuer(param.getIssuer());
        entity.setValidFrom(param.getValidFrom());
        entity.setValidTo(param.getValidTo());
        entity.setMaterialUrl(param.getMaterialUrl());
        entity.setStatus(param.getStatus() == null || param.getStatus().isBlank() ? "VALID" : param.getStatus());
        entity.setOperatorId(param.getOperatorId());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        qualificationMapper.insert(entity);
        return entity;
    }

    @Override
    public List<OperationQualificationEntity> listQualifications(String type) {
        QueryWrapper<OperationQualificationEntity> query = new QueryWrapper<>();
        if (type != null && !type.isBlank()) {
            query.eq("qualification_type", type);
        }
        query.orderByDesc("create_time");
        return qualificationMapper.selectList(query);
    }
}
