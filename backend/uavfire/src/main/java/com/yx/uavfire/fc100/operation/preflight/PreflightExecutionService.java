package com.yx.uavfire.fc100.operation.preflight;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.operation.compliance.dao.OperationComplianceRecordMapper;
import com.yx.uavfire.fc100.operation.compliance.model.entity.OperationComplianceRecordEntity;
import com.yx.uavfire.fc100.operation.dao.OperationAssignmentMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentRole;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PreflightExecutionService {
    private final OperationIncidentMapper incidentMapper;
    private final OperationAssignmentMapper assignmentMapper;
    private final OperationComplianceRecordMapper recordMapper;
    private final PreflightContextFactory contextFactory;
    private final PreflightRuleEngine engine;
    private final ObjectMapper objectMapper;

    public PreflightExecutionService(OperationIncidentMapper incidentMapper,
                                     OperationAssignmentMapper assignmentMapper,
                                     OperationComplianceRecordMapper recordMapper,
                                     PreflightContextFactory contextFactory,
                                     PreflightRuleEngine engine,
                                     ObjectMapper objectMapper) {
        this.incidentMapper = incidentMapper;
        this.assignmentMapper = assignmentMapper;
        this.recordMapper = recordMapper;
        this.contextFactory = contextFactory;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PreflightResult run(OperationIncidentEntity incident, OperationAssignmentEntity assignment, String operatorId) {
        PreflightContext context = contextFactory.create(incident, assignment, operatorId);
        PreflightResult result = engine.evaluate(context);
        save(result);
        return result;
    }

    @Transactional
    public PreflightResult runByIncident(Long incidentId, String operatorId) {
        OperationIncidentEntity incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INCIDENT_NOT_FOUND,
                "operation incident not found: " + incidentId);
        }
        OperationAssignmentEntity assignment = assignmentMapper.selectOne(new QueryWrapper<OperationAssignmentEntity>()
            .eq("incident_id", incidentId)
            .eq("role", OperationAssignmentRole.DELIVERY_PRIMARY.name())
            .eq("status", OperationAssignmentStatus.ACTIVE.name())
            .orderByAsc("assigned_at")
            .last("limit 1"));
        PreflightResult result = run(incident, assignment, operatorId);
        return result;
    }

    public PreflightResult get(Long id) {
        OperationComplianceRecordEntity record = recordMapper.selectById(id);
        if (record == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "preflight record not found: " + id);
        }
        PreflightResult result = new PreflightResult();
        result.setId(record.getId());
        result.setIncidentId(record.getIncidentId());
        result.setOperatorId(record.getOperatorId());
        result.setStatus(PreflightStatus.valueOf(record.getStatus()));
        result.setCreateTime(record.getCreateTime());
        try {
            result.setItems(objectMapper.readValue(record.getDetailsJson(), new TypeReference<List<RuleCheckResult>>() {}));
        } catch (Exception ex) {
            result.setItems(List.of());
        }
        return result;
    }

    private void save(PreflightResult result) {
        OperationComplianceRecordEntity record = new OperationComplianceRecordEntity();
        record.setIncidentId(result.getIncidentId());
        record.setRecordType("PREFLIGHT_CHECK");
        record.setStatus(result.getStatus().name());
        record.setOperatorId(result.getOperatorId());
        record.setCreateTime(result.getCreateTime());
        try {
            record.setBlockingItemsJson(objectMapper.writeValueAsString(result.blockingItems()));
            record.setDetailsJson(objectMapper.writeValueAsString(result.getItems()));
        } catch (JsonProcessingException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INTERNAL_ERROR, "failed to serialize preflight result");
        }
        recordMapper.insert(record);
        result.setId(record.getId());
    }
}
