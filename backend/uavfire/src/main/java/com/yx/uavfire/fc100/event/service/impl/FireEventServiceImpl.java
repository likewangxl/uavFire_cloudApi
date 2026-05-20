package com.yx.uavfire.fc100.event.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
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

    private static final BigDecimal LOW = new BigDecimal("0.75");
    private static final BigDecimal HIGH = new BigDecimal("0.90");

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
    private final FireMissionMapper missionMapper;
    private final MissionNoGenerator noGen;
    private final Clock clock;
    private final IDeviceRedisService deviceRedisService;

    public FireEventServiceImpl(FireEventMapper em, FireMissionMapper mm,
                                MissionNoGenerator g, Clock c,
                                IDeviceRedisService deviceRedisService) {
        this.eventMapper = em;
        this.missionMapper = mm;
        this.noGen = g;
        this.clock = c;
        this.deviceRedisService = deviceRedisService;
    }

    @Override
    @Transactional
    public FireEventCreateResponse create(FireEventCreateParam param) {
        fillPositionFromOsdIfMissing(param);

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
                    : existing.getStatus());
        }

        long now = clock.now();
        FireEventEntity e = new FireEventEntity();
        BeanUtils.copyProperties(param, e);
        e.setEventTimestamp(Instant.parse(param.getTimestamp()).toEpochMilli());
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

        if (!autoCreate) {
            return new FireEventCreateResponse(e.getId(), e.getEventId(),
                false, null, e.getStatus());
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
            true, m.getMissionNo(), FireMissionStatus.WAITING_REVIEW.name());
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
            throw new Fc100BusinessException(Fc100ErrorCode.MISSING_DEVICE_POSITION,
                "OSD has no latitude/longitude for device: " + sn);
        }
        if (param.getLat() == null) param.setLat(lat.doubleValue());
        if (param.getLng() == null) param.setLng(lng.doubleValue());
        if (param.getAlt() == null && height != null) {
            param.setAlt(height.doubleValue());
        }
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
}
