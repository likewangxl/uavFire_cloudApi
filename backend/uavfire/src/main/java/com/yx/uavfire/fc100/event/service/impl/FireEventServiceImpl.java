package com.yx.uavfire.fc100.event.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.model.dto.FireEventHistoryDTO;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.enums.FireEventStatus;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class FireEventServiceImpl implements FireEventService {

    private static final BigDecimal LOW = new BigDecimal("0.10");
    private static final BigDecimal HIGH = new BigDecimal("0.90");
    private static final double MERGE_RADIUS_METERS = 10.0;
    private static final long MERGE_WINDOW_MS = 30 * 60 * 1000L;

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

    private final FireEventMapper eventMapper;
    private final FireEventHistoryMapper historyMapper;
    private final FireMissionMapper missionMapper;
    private final MissionNoGenerator noGen;
    private final Clock clock;
    private final IDeviceRedisService deviceRedisService;

    public FireEventServiceImpl(FireEventMapper em, FireEventHistoryMapper hm, FireMissionMapper mm,
                                MissionNoGenerator g, Clock c,
                                IDeviceRedisService deviceRedisService) {
        this.eventMapper = em;
        this.historyMapper = hm;
        this.missionMapper = mm;
        this.noGen = g;
        this.clock = c;
        this.deviceRedisService = deviceRedisService;
    }

    @Override
    @Transactional
    public FireEventCreateResponse create(FireEventCreateParam param) {
        fillPositionFromOsdIfMissing(param);
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
        FireEventEntity mergeCandidate = findMergeCandidate(param, eventTs);
        if (mergeCandidate != null) {
            MergeResult mergeResult = mergeIntoExisting(mergeCandidate, param, eventTs, now);
            insertHistory(mergeCandidate, param, eventTs, now, "MERGED");
            String activeMissionNo = findActiveMissionNo(mergeCandidate.getId());
            return new FireEventCreateResponse(
                mergeCandidate.getId(),
                mergeCandidate.getEventId(),
                false,
                activeMissionNo,
                mergeCandidate.getStatus(),
                false,
                true,
                mergeResult.notificationRequired,
                mergeResult.notificationReason);
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
        boolean autoCreate = c.compareTo(LOW) >= 0;
        e.setStatus(autoCreate
            ? FireEventStatus.MISSION_CREATED.name()
            : FireEventStatus.LOW_CONFIDENCE.name());
        eventMapper.insert(e);
        insertHistory(e, param, eventTs, now, "CREATED");

        if (!autoCreate) {
            return new FireEventCreateResponse(e.getId(), e.getEventId(),
                false, null, e.getStatus(), true, false, true, "CREATED");
        }

        // 2. 自动建 WAITING_REVIEW 任务
        FireMissionEntity m = new FireMissionEntity();
        m.setMissionNo(noGen.next());
        m.setWorkspaceId(e.getWorkspaceId());
        m.setFireEventId(e.getId());
        m.setAttemptIndex(1);
        m.setStatus(FireMissionStatus.WAITING_REVIEW.name());
        m.setVersion(0L);
        m.setIsHighConfidence(c.compareTo(HIGH) >= 0 ? 1 : 0);
        m.setDeleted(0);
        m.setCreateTime(now);
        m.setUpdateTime(now);
        missionMapper.insert(m);

        log.info("auto-created mission {} from fire event {} (confidence={}, highConf={})",
            m.getMissionNo(), e.getEventId(), c, m.getIsHighConfidence());

        return new FireEventCreateResponse(e.getId(), e.getEventId(),
            true, m.getMissionNo(), FireMissionStatus.WAITING_REVIEW.name(),
            true, false, true, "CREATED");
    }

    private FireEventEntity findMergeCandidate(FireEventCreateParam param, long eventTs) {
        if (param.getLat() == null || param.getLng() == null || param.getDeviceSn() == null || param.getDeviceSn().isBlank()) {
            return null;
        }
        List<FireEventEntity> candidates = eventMapper.selectList(new QueryWrapper<FireEventEntity>()
            .eq("workspace_id", workspaceIdOf(param))
            .eq("device_sn", param.getDeviceSn())
            .eq("deleted", 0)
            .ge("last_seen_time", eventTs - MERGE_WINDOW_MS)
            .last("limit 20"));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        FireEventEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (FireEventEntity candidate : candidates) {
            if (candidate.getLat() == null || candidate.getLng() == null) {
                continue;
            }
            long candidateTs = candidate.getLastSeenTime() != null
                ? candidate.getLastSeenTime()
                : candidate.getEventTimestamp() != null ? candidate.getEventTimestamp() : 0L;
            if (Math.abs(eventTs - candidateTs) > MERGE_WINDOW_MS) {
                continue;
            }
            double distance = distanceMeters(param.getLat(), param.getLng(), candidate.getLat(), candidate.getLng());
            if (distance <= MERGE_RADIUS_METERS && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private MergeResult mergeIntoExisting(FireEventEntity existing, FireEventCreateParam param, long eventTs, long now) {
        boolean levelUpgraded = fireLevelRank(param.getFireLevel()) > fireLevelRank(existing.getFireLevel());
        existing.setLastSeenTime(eventTs);
        existing.setReportCount(existing.getReportCount() == null ? 2 : existing.getReportCount() + 1);
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
        if (param.getThermalTemperature() != null) {
            existing.setThermalTemperature(param.getThermalTemperature());
        }
        if (param.getThermalImageUrl() != null && !param.getThermalImageUrl().isBlank()) {
            existing.setThermalImageUrl(param.getThermalImageUrl());
        }
        if (param.getVisibleImageUrl() != null && !param.getVisibleImageUrl().isBlank()) {
            existing.setVisibleImageUrl(param.getVisibleImageUrl());
        }
        eventMapper.updateById(existing);
        return levelUpgraded
            ? new MergeResult(true, "LEVEL_UPGRADED")
            : new MergeResult(false, "DUPLICATE_SUPPRESSED");
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

    private String workspaceIdOf(FireEventCreateParam param) {
        return param.getWorkspaceId() != null ? param.getWorkspaceId() : "DEFAULT";
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

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusMeters = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static class MergeResult {
        private final boolean notificationRequired;
        private final String notificationReason;

        private MergeResult(boolean notificationRequired, String notificationReason) {
            this.notificationRequired = notificationRequired;
            this.notificationReason = notificationReason;
        }
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
        List<FireMissionEntity> list = missionMapper.selectList(
            new QueryWrapper<FireMissionEntity>()
                .eq("fire_event_id", fireEventId)
                .eq("deleted", 0)
                .in("status", ACTIVE_MISSION_STATUSES)
                .orderByDesc("create_time"));
        return list.isEmpty() ? null : list.get(0).getMissionNo();
    }

    @Override
    public FireEventDTO get(String eventId) {
        FireEventEntity e = eventMapper.selectOne(
            new QueryWrapper<FireEventEntity>().eq("event_id", eventId));
        if (e == null) return null;
        FireEventDTO d = new FireEventDTO();
        BeanUtils.copyProperties(e, d);
        return d;
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
            .map(e -> {
                FireEventDTO d = new FireEventDTO();
                BeanUtils.copyProperties(e, d);
                return d;
            })
            .collect(java.util.stream.Collectors.toList());
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
