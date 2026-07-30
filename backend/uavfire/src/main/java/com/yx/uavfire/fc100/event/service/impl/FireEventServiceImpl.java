package com.yx.uavfire.fc100.event.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDecisionResult;
import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.model.dto.FireEventHistoryDTO;
import com.yx.uavfire.fc100.event.model.dto.FireEventRecheckResultDTO;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.enums.FireEventStatus;
import com.yx.uavfire.fc100.event.model.param.FireEventActionParam;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.model.param.FireLaserLocationParam;
import com.yx.uavfire.fc100.event.model.param.FireEventRecheckResultParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import com.yx.uavfire.fc100.event.service.FireGeoLocationResult;
import com.yx.uavfire.fc100.event.service.FireGeoLocationService;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.enums.ReleaseExecutionMode;
import com.yx.uavfire.fc100.mission.model.enums.ReleasePolicy;
import com.yx.uavfire.fc100.operation.dao.OperationIncidentMapper;
import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import com.yx.uavfire.fc100.operation.model.param.CreateOperationIncidentParam;
import com.yx.uavfire.fc100.operation.model.param.OperationActionParam;
import com.yx.uavfire.fc100.operation.service.IncidentStateMachine;
import com.yx.uavfire.fc100.operation.service.IncidentTransitCommand;
import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class FireEventServiceImpl implements FireEventService {

    private static final BigDecimal LOW = new BigDecimal("0.10");
    private static final BigDecimal HIGH = new BigDecimal("0.90");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GEO_METHOD_LASER_RANGEFINDER = "LASER_RANGEFINDER";
    private static final String GEO_QUALITY_PRECISE = "PRECISE";
    private static final String GEO_QUALITY_LASER_LOCATING = "LASER_LOCATING";
    private static final String GEO_QUALITY_LASER_FAILED = "LASER_FAILED";
    private static final String DEDUP_LOCK_PREFIX = "fire_event_dedup:";
    private static final int DEDUP_LOCK_TIMEOUT_SECONDS = 3;

    /** 已绑定活跃任务的 mission 状态集合（用于同 eventId 去重） */
    private static final Set<String> ACTIVE_MISSION_STATUSES = Set.of(
        FireMissionStatus.CREATED.name(),
        FireMissionStatus.WAITING_REVIEW.name(),
        FireMissionStatus.APPROVED.name(),
        FireMissionStatus.ROUTE_GENERATED.name(),
        FireMissionStatus.ROUTE_EXPORTED.name(),
        FireMissionStatus.SENT_TO_DELIVERY.name(),
        FireMissionStatus.ACCEPTED_BY_PILOT.name(),
        FireMissionStatus.IN_PROGRESS.name(),
        FireMissionStatus.PAYLOAD_RELEASE_PENDING.name(),
        FireMissionStatus.PAYLOAD_RELEASED.name(),
        FireMissionStatus.RETURNING.name(),
        FireMissionStatus.REVIEWING.name(),
        FireMissionStatus.MANUAL_TAKEOVER.name(),
        FireMissionStatus.PAYLOAD_RELEASE_FAILED.name(),
        FireMissionStatus.RETURN_FAILED.name()
    );

    private static final Set<String> ACTIVE_INCIDENT_STATUSES = Set.of(
        OperationIncidentStatus.CANDIDATE.name(),
        OperationIncidentStatus.CONFIRMED.name(),
        OperationIncidentStatus.DISPATCHING.name(),
        OperationIncidentStatus.RESPONDING.name(),
        OperationIncidentStatus.RECHECKING.name(),
        OperationIncidentStatus.RESOLVED.name()
    );

    private final FireEventMapper eventMapper;
    private final FireEventHistoryMapper historyMapper;
    private final FireMissionMapper missionMapper;
    private final MissionNoGenerator noGen;
    private final Clock clock;
    private final IDeviceRedisService deviceRedisService;
    private final FireGeoLocationService fireGeoLocationService;
    private final OperationIncidentService operationIncidentService;
    private final OperationIncidentMapper operationIncidentMapper;
    private final IncidentStateMachine incidentStateMachine;
    private final Fc100ThermalProperties thermalProperties;
    private final FireApproachDispatcher fireApproachDispatcher;

    @Autowired(required = false)
    private VisibleFireLocalizationDispatcher visibleFireLocalizationDispatcher;

    @Value("${fc100.fire-event.dedup-enabled:true}")
    private boolean fireEventDedupEnabled = true;

    @Value("${fc100.fire-event.dedup-radius-m:40}")
    private double fireEventDedupRadiusM = 40.0;

    @Value("${fc100.fire-event.dedup-radius-margin-m:10}")
    private double fireEventDedupRadiusMarginM = 10.0;

    @Value("${fc100.fire-event.dedup-radius-min-m:15}")
    private double fireEventDedupRadiusMinM = 15.0;

    @Value("${fc100.fire-event.dedup-radius-max-m:60}")
    private double fireEventDedupRadiusMaxM = 60.0;

    @Value("${fc100.fire-event.dedup-active-window-ms:1800000}")
    private long fireEventDedupActiveWindowMs = 30 * 60 * 1000L;

    public FireEventServiceImpl(FireEventMapper em, FireEventHistoryMapper hm, FireMissionMapper mm,
                                MissionNoGenerator g, Clock c,
                                IDeviceRedisService deviceRedisService) {
        this(em, hm, mm, g, c, deviceRedisService, null, null, null, null, null, null);
    }

    @Override
    @Transactional
    public FireEventDecisionResult confirm(String eventId, FireEventActionParam param, HttpServletRequest request) {
        requireOperationWiring();
        FireEventEntity event = requireEvent(eventId);
        long now = clock.now();
        event.setConfirmedStatus("CONFIRMED");
        event.setUpdatedBy(param.getOperatorId());
        event.setUpdateTime(now);
        eventMapper.updateById(event);
        insertDecisionHistory(event, "CONFIRMED", param.getOperatorId(), now);

        OperationIncidentEntity existing = findActiveIncident(event.getId());
        OperationIncidentDTO incident;
        boolean reused = existing != null;
        if (existing != null) {
            if (OperationIncidentStatus.CANDIDATE.name().equals(existing.getStatus()) && incidentStateMachine != null) {
                OperationIncidentEntity confirmed = incidentStateMachine.transit(incidentCmd(
                    existing.getId(), OperationIncidentEvent.CONFIRM, param, request)
                    .expectedFrom(OperationIncidentStatus.CANDIDATE)
                    .remark(param.getReason())
                    .build());
                incident = toIncidentDto(confirmed);
            } else {
                incident = toIncidentDto(existing);
            }
            event.setLinkedIncidentId(existing.getId());
            eventMapper.updateById(event);
        } else {
            CreateOperationIncidentParam create = new CreateOperationIncidentParam();
            create.setFireEventId(event.getId());
            create.setCreatedBy(param.getOperatorId());
            create.setConfirmedBy(param.getOperatorId());
            create.setLevel(event.getFireLevel());
            create.setCenterLat(event.getLat());
            create.setCenterLng(event.getLng());
            create.setRiskRadiusM(event.getGeoErrorRadiusM());
            incident = operationIncidentService.create(create);
            event.setLinkedIncidentId(incident.getId());
            eventMapper.updateById(event);
        }

        RecheckAdvice advice = recheckAdvice(event);
        updateIncidentRecheckAdvice(incident.getId(), advice, now);
        incident.setRecommendedRecheck(advice.recommended ? 1 : 0);
        incident.setRecheckReason(advice.reason);

        FireMissionEntity existingDraft = findActiveMission(event.getId());
        boolean draftCreated = false;
        String draftMissionNo = existingDraft == null ? null : existingDraft.getMissionNo();
        if (isPrecise(event)) {
            if (existingDraft == null) {
                FireMissionEntity draft = createDraftMission(event, incident.getId(), now);
                draftCreated = true;
                draftMissionNo = draft.getMissionNo();
            } else if (existingDraft.getIncidentId() == null) {
                existingDraft.setIncidentId(incident.getId());
                existingDraft.setUpdateTime(now);
                missionMapper.updateById(existingDraft);
            }
        }

        FireEventDecisionResult result = new FireEventDecisionResult();
        result.setFireEvent(toFireEventDto(event));
        result.setIncident(incident);
        result.setReusedIncident(reused);
        result.setDraftMissionCreated(draftCreated);
        result.setDraftMissionNo(draftMissionNo);
        result.setRecommendedRecheck(advice.recommended);
        result.setRecheckReason(advice.reason);
        return result;
    }

    @Override
    @Transactional
    public FireEventDecisionResult reject(String eventId, FireEventActionParam param, HttpServletRequest request) {
        requireOperationWiring();
        FireEventEntity event = requireEvent(eventId);
        long now = clock.now();
        event.setConfirmedStatus("REJECTED");
        event.setUpdatedBy(param.getOperatorId());
        event.setUpdateTime(now);
        eventMapper.updateById(event);
        insertDecisionHistory(event, "REJECTED", param.getOperatorId(), now);

        OperationIncidentDTO incident = null;
        if (event.getLinkedIncidentId() != null) {
            OperationIncidentEntity linked = operationIncidentMapper.selectById(event.getLinkedIncidentId());
            if (linked != null && (OperationIncidentStatus.CANDIDATE.name().equals(linked.getStatus())
                || OperationIncidentStatus.CONFIRMED.name().equals(linked.getStatus()))) {
                OperationActionParam action = new OperationActionParam();
                action.setOperatorId(param.getOperatorId());
                action.setReason(param.getReason() != null && !param.getReason().isBlank()
                    ? param.getReason()
                    : "fire event rejected as false alarm");
                incident = toIncidentDto(operationIncidentService.markFalseAlarm(linked.getId(), action, request));
            } else if (linked != null) {
                incident = toIncidentDto(linked);
            }
        }

        FireEventDecisionResult result = new FireEventDecisionResult();
        result.setFireEvent(toFireEventDto(event));
        result.setIncident(incident);
        result.setReusedIncident(incident != null);
        return result;
    }

    @Override
    @Transactional
    public FireEventRecheckResultDTO recordRecheckResult(String eventId, FireEventRecheckResultParam param,
                                                         HttpServletRequest request) {
        requireOperationWiring();
        FireEventEntity event = requireEvent(eventId);
        if (event.getLinkedIncidentId() == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INCIDENT_NOT_FOUND,
                "fire event has no linked operation incident: " + event.getId());
        }
        OperationIncidentEntity linked = operationIncidentMapper.selectById(event.getLinkedIncidentId());
        if (linked == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INCIDENT_NOT_FOUND,
                "operation incident not found: " + event.getLinkedIncidentId());
        }

        boolean saturated = event.getThermalTemperature() != null
            && event.getThermalTemperature() >= thermalProperties.getSaturationTempC();
        RecheckDecision decision = decideRecheck(event, param, saturated);
        long now = clock.now();
        insertRecheckHistory(event, param, decision, now);
        updateIncidentRecheckResult(linked.getId(), decision.reason, now);

        OperationIncidentEntity current = linked;
        if (OperationIncidentStatus.RESPONDING.name().equals(current.getStatus())) {
            current = incidentStateMachine.transit(incidentCmd(current.getId(), OperationIncidentEvent.START_RECHECK,
                param.getOperatorId(), param.getRemark(), request).build());
        }
        OperationIncidentEntity next = incidentStateMachine.transit(incidentCmd(current.getId(),
            decision.resolved ? OperationIncidentEvent.RESOLVE : OperationIncidentEvent.CONTINUE_RESPONSE,
            param.getOperatorId(), decision.reason, request).build());

        FireEventRecheckResultDTO dto = new FireEventRecheckResultDTO();
        dto.setFireEventId(event.getId());
        dto.setIncidentId(next.getId());
        dto.setSaturated(saturated);
        dto.setResolved(decision.resolved);
        dto.setReason(decision.reason);
        dto.setIncident(toIncidentDto(next));
        return dto;
    }

    public FireEventServiceImpl(FireEventMapper em, FireEventHistoryMapper hm, FireMissionMapper mm,
                                MissionNoGenerator g, Clock c,
                                IDeviceRedisService deviceRedisService,
                                FireGeoLocationService fireGeoLocationService) {
        this(em, hm, mm, g, c, deviceRedisService, fireGeoLocationService, null, null, null, null, null);
    }

    public FireEventServiceImpl(FireEventMapper em, FireEventHistoryMapper hm, FireMissionMapper mm,
                                MissionNoGenerator g, Clock c,
                                IDeviceRedisService deviceRedisService,
                                FireGeoLocationService fireGeoLocationService,
                                OperationIncidentService operationIncidentService,
                                OperationIncidentMapper operationIncidentMapper,
                                IncidentStateMachine incidentStateMachine,
                                Fc100ThermalProperties thermalProperties) {
        this(em, hm, mm, g, c, deviceRedisService, fireGeoLocationService, operationIncidentService,
            operationIncidentMapper, incidentStateMachine, thermalProperties, null);
    }

    @Autowired
    public FireEventServiceImpl(FireEventMapper em, FireEventHistoryMapper hm, FireMissionMapper mm,
                                MissionNoGenerator g, Clock c,
                                IDeviceRedisService deviceRedisService,
                                FireGeoLocationService fireGeoLocationService,
                                OperationIncidentService operationIncidentService,
                                OperationIncidentMapper operationIncidentMapper,
                                IncidentStateMachine incidentStateMachine,
                                Fc100ThermalProperties thermalProperties,
                                FireApproachDispatcher fireApproachDispatcher) {
        this.eventMapper = em;
        this.historyMapper = hm;
        this.missionMapper = mm;
        this.noGen = g;
        this.clock = c;
        this.deviceRedisService = deviceRedisService;
        this.fireGeoLocationService = fireGeoLocationService;
        this.operationIncidentService = operationIncidentService;
        this.operationIncidentMapper = operationIncidentMapper;
        this.incidentStateMachine = incidentStateMachine;
        this.thermalProperties = thermalProperties != null ? thermalProperties : new Fc100ThermalProperties();
        this.fireApproachDispatcher = fireApproachDispatcher;
    }

    @Override
    @Transactional
    public FireEventCreateResponse create(FireEventCreateParam param) {
        resolveFirePointFromGeoSnapshot(param);
        fillThermalRoiFromMeasureRoi(param);
        // 去重资格必须在 OSD 回填坐标之后判：串行确认链的事件不带火点坐标（回填飞机位置），
        // 先判资格会让这类事件整体跳过空间去重——2026-07-26 实飞同一盆火 10 连报。
        fillPositionFromOsdIfMissing(param);
        boolean spatialDedupCoordinateEligible = param.getLat() != null
            && param.getLng() != null
            && !isUnresolvedLaserQuality(param.getGeoQuality());
        long eventTs = Instant.parse(param.getTimestamp()).toEpochMilli();

        // 1. 同 eventId 去重：已存在则返回已绑定的活跃任务
        FireEventEntity existing = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", param.getEventId()));
        if (existing != null) {
            String activeMissionNo = findActiveMissionNo(existing.getId());
            return new FireEventCreateResponse(
                existing.getId(),
                existing.getEventId(),
                activeMissionNo != null,
                activeMissionNo,
                activeMissionNo != null
                    ? FireMissionStatus.WAITING_REVIEW.name()
                    : existing.getStatus(),
                false, false, false, "EXISTING_EVENT_ID");
        }

        long now = clock.now();
        return createAfterEventIdDedup(param, eventTs, now, spatialDedupCoordinateEligible);

    }

    @Override
    @Transactional
    public synchronized boolean applyLaserLocation(String eventId, FireLaserLocationParam param) {
        if (eventId == null || eventId.isBlank() || param == null) {
            return false;
        }
        FireEventEntity existing = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", eventId));
        if (existing == null || !GEO_QUALITY_LASER_LOCATING.equalsIgnoreCase(existing.getGeoQuality())) {
            return false;
        }
        existing.setLat(param.getFireLat());
        existing.setLng(param.getFireLng());
        existing.setAlt(param.getFireAlt());
        existing.setGeoMethod(GEO_METHOD_LASER_RANGEFINDER);
        existing.setGeoQuality(GEO_QUALITY_PRECISE);
        existing.setGeoErrorRadiusM(
            param.getGeoErrorRadiusM() != null ? param.getGeoErrorRadiusM() : 5.0);
        existing.setGeoSourceTs(param.getSourceTs());
        existing.setUpdateTime(clock.now());
        eventMapper.updateById(existing);
        insertLaserLocationHistory(existing, param.getSourceTs(), "LASER_LOCATED");
        return true;
    }

    @Override
    @Transactional
    public synchronized boolean markLaserLocationFailed(String eventId, String reason, long sourceTs) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        FireEventEntity existing = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", eventId));
        if (existing == null || !GEO_QUALITY_LASER_LOCATING.equalsIgnoreCase(existing.getGeoQuality())) {
            return false;
        }
        existing.setGeoQuality(GEO_QUALITY_LASER_FAILED);
        existing.setGeoSourceTs(sourceTs);
        existing.setUpdateTime(clock.now());
        eventMapper.updateById(existing);
        insertLaserLocationHistory(existing, sourceTs, "LASER_FAILED");
        log.warn("laser fire location failed eventId={} reason={}", eventId, reason);
        return true;
    }

    private FireEventCreateResponse createAfterEventIdDedup(
        FireEventCreateParam param,
        long eventTs,
        long now,
        boolean spatialDedupCoordinateEligible) {
        if (fireEventDedupEnabled && spatialDedupCoordinateEligible) {
            return createWithDedupLock(param, eventTs, now);
        }
        return createWithOptionalSpatialDedup(param, eventTs, now, spatialDedupCoordinateEligible);
    }

    private FireEventCreateResponse createWithDedupLock(FireEventCreateParam param, long eventTs, long now) {
        String lockName = DEDUP_LOCK_PREFIX + workspaceIdOf(param);
        boolean lockAcquired = acquireDedupLock(lockName);
        try {
            return createWithOptionalSpatialDedup(param, eventTs, now, true);
        } finally {
            if (lockAcquired) {
                releaseDedupLock(lockName);
            }
        }
    }

    private FireEventCreateResponse createWithOptionalSpatialDedup(
        FireEventCreateParam param,
        long eventTs,
        long now,
        boolean spatialDedupCoordinateEligible) {
        FireEventEntity mergeCandidate = spatialDedupCoordinateEligible
            ? findNearbyActiveEvent(param, now)
            : null;
        if (mergeCandidate != null) {
            boolean levelUpgraded = mergeIntoExisting(mergeCandidate, param, now);
            insertHistory(mergeCandidate, param, eventTs, now, "MERGED");
            String activeMissionNo = findActiveMissionNo(mergeCandidate.getId());
            return createdResponse(
                mergeCandidate,
                new FireEventCreateResponse(
                mergeCandidate.getId(),
                mergeCandidate.getEventId(),
                activeMissionNo != null,
                activeMissionNo,
                activeMissionNo != null
                    ? FireMissionStatus.WAITING_REVIEW.name()
                    : mergeCandidate.getStatus(),
                false,
                true,
                levelUpgraded,
                    levelUpgraded ? "LEVEL_UPGRADED" : "MERGED_NEARBY"));
        }

        FireEventEntity e = new FireEventEntity();
        BeanUtils.copyProperties(param, e);
        e.setEventTimestamp(eventTs);
        e.setLastSeenTime(eventTs);
        e.setReportCount(1);
        e.setLastSourceEventId(param.getEventId());
        e.setNotificationVersion(1);
        e.setAltitudeReference(param.getAltitudeReference() != null
            ? param.getAltitudeReference() : "ELLIPSOID");
        e.setTemperatureUnit(param.getTemperatureUnit() != null
            ? param.getTemperatureUnit() : "K");
        e.setWorkspaceId(param.getWorkspaceId() != null
            ? param.getWorkspaceId() : "DEFAULT");
        e.setDeleted(0);
        e.setCreateTime(now);
        e.setUpdateTime(now);

        BigDecimal c = param.getConfidence();
        boolean candidate = c.compareTo(LOW) >= 0;
        e.setStatus(candidate
            ? FireEventStatus.CANDIDATE.name()
            : FireEventStatus.LOW_CONFIDENCE.name());
        e.setConfirmedStatus("PENDING");
        if (e.getGeoQuality() == null || e.getGeoQuality().isBlank()) {
            e.setGeoQuality("UNKNOWN");
        }
        eventMapper.insert(e);
        insertHistory(e, param, eventTs, now, "CREATED");
        dispatchVisibleLaserLocalization(e, param, eventTs);

        return createdResponse(e, new FireEventCreateResponse(e.getId(), e.getEventId(),
            false, null, e.getStatus(), true, false, true, "CREATED"));
    }

    private void dispatchVisibleLaserLocalization(
            FireEventEntity event,
            FireEventCreateParam param,
            long eventTs) {
        if (visibleFireLocalizationDispatcher == null
                || !GEO_QUALITY_LASER_LOCATING.equalsIgnoreCase(event.getGeoQuality())
                || param.getVisibleRoi() == null
                || param.getVisibleRoi().isEmpty()
                || event.getDeviceSn() == null
                || event.getDeviceSn().isBlank()) {
            return;
        }
        visibleFireLocalizationDispatcher.dispatch(
                event.getEventId(),
                taskIdForLaserEvent(event.getEventId(), event.getDeviceSn()),
                event.getDeviceSn(),
                eventTs,
                param.getVisibleRoi());
    }

    private String taskIdForLaserEvent(String eventId, String deviceSn) {
        if (eventId != null && !eventId.isBlank()) {
            int split = eventId.lastIndexOf('-');
            if (split > 0 && split < eventId.length() - 1) {
                String suffix = eventId.substring(split + 1);
                if (suffix.length() >= 10 && suffix.chars().allMatch(Character::isDigit)) {
                    return eventId.substring(0, split);
                }
            }
        }
        return "fire-" + deviceSn;
    }

    private boolean acquireDedupLock(String lockName) {
        try {
            /*
             * MySQL named locks are connection-scoped. create() is @Transactional, and MyBatis-Spring
             * binds one SqlSession/Connection to that transaction, so GET_LOCK and RELEASE_LOCK run on
             * the same pooled connection. Without this transaction boundary, separate mapper calls could
             * borrow different connections and RELEASE_LOCK would not necessarily release the acquired lock.
             * The lock lives in MySQL, so it also serializes future multi-instance deployments.
             */
            Integer acquired = eventMapper.acquireNamedLock(lockName, DEDUP_LOCK_TIMEOUT_SECONDS);
            if (Integer.valueOf(1).equals(acquired)) {
                return true;
            }
            log.warn("fire event spatial dedup lock not acquired lockName={} result={}, proceeding without lock",
                lockName, acquired);
        } catch (Exception e) {
            log.warn("fire event spatial dedup lock acquire failed lockName={}, proceeding without lock",
                lockName, e);
        }
        return false;
    }

    private void releaseDedupLock(String lockName) {
        try {
            Integer released = eventMapper.releaseNamedLock(lockName);
            if (!Integer.valueOf(1).equals(released)) {
                log.warn("fire event spatial dedup lock release returned non-success lockName={} result={}",
                    lockName, released);
            }
        } catch (Exception e) {
            log.warn("fire event spatial dedup lock release failed lockName={}", lockName, e);
        }
    }

    private FireEventCreateResponse createdResponse(FireEventEntity event, FireEventCreateResponse response) {
        if (fireApproachDispatcher != null && !isUnresolvedLaserQuality(event.getGeoQuality())) {
            fireApproachDispatcher.dispatchIfEligible(event);
        }
        return response;
    }

    private boolean isUnresolvedLaserQuality(String quality) {
        return GEO_QUALITY_LASER_LOCATING.equalsIgnoreCase(quality)
            || GEO_QUALITY_LASER_FAILED.equalsIgnoreCase(quality);
    }

    @Override
    @Transactional
    public boolean attachVisibleImage(
        String eventId,
        String sourceEventId,
        String visibleImageUrl,
        String timestamp,
        String thermalSourceEventId,
        String thermalImageUrl) {
        if (eventId == null || eventId.isBlank() || visibleImageUrl == null || visibleImageUrl.isBlank()) {
            return false;
        }
        if (thermalSourceEventId != null && !thermalSourceEventId.isBlank()
            && (thermalImageUrl == null || thermalImageUrl.isBlank())) {
            log.warn(
                "visible confirmation rejected without associated thermal image eventId={} sourceEventId={} thermalSourceEventId={}",
                eventId,
                sourceEventId,
                thermalSourceEventId);
            return false;
        }
        FireEventEntity existing = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", eventId));
        if (existing == null) {
            return false;
        }
        long eventTs = timestamp != null && !timestamp.isBlank()
            ? Instant.parse(timestamp).toEpochMilli()
            : clock.now();
        long now = clock.now();
        String associatedThermalImageUrl = thermalImageUrl != null && !thermalImageUrl.isBlank()
            ? thermalImageUrl
            : thermalSourceEventId != null && !thermalSourceEventId.isBlank()
                ? null
                : existing.getThermalImageUrl();
        existing.setVisibleImageUrl(visibleImageUrl);
        if (thermalImageUrl != null && !thermalImageUrl.isBlank()) {
            existing.setThermalImageUrl(thermalImageUrl);
        }
        existing.setLastSourceEventId(sourceEventId != null && !sourceEventId.isBlank() ? sourceEventId : eventId);
        existing.setUpdateTime(now);
        eventMapper.updateById(existing);

        FireEventHistoryEntity history = new FireEventHistoryEntity();
        history.setFireEventId(existing.getId());
        history.setEventId(existing.getEventId());
        history.setSourceEventId(existing.getLastSourceEventId());
        history.setWorkspaceId(existing.getWorkspaceId());
        history.setSource(existing.getSource());
        history.setDeviceSn(existing.getDeviceSn());
        history.setConfidence(existing.getConfidence());
        history.setFireLevel(existing.getFireLevel());
        history.setLat(existing.getLat());
        history.setLng(existing.getLng());
        history.setAlt(existing.getAlt());
        history.setAltitudeReference(existing.getAltitudeReference());
        history.setGeoMethod(existing.getGeoMethod());
        history.setGeoErrorRadiusM(existing.getGeoErrorRadiusM());
        history.setGeoQuality(existing.getGeoQuality());
        history.setGeoSourceTs(existing.getGeoSourceTs());
        history.setAircraftLat(existing.getAircraftLat());
        history.setAircraftLng(existing.getAircraftLng());
        history.setAircraftAlt(existing.getAircraftAlt());
        history.setGimbalPitch(existing.getGimbalPitch());
        history.setGimbalYaw(existing.getGimbalYaw());
        history.setGimbalRoll(existing.getGimbalRoll());
        history.setThermalRoi(existing.getThermalRoi());
        history.setThermalTemperature(existing.getThermalTemperature());
        history.setTemperatureUnit(existing.getTemperatureUnit());
        history.setThermalImageUrl(associatedThermalImageUrl);
        history.setVisibleImageUrl(visibleImageUrl);
        history.setEventTimestamp(eventTs);
        history.setAction("VISIBLE_CONFIRM");
        history.setCreateTime(now);
        historyMapper.insert(history);
        return true;
    }

    @Override
    @Transactional
    public boolean recordVisibleConfirmationStatus(
        String eventId,
        String sourceEventId,
        String action,
        String visibleImageUrl,
        String timestamp,
        String thermalSourceEventId,
        String thermalImageUrl) {
        if (eventId == null || eventId.isBlank() || action == null || !action.startsWith("VISIBLE_")) {
            return false;
        }
        if (thermalSourceEventId != null && !thermalSourceEventId.isBlank()
            && (thermalImageUrl == null || thermalImageUrl.isBlank())) {
            log.warn(
                "visible status rejected without associated thermal image eventId={} sourceEventId={} thermalSourceEventId={} action={}",
                eventId,
                sourceEventId,
                thermalSourceEventId,
                action);
            return false;
        }
        FireEventEntity existing = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", eventId));
        if (existing == null) {
            return false;
        }
        long eventTs = timestamp != null && !timestamp.isBlank()
            ? Instant.parse(timestamp).toEpochMilli()
            : clock.now();
        long now = clock.now();
        String associatedThermalImageUrl = thermalImageUrl != null && !thermalImageUrl.isBlank()
            ? thermalImageUrl
            : thermalSourceEventId != null && !thermalSourceEventId.isBlank()
                ? null
                : existing.getThermalImageUrl();

        FireEventHistoryEntity history = new FireEventHistoryEntity();
        history.setFireEventId(existing.getId());
        history.setEventId(existing.getEventId());
        history.setSourceEventId(sourceEventId != null && !sourceEventId.isBlank() ? sourceEventId : eventId);
        history.setWorkspaceId(existing.getWorkspaceId());
        history.setSource(existing.getSource());
        history.setDeviceSn(existing.getDeviceSn());
        history.setConfidence(existing.getConfidence());
        history.setFireLevel(existing.getFireLevel());
        history.setLat(existing.getLat());
        history.setLng(existing.getLng());
        history.setAlt(existing.getAlt());
        history.setAltitudeReference(existing.getAltitudeReference());
        history.setGeoMethod(existing.getGeoMethod());
        history.setGeoErrorRadiusM(existing.getGeoErrorRadiusM());
        history.setGeoQuality(existing.getGeoQuality());
        history.setGeoSourceTs(existing.getGeoSourceTs());
        history.setAircraftLat(existing.getAircraftLat());
        history.setAircraftLng(existing.getAircraftLng());
        history.setAircraftAlt(existing.getAircraftAlt());
        history.setGimbalPitch(existing.getGimbalPitch());
        history.setGimbalYaw(existing.getGimbalYaw());
        history.setGimbalRoll(existing.getGimbalRoll());
        history.setThermalRoi(existing.getThermalRoi());
        history.setThermalTemperature(existing.getThermalTemperature());
        history.setTemperatureUnit(existing.getTemperatureUnit());
        history.setThermalImageUrl(associatedThermalImageUrl);
        history.setVisibleImageUrl(visibleImageUrl);
        history.setEventTimestamp(eventTs);
        history.setAction(action);
        history.setCreateTime(now);
        historyMapper.insert(history);
        return true;
    }

    /**
     * Future larger datasets can add idx_fire_event_workspace_lastseen.
     */
    private FireEventEntity findNearbyActiveEvent(FireEventCreateParam param, long now) {
        if (!fireEventDedupEnabled || param.getLat() == null || param.getLng() == null) {
            return null;
        }
        double prefilterRadiusM = dedupPrefilterRadiusM();
        double latDelta = prefilterRadiusM / 111320.0;
        double cos = Math.cos(Math.toRadians(param.getLat()));
        double lngDelta = prefilterRadiusM / (111320.0 * Math.max(Math.abs(cos), 1e-6));
        long activeSince = now - fireEventDedupActiveWindowMs;
        List<FireEventEntity> candidates = eventMapper.selectList(new QueryWrapper<FireEventEntity>()
            .eq("workspace_id", workspaceIdOf(param))
            .eq("deleted", 0)
            .ne("status", FireEventStatus.IGNORED.name())
            .and(w -> w.ge("last_seen_time", activeSince)
                .or()
                .eq("status", FireEventStatus.MISSION_CREATED.name()))
            .isNotNull("lat")
            .isNotNull("lng")
            .between("lat", param.getLat() - latDelta, param.getLat() + latDelta)
            .between("lng", param.getLng() - lngDelta, param.getLng() + lngDelta)
            .orderByDesc("last_seen_time")
            .last("limit 50"));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        FireEventEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (FireEventEntity candidate : candidates) {
            if (candidate.getLat() == null || candidate.getLng() == null
                || candidate.getDeleted() == null || candidate.getDeleted() != 0
                || FireEventStatus.IGNORED.name().equals(candidate.getStatus())
                || !workspaceIdOf(param).equals(candidate.getWorkspaceId())) {
                continue;
            }
            boolean missionCreated = FireEventStatus.MISSION_CREATED.name().equals(candidate.getStatus());
            Long candidateLastSeen = candidate.getLastSeenTime();
            if (!missionCreated && (candidateLastSeen == null || candidateLastSeen < activeSince)) {
                continue;
            }
            double distance = distanceMeters(param.getLat(), param.getLng(), candidate.getLat(), candidate.getLng());
            double threshold = adaptiveDedupThresholdM(param, candidate);
            if (distance <= threshold && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private double dedupPrefilterRadiusM() {
        return Math.max(fireEventDedupRadiusM, fireEventDedupRadiusMaxM);
    }

    private double adaptiveDedupThresholdM(FireEventCreateParam param, FireEventEntity candidate) {
        Double reportError = param.getGeoErrorRadiusM();
        Double candidateError = candidate.getGeoErrorRadiusM();
        if (reportError == null || candidateError == null) {
            return fireEventDedupRadiusM;
        }
        return clamp(reportError + candidateError + fireEventDedupRadiusMarginM,
            fireEventDedupRadiusMinM, fireEventDedupRadiusMaxM);
    }

    private double clamp(double value, double min, double max) {
        double lower = Math.min(min, max);
        double upper = Math.max(min, max);
        return Math.max(lower, Math.min(upper, value));
    }

    private boolean mergeIntoExisting(FireEventEntity existing, FireEventCreateParam param, long now) {
        boolean levelUpgraded = fireLevelRank(param.getFireLevel()) > fireLevelRank(existing.getFireLevel());
        existing.setLastSeenTime(now);
        existing.setReportCount((existing.getReportCount() == null ? 1 : existing.getReportCount()) + 1);
        existing.setLastSourceEventId(param.getEventId());
        existing.setUpdateTime(now);
        if (param.getConfidence() != null) {
            existing.setConfidence(param.getConfidence());
        }
        if (param.getFireLevel() != null && !param.getFireLevel().isBlank()) {
            existing.setFireLevel(param.getFireLevel());
        }
        if (levelUpgraded) {
            existing.setNotificationVersion(existing.getNotificationVersion() == null ? 2 : existing.getNotificationVersion() + 1);
        } else if (existing.getNotificationVersion() == null) {
            existing.setNotificationVersion(1);
        }
        existing.setThermalTemperature(maxNullable(existing.getThermalTemperature(), param.getThermalTemperature()));
        if (param.getThermalImageUrl() != null && !param.getThermalImageUrl().isBlank()
            && (existing.getThermalImageUrl() == null || existing.getThermalImageUrl().isBlank())) {
            existing.setThermalImageUrl(param.getThermalImageUrl());
        }
        if (param.getVisibleImageUrl() != null && !param.getVisibleImageUrl().isBlank()
            && (existing.getVisibleImageUrl() == null || existing.getVisibleImageUrl().isBlank())) {
            existing.setVisibleImageUrl(param.getVisibleImageUrl());
        }
        if (geoError(param.getGeoErrorRadiusM()) < geoError(existing.getGeoErrorRadiusM())) {
            existing.setLat(param.getLat());
            existing.setLng(param.getLng());
            existing.setAlt(param.getAlt());
            existing.setAltitudeReference(param.getAltitudeReference());
            existing.setGeoMethod(param.getGeoMethod());
            existing.setGeoErrorRadiusM(param.getGeoErrorRadiusM());
            existing.setGeoQuality(param.getGeoQuality());
            existing.setGeoSourceTs(param.getGeoSourceTs());
        }
        eventMapper.updateById(existing);
        return levelUpgraded;
    }

    private Double maxNullable(Double oldValue, Double newValue) {
        if (oldValue == null) return newValue;
        if (newValue == null) return oldValue;
        return Math.max(oldValue, newValue);
    }

    private double geoError(Double errorRadiusM) {
        return errorRadiusM == null ? Double.POSITIVE_INFINITY : errorRadiusM;
    }

    private int fireLevelRank(String level) {
        if (level == null) return 0;
        switch (level.toUpperCase()) {
            case "HIGH": return 3;
            case "MEDIUM": return 2;
            case "LOW": return 1;
            default: return 0;
        }
    }

    private void insertHistory(FireEventEntity parent, FireEventCreateParam param, long eventTs, long now, String action) {
        FireEventHistoryEntity history = new FireEventHistoryEntity();
        history.setFireEventId(parent.getId());
        history.setEventId(parent.getEventId());
        history.setSourceEventId(param.getEventId());
        history.setWorkspaceId(workspaceIdOf(param));
        history.setSource(param.getSource());
        history.setDeviceSn(param.getDeviceSn());
        history.setConfidence(param.getConfidence());
        history.setFireLevel(param.getFireLevel());
        history.setLat(param.getLat());
        history.setLng(param.getLng());
        history.setAlt(param.getAlt());
        history.setAltitudeReference(param.getAltitudeReference() != null
            ? param.getAltitudeReference() : parent.getAltitudeReference());
        copyGeoFields(param, history);
        history.setThermalTemperature(param.getThermalTemperature());
        history.setTemperatureUnit(param.getTemperatureUnit() != null
            ? param.getTemperatureUnit() : parent.getTemperatureUnit());
        history.setThermalImageUrl(param.getThermalImageUrl());
        history.setVisibleImageUrl(param.getVisibleImageUrl());
        history.setEventTimestamp(eventTs);
        history.setAction(action);
        history.setCreateTime(now);
        historyMapper.insert(history);
    }

    private void insertLaserLocationHistory(FireEventEntity event, long sourceTs, String action) {
        FireEventCreateParam snapshot = new FireEventCreateParam();
        snapshot.setEventId(event.getEventId());
        snapshot.setWorkspaceId(event.getWorkspaceId());
        snapshot.setSource(event.getSource());
        snapshot.setDeviceSn(event.getDeviceSn());
        snapshot.setConfidence(event.getConfidence());
        snapshot.setFireLevel(event.getFireLevel());
        snapshot.setLat(event.getLat());
        snapshot.setLng(event.getLng());
        snapshot.setAlt(event.getAlt());
        snapshot.setAltitudeReference(event.getAltitudeReference());
        snapshot.setGeoMethod(event.getGeoMethod());
        snapshot.setGeoErrorRadiusM(event.getGeoErrorRadiusM());
        snapshot.setGeoQuality(event.getGeoQuality());
        snapshot.setGeoSourceTs(event.getGeoSourceTs());
        snapshot.setAircraftLat(event.getAircraftLat());
        snapshot.setAircraftLng(event.getAircraftLng());
        snapshot.setAircraftAlt(event.getAircraftAlt());
        snapshot.setGimbalPitch(event.getGimbalPitch());
        snapshot.setGimbalYaw(event.getGimbalYaw());
        snapshot.setGimbalRoll(event.getGimbalRoll());
        snapshot.setThermalRoi(event.getThermalRoi());
        snapshot.setThermalTemperature(event.getThermalTemperature());
        snapshot.setTemperatureUnit(event.getTemperatureUnit());
        snapshot.setThermalImageUrl(event.getThermalImageUrl());
        snapshot.setVisibleImageUrl(event.getVisibleImageUrl());
        insertHistory(event, snapshot, sourceTs, clock.now(), action);
    }

    private String workspaceIdOf(FireEventCreateParam param) {
        return param.getWorkspaceId() != null ? param.getWorkspaceId() : "DEFAULT";
    }

    private void resolveFirePointFromGeoSnapshot(FireEventCreateParam param) {
        if (hasLaserRangefinderGeo(param)) {
            param.setGeoQuality(GEO_QUALITY_PRECISE);
            if (param.getGeoSourceTs() == null && param.getTimestamp() != null && !param.getTimestamp().isBlank()) {
                param.setGeoSourceTs(Instant.parse(param.getTimestamp()).toEpochMilli());
            }
            return;
        }
        if (param == null || param.getGeoSnapshot() == null || fireGeoLocationService == null) {
            copyGeoSnapshotTelemetry(param);
            return;
        }
        copyGeoSnapshotTelemetry(param);
        FireGeoLocationResult result = fireGeoLocationService.resolve(param.getGeoSnapshot());
        if (result == null) {
            return;
        }
        if (result.getLat() != null && result.getLng() != null) {
            param.setLat(result.getLat());
            param.setLng(result.getLng());
            param.setAlt(result.getAlt());
        }
        if (result.getGeoMethod() != null) {
            param.setGeoMethod(result.getGeoMethod());
        }
        if (result.getGeoQuality() != null) {
            param.setGeoQuality(result.getGeoQuality());
        }
        if (result.getGeoErrorRadiusM() != null) {
            param.setGeoErrorRadiusM(result.getGeoErrorRadiusM());
        }
        if (result.getGeoSourceTs() != null) {
            param.setGeoSourceTs(result.getGeoSourceTs());
        }
    }

    private boolean hasLaserRangefinderGeo(FireEventCreateParam param) {
        return param != null
            && param.getLat() != null
            && param.getLng() != null
            && !isUnresolvedLaserQuality(param.getGeoQuality())
            && GEO_METHOD_LASER_RANGEFINDER.equalsIgnoreCase(param.getGeoMethod());
    }

    private void copyGeoSnapshotTelemetry(FireEventCreateParam param) {
        if (param == null || param.getGeoSnapshot() == null) {
            return;
        }
        var snapshot = param.getGeoSnapshot();
        if (snapshot.getSourceTs() != null && param.getGeoSourceTs() == null) {
            param.setGeoSourceTs(snapshot.getSourceTs());
        }
        if (snapshot.getAircraftPosition() != null) {
            if (param.getAircraftLat() == null) param.setAircraftLat(snapshot.getAircraftPosition().getLat());
            if (param.getAircraftLng() == null) param.setAircraftLng(snapshot.getAircraftPosition().getLng());
            if (param.getAircraftAlt() == null) param.setAircraftAlt(snapshot.getAircraftPosition().getAlt());
        }
        if (snapshot.getGimbalAttitude() != null) {
            if (param.getGimbalPitch() == null) param.setGimbalPitch(snapshot.getGimbalAttitude().getPitch());
            if (param.getGimbalYaw() == null) param.setGimbalYaw(snapshot.getGimbalAttitude().getYaw());
            if (param.getGimbalRoll() == null) param.setGimbalRoll(snapshot.getGimbalAttitude().getRoll());
        }
        if (snapshot.getThermalRoi() != null && param.getThermalRoi() == null) {
            try {
                param.setThermalRoi(JSON.writeValueAsString(snapshot.getThermalRoi()));
            } catch (JsonProcessingException e) {
                param.setThermalRoi(snapshot.getThermalRoi().toString());
            }
        }
    }

    private void fillThermalRoiFromMeasureRoi(FireEventCreateParam param) {
        if (param == null || param.getThermalRoi() != null || param.getThermalMeasureRoi() == null) {
            return;
        }
        try {
            param.setThermalRoi(JSON.writeValueAsString(orderedThermalRoi(param.getThermalMeasureRoi())));
        } catch (JsonProcessingException e) {
            param.setThermalRoi(param.getThermalMeasureRoi().toString());
        }
    }

    private Map<String, Double> orderedThermalRoi(Map<String, Double> roi) {
        Map<String, Double> ordered = new LinkedHashMap<>();
        copyRoiValue(roi, ordered, "x");
        copyRoiValue(roi, ordered, "y");
        copyRoiValue(roi, ordered, "width");
        copyRoiValue(roi, ordered, "height");
        roi.forEach((key, value) -> {
            if (!ordered.containsKey(key)) {
                ordered.put(key, value);
            }
        });
        return ordered;
    }

    private void copyRoiValue(Map<String, Double> source, Map<String, Double> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private void copyGeoFields(FireEventCreateParam param, FireEventEntity target) {
        if (param == null || target == null) return;
        if (param.getGeoMethod() != null) target.setGeoMethod(param.getGeoMethod());
        if (param.getGeoErrorRadiusM() != null) target.setGeoErrorRadiusM(param.getGeoErrorRadiusM());
        if (param.getGeoQuality() != null) target.setGeoQuality(param.getGeoQuality());
        if (param.getGeoSourceTs() != null) target.setGeoSourceTs(param.getGeoSourceTs());
        if (param.getAircraftLat() != null) target.setAircraftLat(param.getAircraftLat());
        if (param.getAircraftLng() != null) target.setAircraftLng(param.getAircraftLng());
        if (param.getAircraftAlt() != null) target.setAircraftAlt(param.getAircraftAlt());
        if (param.getGimbalPitch() != null) target.setGimbalPitch(param.getGimbalPitch());
        if (param.getGimbalYaw() != null) target.setGimbalYaw(param.getGimbalYaw());
        if (param.getGimbalRoll() != null) target.setGimbalRoll(param.getGimbalRoll());
        if (param.getThermalRoi() != null) target.setThermalRoi(param.getThermalRoi());
    }

    private void copyGeoFields(FireEventCreateParam param, FireEventHistoryEntity target) {
        if (param == null || target == null) return;
        target.setGeoMethod(param.getGeoMethod());
        target.setGeoErrorRadiusM(param.getGeoErrorRadiusM());
        target.setGeoQuality(param.getGeoQuality());
        target.setGeoSourceTs(param.getGeoSourceTs());
        target.setAircraftLat(param.getAircraftLat());
        target.setAircraftLng(param.getAircraftLng());
        target.setAircraftAlt(param.getAircraftAlt());
        target.setGimbalPitch(param.getGimbalPitch());
        target.setGimbalYaw(param.getGimbalYaw());
        target.setGimbalRoll(param.getGimbalRoll());
        target.setThermalRoi(param.getThermalRoi());
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusMeters = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 当 caller (ai-service) 没带 lat/lng 时,从 Redis 里取该 deviceSn 最新 OSD 自动填入。
     * OSD 也查不到则抛 MISSING_DEVICE_POSITION (HTTP 400),不持久化半残事件。
     */
    private void fillPositionFromOsdIfMissing(FireEventCreateParam param) {
        if (param.getLat() != null && param.getLng() != null) {
            return;
        }
        String sn = param.getDeviceSn();
        if (sn == null || sn.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSING_DEVICE_POSITION,
                "deviceSn missing; cannot infer position");
        }
        // M4T 飞 RC PLUS 直连时 OSD 缓存为 OsdRcDrone；Dock 飞机为 OsdDockDrone。两种都要兼容。
        Float lat = null, lng = null, height = null;
        try {
            Optional<OsdDockDrone> dockOpt = deviceRedisService.getDeviceOsd(sn, OsdDockDrone.class);
            if (dockOpt.isPresent()) {
                OsdDockDrone osd = dockOpt.get();
                lat = osd.getLatitude();
                lng = osd.getLongitude();
                height = osd.getHeight();
            }
        } catch (ClassCastException ignored) {
            // 缓存类型不是 OsdDockDrone, 下一步用 OsdRcDrone 重试
        }
        if (lat == null || lng == null) {
            try {
                Optional<OsdRcDrone> rcOpt = deviceRedisService.getDeviceOsd(sn, OsdRcDrone.class);
                if (rcOpt.isPresent()) {
                    OsdRcDrone osd = rcOpt.get();
                    lat = osd.getLatitude();
                    lng = osd.getLongitude();
                    height = osd.getHeight();
                }
            } catch (ClassCastException ignored) {
            }
        }
        if (lat == null || lng == null) {
            FireEventEntity latest = latestKnownPosition(sn);
            if (latest != null) {
                lat = latest.getLat().floatValue();
                lng = latest.getLng().floatValue();
                if (latest.getAlt() != null) {
                    height = latest.getAlt().floatValue();
                }
                log.warn("OSD has no latitude/longitude for device {}, using latest fire event position id={}",
                    sn, latest.getId());
            } else {
                throw new Fc100BusinessException(Fc100ErrorCode.MISSING_DEVICE_POSITION,
                    "OSD has no latitude/longitude for device: " + sn);
            }
        }
        if (param.getLat() == null) param.setLat(lat.doubleValue());
        if (param.getLng() == null) param.setLng(lng.doubleValue());
        if (param.getAlt() == null && height != null) {
            param.setAlt(height.doubleValue());
        }
        if (param.getAircraftLat() == null) param.setAircraftLat(lat.doubleValue());
        if (param.getAircraftLng() == null) param.setAircraftLng(lng.doubleValue());
        if (param.getAircraftAlt() == null && height != null) {
            param.setAircraftAlt(height.doubleValue());
        }
    }

    private FireEventEntity latestKnownPosition(String deviceSn) {
        return eventMapper.selectOne(new QueryWrapper<FireEventEntity>()
            .eq("device_sn", deviceSn)
            .isNotNull("lat")
            .isNotNull("lng")
            .eq("deleted", 0)
            .orderByDesc("event_timestamp")
            .last("limit 1"));
    }

    private String findActiveMissionNo(Long fireEventId) {
        FireMissionEntity mission = findActiveMission(fireEventId);
        return mission == null ? null : mission.getMissionNo();
    }

    private FireMissionEntity findActiveMission(Long fireEventId) {
        List<FireMissionEntity> list = missionMapper.selectList(
            new QueryWrapper<FireMissionEntity>()
                .eq("fire_event_id", fireEventId)
                .eq("deleted", 0)
                .in("status", ACTIVE_MISSION_STATUSES)
                .orderByDesc("create_time"));
        return list.isEmpty() ? null : list.get(0);
    }

    private void requireOperationWiring() {
        if (operationIncidentService == null || operationIncidentMapper == null || incidentStateMachine == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INTERNAL_ERROR,
                "fire event decision flow is not wired");
        }
    }

    private FireEventEntity requireEvent(String eventId) {
        FireEventEntity event = eventMapper.selectOne(new QueryWrapper<FireEventEntity>()
            .eq("deleted", 0)
            .and(w -> {
                w.eq("event_id", eventId);
                if (eventId != null && eventId.matches("\\d+")) {
                    w.or().eq("id", Long.parseLong(eventId));
                }
            }));
        if (event == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND,
                "fire event not found: " + eventId);
        }
        return event;
    }

    private OperationIncidentEntity findActiveIncident(Long fireEventId) {
        List<OperationIncidentEntity> list = operationIncidentMapper.selectList(
            new QueryWrapper<OperationIncidentEntity>()
                .eq("fire_event_id", fireEventId)
                .in("status", ACTIVE_INCIDENT_STATUSES)
                .orderByDesc("create_time")
                .last("limit 1"));
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private FireMissionEntity createDraftMission(FireEventEntity event, Long incidentId, long now) {
        FireMissionEntity draft = new FireMissionEntity();
        draft.setMissionNo(noGen.next());
        draft.setWorkspaceId(event.getWorkspaceId());
        draft.setFireEventId(event.getId());
        draft.setIncidentId(incidentId);
        draft.setAttemptIndex(1);
        draft.setStatus(FireMissionStatus.CREATED.name());
        draft.setReleasePolicy(ReleasePolicy.MANUAL_CONFIRM.name());
        draft.setReleaseExecutionMode(ReleaseExecutionMode.OFFICIAL_HOOK_MANUAL.name());
        draft.setVersion(0L);
        draft.setIsHighConfidence(event.getConfidence() != null && event.getConfidence().compareTo(HIGH) >= 0 ? 1 : 0);
        draft.setDeleted(0);
        draft.setCreateTime(now);
        draft.setUpdateTime(now);
        missionMapper.insert(draft);
        log.info("created draft mission {} from confirmed fire event {} (confidence={}, highConf={})",
            draft.getMissionNo(), event.getEventId(), event.getConfidence(), draft.getIsHighConfidence());
        return draft;
    }

    private boolean isPrecise(FireEventEntity event) {
        return event != null && "PRECISE".equalsIgnoreCase(event.getGeoQuality());
    }

    private RecheckAdvice recheckAdvice(FireEventEntity event) {
        if (!isPrecise(event)) {
            return new RecheckAdvice(true, "需复测或人工标注坐标");
        }
        if (event.getThermalTemperature() != null
            && event.getThermalTemperature() >= thermalProperties.getSaturationTempC()) {
            return new RecheckAdvice(true, "测温可能饱和，需结合热源面积复测");
        }
        return new RecheckAdvice(true, "确认后建议复测火情边界与处置效果");
    }

    private void updateIncidentRecheckAdvice(Long incidentId, RecheckAdvice advice, long now) {
        if (incidentId == null || operationIncidentMapper == null) return;
        OperationIncidentEntity update = new OperationIncidentEntity();
        update.setId(incidentId);
        update.setRecommendedRecheck(advice.recommended ? 1 : 0);
        update.setRecheckReason(advice.reason);
        update.setUpdateTime(now);
        operationIncidentMapper.updateById(update);
    }

    private void updateIncidentRecheckResult(Long incidentId, String reason, long now) {
        if (incidentId == null || operationIncidentMapper == null) return;
        OperationIncidentEntity update = new OperationIncidentEntity();
        update.setId(incidentId);
        update.setRecommendedRecheck(0);
        update.setRecheckReason(reason);
        update.setUpdateTime(now);
        operationIncidentMapper.updateById(update);
    }

    private RecheckDecision decideRecheck(FireEventEntity event, FireEventRecheckResultParam param, boolean saturated) {
        boolean suggestedResolved = "RESOLVED".equalsIgnoreCase(param.getSuggestion());
        boolean flameCleared = !Boolean.TRUE.equals(param.getFlameVisible());
        double previousArea = previousHotArea(event);
        double currentArea = param.getHotAreaM2() == null ? Double.MAX_VALUE : param.getHotAreaM2();
        boolean areaCleared = previousArea > 0 ? currentArea <= previousArea * 0.5 : currentArea <= 0.1;
        boolean temperatureDropped = event.getThermalTemperature() == null
            || param.getMaxTemp() == null
            || param.getMaxTemp() < event.getThermalTemperature();

        if (!suggestedResolved) {
            return new RecheckDecision(false, "复测建议继续处置");
        }
        if (saturated && !(flameCleared && areaCleared && temperatureDropped)) {
            return new RecheckDecision(false, "测温可能饱和，不能仅凭绝对温度下降判定解除，需热源面积同步下降且无明火");
        }
        if (flameCleared && temperatureDropped) {
            return new RecheckDecision(true, "复测建议火情已解除");
        }
        return new RecheckDecision(false, "复测仍存在明火或温度未下降，建议继续处置");
    }

    private double previousHotArea(FireEventEntity event) {
        if (event == null || event.getThermalRoi() == null || event.getThermalRoi().isBlank()) {
            return -1.0;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> roi = JSON.readValue(event.getThermalRoi(), Map.class);
            Object area = roi.get("areaM2");
            if (area == null) area = roi.get("area_m2");
            if (area instanceof Number) return ((Number) area).doubleValue();
            Object width = roi.get("width");
            Object height = roi.get("height");
            if (width instanceof Number && height instanceof Number) {
                return ((Number) width).doubleValue() * ((Number) height).doubleValue();
            }
        } catch (Exception ignored) {
        }
        return -1.0;
    }

    private void insertDecisionHistory(FireEventEntity event, String action, String operatorId, long now) {
        FireEventHistoryEntity history = historyFromEvent(event, now);
        history.setSourceEventId(operatorId);
        history.setAction(action);
        historyMapper.insert(history);
    }

    private void insertRecheckHistory(FireEventEntity event, FireEventRecheckResultParam param,
                                      RecheckDecision decision, long now) {
        FireEventHistoryEntity history = historyFromEvent(event, now);
        history.setSourceEventId(param.getOperatorId());
        history.setThermalTemperature(param.getMaxTemp());
        history.setAction("RECHECK_RESULT");
        historyMapper.insert(history);
    }

    private FireEventHistoryEntity historyFromEvent(FireEventEntity event, long now) {
        FireEventHistoryEntity history = new FireEventHistoryEntity();
        history.setFireEventId(event.getId());
        history.setEventId(event.getEventId());
        history.setWorkspaceId(event.getWorkspaceId());
        history.setSource(event.getSource());
        history.setDeviceSn(event.getDeviceSn());
        history.setConfidence(event.getConfidence());
        history.setFireLevel(event.getFireLevel());
        history.setLat(event.getLat());
        history.setLng(event.getLng());
        history.setAlt(event.getAlt());
        history.setAltitudeReference(event.getAltitudeReference());
        history.setGeoMethod(event.getGeoMethod());
        history.setGeoErrorRadiusM(event.getGeoErrorRadiusM());
        history.setGeoQuality(event.getGeoQuality());
        history.setGeoSourceTs(event.getGeoSourceTs());
        history.setAircraftLat(event.getAircraftLat());
        history.setAircraftLng(event.getAircraftLng());
        history.setAircraftAlt(event.getAircraftAlt());
        history.setGimbalPitch(event.getGimbalPitch());
        history.setGimbalYaw(event.getGimbalYaw());
        history.setGimbalRoll(event.getGimbalRoll());
        history.setThermalRoi(event.getThermalRoi());
        history.setThermalTemperature(event.getThermalTemperature());
        history.setTemperatureUnit(event.getTemperatureUnit());
        history.setThermalImageUrl(event.getThermalImageUrl());
        history.setVisibleImageUrl(event.getVisibleImageUrl());
        history.setEventTimestamp(now);
        history.setCreateTime(now);
        return history;
    }

    private IncidentTransitCommand.IncidentTransitCommandBuilder incidentCmd(Long incidentId,
                                                                            OperationIncidentEvent event,
                                                                            FireEventActionParam param,
                                                                            HttpServletRequest request) {
        return incidentCmd(incidentId, event, param.getOperatorId(), param.getReason(), request);
    }

    private IncidentTransitCommand.IncidentTransitCommandBuilder incidentCmd(Long incidentId,
                                                                            OperationIncidentEvent event,
                                                                            String operatorId,
                                                                            String remark,
                                                                            HttpServletRequest request) {
        return IncidentTransitCommand.builder()
            .incidentId(incidentId)
            .event(event)
            .operatorId(operatorId)
            .clientIp(request == null ? null : request.getRemoteAddr())
            .requestId(request == null ? null : request.getHeader("X-Request-Id"))
            .idempotencyKey(request == null ? null : request.getHeader("X-Idempotency-Key"))
            .remark(remark);
    }

    private FireEventDTO toFireEventDto(FireEventEntity entity) {
        FireEventDTO dto = new FireEventDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setLocationQuality(normalizeLocationQuality(entity.getGeoQuality()));
        fillActiveMission(dto, entity.getId());
        return dto;
    }

    private OperationIncidentDTO toIncidentDto(OperationIncidentEntity entity) {
        if (entity == null) return null;
        OperationIncidentDTO dto = new OperationIncidentDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    private String normalizeLocationQuality(String quality) {
        if (quality == null || quality.isBlank()) return "UNKNOWN";
        String normalized = quality.toUpperCase();
        if ("AUTO_WAYPOINT_READY".equals(normalized)) return "PRECISE";
        if ("MANUAL".equals(normalized)) return "MANUAL_MARKED";
        if ("LOW_ACCURACY".equals(normalized) || "DEM_MISSING".equals(normalized)
            || "RTK_NOT_FIXED".equals(normalized) || "GEO_SNAPSHOT_INCOMPLETE".equals(normalized)) {
            return "ESTIMATED";
        }
        return normalized;
    }

    private static class RecheckAdvice {
        private final boolean recommended;
        private final String reason;

        private RecheckAdvice(boolean recommended, String reason) {
            this.recommended = recommended;
            this.reason = reason;
        }
    }

    private static class RecheckDecision {
        private final boolean resolved;
        private final String reason;

        private RecheckDecision(boolean resolved, String reason) {
            this.resolved = resolved;
            this.reason = reason;
        }
    }

    @Override
    public FireEventDTO get(String eventId) {
        QueryWrapper<FireEventEntity> query = new QueryWrapper<FireEventEntity>()
            .eq("deleted", 0)
            .and(w -> {
                w.eq("event_id", eventId);
                if (eventId != null && eventId.matches("\\d+")) {
                    w.or().eq("id", Long.parseLong(eventId));
                }
            });
        FireEventEntity e = eventMapper.selectOne(
            query);
        if (e == null) return null;
        return toFireEventDto(e);
    }

    @Override
    public List<FireEventDTO> list(String workspaceId, String status, int limit) {
        QueryWrapper<FireEventEntity> qw = new QueryWrapper<FireEventEntity>()
            .eq("deleted", 0)
            .orderByDesc("last_seen_time")
            .orderByDesc("create_time")
            .last("LIMIT " + limit);
        if (workspaceId != null && !workspaceId.isEmpty()) {
            qw.eq("workspace_id", workspaceId);
        }
        if (status != null && !status.isEmpty()) {
            qw.eq("status", status);
        }
        return eventMapper.selectList(qw).stream()
            .map(this::toFireEventDto)
            .collect(java.util.stream.Collectors.toList());
    }

    private void fillActiveMission(FireEventDTO dto, Long fireEventId) {
        FireMissionEntity mission = findActiveMission(fireEventId);
        if (mission != null) {
            dto.setMissionNo(mission.getMissionNo());
            dto.setMissionStatus(mission.getStatus());
        }
    }

    @Override
    public List<FireEventHistoryDTO> listHistory(String eventId, int limit) {
        FireEventEntity event = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", eventId).eq("deleted", 0));
        if (event == null) {
            return List.of();
        }
        int cappedLimit = Math.max(1, Math.min(limit, 500));
        return historyMapper.selectList(new QueryWrapper<FireEventHistoryEntity>()
                .eq("fire_event_id", event.getId())
                .orderByDesc("event_timestamp")
                .orderByDesc("id")
                .last("LIMIT " + cappedLimit))
            .stream()
            .map(h -> {
                FireEventHistoryDTO d = new FireEventHistoryDTO();
                BeanUtils.copyProperties(h, d);
                return d;
            })
            .collect(java.util.stream.Collectors.toList());
    }
}
