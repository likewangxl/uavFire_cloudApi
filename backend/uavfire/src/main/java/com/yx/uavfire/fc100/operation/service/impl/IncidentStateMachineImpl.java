package com.yx.uavfire.fc100.operation.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentLogMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentLogEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.statemachine.IncidentTransitionTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class IncidentStateMachineImpl implements IncidentStateMachine {

    private final OperationIncidentMapper incidentMapper;
    private final OperationIncidentLogMapper logMapper;
    private final Clock clock;

    public IncidentStateMachineImpl(OperationIncidentMapper incidentMapper,
                                    OperationIncidentLogMapper logMapper,
                                    Clock clock) {
        this.incidentMapper = incidentMapper;
        this.logMapper = logMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OperationIncidentEntity transit(IncidentTransitCommand cmd) {
        OperationIncidentEntity incident = incidentMapper.selectById(cmd.getIncidentId());
        if (incident == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INCIDENT_NOT_FOUND,
                "operation incident not found: " + cmd.getIncidentId());
        }
        OperationIncidentStatus from = OperationIncidentStatus.valueOf(incident.getStatus());
        if (cmd.getExpectedFrom() != null && cmd.getExpectedFrom() != from) {
            auditDenied(cmd, incident, from,
                "denied: expected " + cmd.getExpectedFrom() + " but actual " + from);
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "expected " + cmd.getExpectedFrom() + " but actual " + from);
        }

        Optional<OperationIncidentStatus> next = IncidentTransitionTable.nextStatus(from, cmd.getEvent());
        if (next.isEmpty()) {
            auditDenied(cmd, incident, from,
                "denied: event " + cmd.getEvent() + " not allowed from " + from);
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "event " + cmd.getEvent() + " not allowed from " + from);
        }

        OperationIncidentStatus to = next.get();
        long now = clock.now();
        UpdateWrapper<OperationIncidentEntity> update = new UpdateWrapper<>();
        update.set("status", to.name())
            .set("update_time", now)
            .eq("id", incident.getId())
            .eq("status", from.name());
        if (closesIncident(to)) {
            update.set("closed_at", now);
        }

        int rows = incidentMapper.update(null, update);
        if (rows == 0) {
            throw new Fc100BusinessException(Fc100ErrorCode.VERSION_MISMATCH,
                "concurrent modification on operation incident " + incident.getId());
        }

        audit(cmd, incident.getId(), from.name(), to.name(), cmd.getRemark(), now);
        incident.setStatus(to.name());
        incident.setUpdateTime(now);
        if (closesIncident(to)) {
            incident.setClosedAt(now);
        }
        log.info("transit operationIncident={} {} -> {} via {}",
            incident.getId(), from, to, cmd.getEvent());
        return incident;
    }

    @Override
    public Set<OperationIncidentEvent> allowedEvents(OperationIncidentStatus current) {
        return IncidentTransitionTable.allowedEvents(current);
    }

    private boolean closesIncident(OperationIncidentStatus status) {
        return status == OperationIncidentStatus.FALSE_ALARM
            || status == OperationIncidentStatus.ABORTED
            || status == OperationIncidentStatus.RESOLVED
            || status == OperationIncidentStatus.ARCHIVED;
    }

    private void auditDenied(IncidentTransitCommand cmd, OperationIncidentEntity incident,
                             OperationIncidentStatus from, String reason) {
        String remark = reason;
        if (cmd.getRemark() != null && !cmd.getRemark().isBlank()) {
            remark = remark + "; " + cmd.getRemark();
        }
        audit(cmd, incident.getId(), from.name(), null, remark, clock.now());
    }

    private void audit(IncidentTransitCommand cmd, Long incidentId, String from, String to, String remark, long now) {
        OperationIncidentLogEntity log = new OperationIncidentLogEntity();
        log.setIncidentId(incidentId);
        log.setAction(cmd.getEvent().name());
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(cmd.getOperatorId());
        log.setOperatorRole(cmd.getOperatorRole());
        log.setClientIp(cmd.getClientIp());
        log.setRequestId(cmd.getRequestId());
        log.setIdempotencyKey(cmd.getIdempotencyKey());
        log.setRemark(remark);
        log.setCreateTime(now);
        logMapper.insert(log);
    }
}
