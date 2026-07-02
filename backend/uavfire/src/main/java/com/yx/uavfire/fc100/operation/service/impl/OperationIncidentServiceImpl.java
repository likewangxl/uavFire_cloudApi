package com.yx.uavfire.fc100.operation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.operation.dao.OperationAssignmentMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentLogMapper;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDetailDTO;
import com.yx.uavfire.fc100.operation.model.dto.OperationTimelineItem;
import com.yx.uavfire.fc100.operation.model.entity.OperationAssignmentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentLogEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentRole;
import com.yx.uavfire.fc100.operation.model.enums.OperationAssignmentStatus;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.model.param.AssignOperationResourceParam;
import com.yx.uavfire.fc100.operation.model.param.CreateOperationIncidentParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.service.IncidentNoGenerator;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import com.yx.uavfire.fc100.operation.service.PreflightGate;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OperationIncidentServiceImpl implements OperationIncidentService {

    private static final Set<String> ACTIVE_INCIDENT_STATUSES = Set.of(
        OperationIncidentStatus.CANDIDATE.name(),
        OperationIncidentStatus.CONFIRMED.name(),
        OperationIncidentStatus.DISPATCHING.name(),
        OperationIncidentStatus.RESPONDING.name(),
        OperationIncidentStatus.RECHECKING.name(),
        OperationIncidentStatus.RESOLVED.name()
    );

    private final OperationIncidentMapper incidentMapper;
    private final OperationAssignmentMapper assignmentMapper;
    private final OperationIncidentLogMapper logMapper;
    private final FireEventMapper fireEventMapper;
    private final FireMissionMapper fireMissionMapper;
    private final IncidentStateMachine stateMachine;
    private final IncidentNoGenerator noGenerator;
    private final PreflightGate preflightGate;
    private final Clock clock;

    public OperationIncidentServiceImpl(OperationIncidentMapper incidentMapper,
                                        OperationAssignmentMapper assignmentMapper,
                                        OperationIncidentLogMapper logMapper,
                                        FireEventMapper fireEventMapper,
                                        FireMissionMapper fireMissionMapper,
                                        IncidentStateMachine stateMachine,
                                        IncidentNoGenerator noGenerator,
                                        PreflightGate preflightGate,
                                        Clock clock) {
        this.incidentMapper = incidentMapper;
        this.assignmentMapper = assignmentMapper;
        this.logMapper = logMapper;
        this.fireEventMapper = fireEventMapper;
        this.fireMissionMapper = fireMissionMapper;
        this.stateMachine = stateMachine;
        this.noGenerator = noGenerator;
        this.preflightGate = preflightGate;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OperationIncidentDTO create(CreateOperationIncidentParam param) {
        FireEventEntity fireEvent = fireEventMapper.selectById(param.getFireEventId());
        if (fireEvent == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "fire event not found: " + param.getFireEventId());
        }
        if (!"CONFIRMED".equalsIgnoreCase(fireEvent.getConfirmedStatus())) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "fire event is not confirmed: " + param.getFireEventId());
        }
        List<OperationIncidentEntity> active = incidentMapper.selectList(new QueryWrapper<OperationIncidentEntity>()
            .eq("fire_event_id", param.getFireEventId())
            .in("status", ACTIVE_INCIDENT_STATUSES));
        if (active != null && !active.isEmpty()) {
            throw new Fc100BusinessException(Fc100ErrorCode.DUPLICATE_FROM_EVENT,
                "active operation incident already exists for fire event: " + param.getFireEventId());
        }

        long now = clock.now();
        OperationIncidentEntity incident = new OperationIncidentEntity();
        incident.setIncidentNo(noGenerator.next());
        incident.setFireEventId(fireEvent.getId());
        incident.setLevel(firstNonBlank(param.getLevel(), firstNonBlank(fireEvent.getFireLevel(), "UNKNOWN")));
        incident.setStatus(OperationIncidentStatus.CONFIRMED.name());
        incident.setCenterLat(param.getCenterLat() != null ? param.getCenterLat() : fireEvent.getLat());
        incident.setCenterLng(param.getCenterLng() != null ? param.getCenterLng() : fireEvent.getLng());
        incident.setRiskRadiusM(param.getRiskRadiusM() != null ? param.getRiskRadiusM() : fireEvent.getGeoErrorRadiusM());
        incident.setCreatedBy(param.getCreatedBy());
        incident.setConfirmedBy(param.getConfirmedBy());
        incident.setCreateTime(now);
        incident.setUpdateTime(now);
        incidentMapper.insert(incident);

        fireEvent.setLinkedIncidentId(incident.getId());
        fireEvent.setConfirmedStatus("CONFIRMED");
        fireEvent.setUpdateTime(now);
        fireEventMapper.updateById(fireEvent);
        linkDraftMissions(fireEvent.getId(), incident.getId(), now);
        audit(incident.getId(), "CREATE", null, OperationIncidentStatus.CONFIRMED.name(),
            param.getCreatedBy(), null, "created from fire_event " + fireEvent.getId(), now);
        return toDto(incident);
    }

    @Override
    public List<OperationIncidentDTO> list(String status, String level, int page, int size) {
        QueryWrapper<OperationIncidentEntity> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        if (level != null && !level.isBlank()) {
            query.eq("level", level);
        }
        query.orderByDesc("create_time");
        Page<OperationIncidentEntity> result = incidentMapper.selectPage(new Page<>(page, size), query);
        return result.getRecords().stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public OperationIncidentDetailDTO detail(Long id) {
        OperationIncidentEntity incident = requireIncident(id);
        OperationIncidentDetailDTO detail = new OperationIncidentDetailDTO();
        BeanUtils.copyProperties(incident, detail);
        detail.setAssignments(assignmentsByIncident(id));
        detail.setTimeline(timeline(id));
        return detail;
    }

    @Override
    public List<OperationTimelineItem> timeline(Long id) {
        List<OperationTimelineItem> items = new ArrayList<>();
        List<OperationIncidentLogEntity> logs = logMapper.selectList(new QueryWrapper<OperationIncidentLogEntity>()
            .eq("incident_id", id));
        if (logs != null) {
            for (OperationIncidentLogEntity log : logs) {
                OperationTimelineItem item = new OperationTimelineItem();
                item.setType("STATUS");
                item.setAction(log.getAction());
                item.setFromStatus(log.getFromStatus());
                item.setToStatus(log.getToStatus());
                item.setOperatorId(log.getOperatorId());
                item.setDescription(log.getRemark());
                item.setCreateTime(log.getCreateTime());
                items.add(item);
            }
        }
        List<OperationAssignmentEntity> assignments = assignmentsByIncident(id);
        for (OperationAssignmentEntity assignment : assignments) {
            OperationTimelineItem item = new OperationTimelineItem();
            item.setType("ASSIGNMENT");
            item.setAction("ASSIGN_" + assignment.getRole());
            item.setResourceSn(assignment.getResourceSn());
            item.setRole(assignment.getRole());
            item.setStatus(assignment.getStatus());
            item.setDescription(assignment.getRole() + " assigned to " + assignment.getResourceSn());
            item.setCreateTime(assignment.getAssignedAt());
            items.add(item);
        }
        items.sort(Comparator.comparing(OperationTimelineItem::getCreateTime,
            Comparator.nullsLast(Long::compareTo)));
        return items;
    }

    @Override
    @Transactional
    public OperationAssignmentEntity assignMonitor(Long id, AssignOperationResourceParam param) {
        OperationAssignmentRole role = parseRole(param.getRole());
        if (role != OperationAssignmentRole.MONITOR_PRIMARY && role != OperationAssignmentRole.MONITOR_RECHECK) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "monitor role required: " + param.getRole());
        }
        return assign(id, param, role, "ASSIGN_MONITOR");
    }

    @Override
    @Transactional
    public OperationAssignmentEntity assignDelivery(Long id, AssignOperationResourceParam param) {
        OperationAssignmentRole role = parseRole(param.getRole());
        if (role != OperationAssignmentRole.DELIVERY_PRIMARY && role != OperationAssignmentRole.DELIVERY_BACKUP) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "delivery role required: " + param.getRole());
        }
        requireIncident(id);
        if (role == OperationAssignmentRole.DELIVERY_PRIMARY) {
            List<OperationAssignmentEntity> activePrimary = assignmentMapper.selectList(new QueryWrapper<OperationAssignmentEntity>()
                .eq("incident_id", id)
                .eq("role", OperationAssignmentRole.DELIVERY_PRIMARY.name())
                .eq("status", OperationAssignmentStatus.ACTIVE.name()));
            if (activePrimary != null && !activePrimary.isEmpty()) {
                throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                    "active DELIVERY_PRIMARY already exists for incident: " + id);
            }
        }
        return assign(id, param, role, "ASSIGN_DELIVERY");
    }

    @Override
    @Transactional
    public OperationIncidentEntity dispatch(Long id, OperationActionParam param, HttpServletRequest req) {
        OperationIncidentEntity incident = requireIncident(id);
        if (!OperationIncidentStatus.CONFIRMED.name().equals(incident.getStatus())) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "dispatch requires CONFIRMED incident");
        }
        OperationAssignmentEntity primary = activeDeliveryPrimary(id);
        if (primary == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "dispatch requires active DELIVERY_PRIMARY assignment");
        }
        preflightGate.check(incident, primary);
        stateMachine.transit(cmd(id, OperationIncidentEvent.DISPATCH, param, req)
            .expectedFrom(OperationIncidentStatus.CONFIRMED)
            .build());
        return stateMachine.transit(cmd(id, OperationIncidentEvent.RESPOND, param, req)
            .expectedFrom(OperationIncidentStatus.DISPATCHING)
            .build());
    }

    @Override
    @Transactional
    public OperationIncidentEntity markFalseAlarm(Long id, OperationActionParam param, HttpServletRequest req) {
        return stateMachine.transit(cmd(id, OperationIncidentEvent.MARK_FALSE_ALARM, param, req)
            .remark(param.getReason())
            .build());
    }

    @Override
    @Transactional
    public OperationIncidentEntity abort(Long id, OperationActionParam param, HttpServletRequest req) {
        requireReason(param, "abort requires reason");
        return stateMachine.transit(cmd(id, OperationIncidentEvent.ABORT, param, req)
            .remark(param.getReason())
            .build());
    }

    @Override
    @Transactional
    public OperationIncidentEntity close(Long id, OperationActionParam param, HttpServletRequest req) {
        OperationIncidentEntity incident = requireIncident(id);
        if (!OperationIncidentStatus.RESOLVED.name().equals(incident.getStatus())
            && !OperationIncidentStatus.FALSE_ALARM.name().equals(incident.getStatus())
            && !OperationIncidentStatus.ABORTED.name().equals(incident.getStatus())) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "close requires RESOLVED, FALSE_ALARM, or ABORTED incident");
        }
        return stateMachine.transit(cmd(id, OperationIncidentEvent.ARCHIVE, param, req)
            .remark(param.getReason())
            .build());
    }

    private OperationAssignmentEntity assign(Long id, AssignOperationResourceParam param,
                                             OperationAssignmentRole role, String action) {
        OperationIncidentEntity incident = requireIncident(id);
        if (!ACTIVE_INCIDENT_STATUSES.contains(incident.getStatus())) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "incident is not active: " + id);
        }
        long now = clock.now();
        OperationAssignmentEntity assignment = new OperationAssignmentEntity();
        assignment.setIncidentId(id);
        assignment.setResourceSn(param.getResourceSn());
        assignment.setRole(role.name());
        assignment.setStatus(OperationAssignmentStatus.ACTIVE.name());
        assignment.setAssignedAt(now);
        assignmentMapper.insert(assignment);
        audit(id, action, incident.getStatus(), incident.getStatus(), param.getOperatorId(), null,
            role.name() + " -> " + param.getResourceSn()
                + (param.getRemark() == null ? "" : "; " + param.getRemark()), now);
        return assignment;
    }

    private void linkDraftMissions(Long fireEventId, Long incidentId, long now) {
        UpdateWrapper<FireMissionEntity> update = new UpdateWrapper<>();
        update.set("incident_id", incidentId)
            .set("update_time", now)
            .eq("fire_event_id", fireEventId)
            .eq("deleted", 0)
            .isNull("incident_id")
            .in("status", List.of(
                FireMissionStatus.CREATED.name(),
                FireMissionStatus.WAITING_REVIEW.name()));
        fireMissionMapper.update(null, update);
    }

    private OperationIncidentEntity requireIncident(Long id) {
        OperationIncidentEntity incident = incidentMapper.selectById(id);
        if (incident == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INCIDENT_NOT_FOUND,
                "operation incident not found: " + id);
        }
        return incident;
    }

    private List<OperationAssignmentEntity> assignmentsByIncident(Long id) {
        List<OperationAssignmentEntity> assignments = assignmentMapper.selectList(new QueryWrapper<OperationAssignmentEntity>()
            .eq("incident_id", id)
            .orderByAsc("assigned_at")
            .orderByAsc("id"));
        return assignments == null ? List.of() : assignments;
    }

    private OperationAssignmentEntity activeDeliveryPrimary(Long id) {
        List<OperationAssignmentEntity> assignments = assignmentMapper.selectList(new QueryWrapper<OperationAssignmentEntity>()
            .eq("incident_id", id)
            .eq("role", OperationAssignmentRole.DELIVERY_PRIMARY.name())
            .eq("status", OperationAssignmentStatus.ACTIVE.name())
            .orderByAsc("assigned_at")
            .last("limit 1"));
        return assignments == null || assignments.isEmpty() ? null : assignments.get(0);
    }

    private OperationAssignmentRole parseRole(String role) {
        try {
            return OperationAssignmentRole.valueOf(role);
        } catch (Exception ex) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "invalid assignment role: " + role);
        }
    }

    private void requireReason(OperationActionParam param, String message) {
        if (param == null || param.getReason() == null || param.getReason().isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, message);
        }
    }

    private IncidentTransitCommand.IncidentTransitCommandBuilder cmd(Long id, OperationIncidentEvent event,
                                                                     OperationActionParam param,
                                                                     HttpServletRequest req) {
        return IncidentTransitCommand.builder()
            .incidentId(id)
            .event(event)
            .operatorId(param.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"));
    }

    private void audit(Long incidentId, String action, String from, String to, String operatorId,
                       String operatorRole, String remark, long now) {
        OperationIncidentLogEntity log = new OperationIncidentLogEntity();
        log.setIncidentId(incidentId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(operatorId);
        log.setOperatorRole(operatorRole);
        log.setRemark(remark);
        log.setCreateTime(now);
        logMapper.insert(log);
    }

    private OperationIncidentDTO toDto(OperationIncidentEntity entity) {
        OperationIncidentDTO dto = new OperationIncidentDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    private String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
