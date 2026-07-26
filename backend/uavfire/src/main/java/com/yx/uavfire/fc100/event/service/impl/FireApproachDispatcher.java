package com.yx.uavfire.fc100.event.service.impl;

import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.enums.FireEventStatus;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
@Slf4j
public class FireApproachDispatcher {

    private static final String ACTION = "fire-confirmation-mission";

    // 地面判定线：OSD 相对起飞点高度低于此值视为未起飞。地面触发抵近不会起飞，
    // 但 agent 会白等 90s fly-to 超时——地面点火调试时每个事件都会踩一轮。
    private static final double MIN_AIRBORNE_HEIGHT_M = 2.0;

    private final Supplier<IDualStreamService> dualStreamServiceSupplier;
    private final Clock clock;
    private final IDeviceRedisService deviceRedisService;

    @Value("${fc100.fire-event.auto-approach-enabled:false}")
    private boolean autoApproachEnabled = false;

    @Value("${fc100.fire-event.auto-approach-cooldown-ms:600000}")
    private long autoApproachCooldownMs = 600000L;

    // Single-instance assumption: multi-instance deployments need a distributed cooldown lock/cache.
    private final ConcurrentHashMap<Long, Long> lastDispatchAtByEventId = new ConcurrentHashMap<>();

    @Autowired
    public FireApproachDispatcher(
        ObjectProvider<IDualStreamService> dualStreamServiceProvider,
        Clock clock,
        ObjectProvider<IDeviceRedisService> deviceRedisServiceProvider) {
        this.dualStreamServiceSupplier = dualStreamServiceProvider::getObject;
        this.clock = clock;
        this.deviceRedisService = deviceRedisServiceProvider.getIfAvailable();
    }

    FireApproachDispatcher(IDualStreamService dualStreamService, Clock clock) {
        this(dualStreamService, clock, null);
    }

    FireApproachDispatcher(IDualStreamService dualStreamService, Clock clock, IDeviceRedisService deviceRedisService) {
        this.dualStreamServiceSupplier = () -> dualStreamService;
        this.clock = clock;
        this.deviceRedisService = deviceRedisService;
    }

    public void dispatchIfEligible(FireEventEntity event) {
        if (!autoApproachEnabled || event == null) {
            return;
        }
        if (event.getId() == null || event.getLat() == null || event.getLng() == null) {
            return;
        }
        if (!StringUtils.hasText(event.getDeviceSn())) {
            return;
        }
        if (FireEventStatus.MISSION_CREATED.name().equals(event.getStatus())
            || FireEventStatus.IGNORED.name().equals(event.getStatus())) {
            return;
        }
        // 未起飞不派单，且不消耗冷却额度——起飞后同一事件继续上报即可正常触发抵近
        if (!aircraftAirborne(event.getDeviceSn())) {
            log.info(
                "fire auto approach skipped: aircraft on ground eventId={} drone={}",
                event.getId(),
                event.getDeviceSn());
            return;
        }

        long now = clock.now();
        AtomicBoolean cooledDown = new AtomicBoolean(false);
        lastDispatchAtByEventId.compute(event.getId(), (eventId, lastDispatchAt) -> {
            if (lastDispatchAt != null && now - lastDispatchAt < autoApproachCooldownMs) {
                cooledDown.set(true);
                return lastDispatchAt;
            }
            return now;
        });
        if (cooledDown.get()) {
            log.info(
                "fire auto approach dispatch skipped by cooldown eventId={} drone={} lat={} lng={} cooldownMs={}",
                event.getId(),
                event.getDeviceSn(),
                event.getLat(),
                event.getLng(),
                autoApproachCooldownMs);
            return;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lat", event.getLat());
        params.put("lng", event.getLng());
        if (event.getAlt() != null) {
            params.put("alt", event.getAlt());
        }
        params.put("taskId", taskIdFor(event));

        try {
            dualStreamServiceSupplier.get().issueCommand(event.getDeviceSn(), ACTION, params);
            log.info(
                "fire auto approach command dispatched eventId={} drone={} lat={} lng={} alt={} taskId={}",
                event.getId(),
                event.getDeviceSn(),
                event.getLat(),
                event.getLng(),
                event.getAlt(),
                params.get("taskId"));
        } catch (Exception ex) {
            log.warn(
                "fire auto approach command dispatch failed eventId={} drone={} lat={} lng={} reason={}",
                event.getId(),
                event.getDeviceSn(),
                event.getLat(),
                event.getLng(),
                ex.getMessage(),
                ex);
        }
    }

    private String taskIdFor(FireEventEntity event) {
        String eventId = event.getEventId();
        if (StringUtils.hasText(eventId)) {
            int split = eventId.lastIndexOf('-');
            if (split > 0 && split < eventId.length() - 1) {
                String suffix = eventId.substring(split + 1);
                if (suffix.length() >= 10 && suffix.chars().allMatch(Character::isDigit)) {
                    return eventId.substring(0, split);
                }
            }
        }
        return "fire-" + event.getDeviceSn();
    }

    /**
     * OSD 相对起飞点高度判断是否已起飞。读不到 OSD/高度时放行（fail-open）：
     * 宁可让 agent 端 90s 超时兜底，也不能因为 OSD 缺失挡掉真实飞行中的抵近。
     */
    private boolean aircraftAirborne(String deviceSn) {
        if (deviceRedisService == null) {
            return true;
        }
        Float height = null;
        try {
            Optional<OsdDockDrone> dockOpt = deviceRedisService.getDeviceOsd(deviceSn, OsdDockDrone.class);
            if (dockOpt.isPresent()) {
                height = dockOpt.get().getHeight();
            }
        } catch (Exception ignored) {
            // 缓存类型不是 OsdDockDrone，下一步用 OsdRcDrone 重试
        }
        if (height == null) {
            try {
                Optional<OsdRcDrone> rcOpt = deviceRedisService.getDeviceOsd(deviceSn, OsdRcDrone.class);
                if (rcOpt.isPresent()) {
                    height = rcOpt.get().getHeight();
                }
            } catch (Exception ignored) {
            }
        }
        if (height == null) {
            return true;
        }
        return height >= MIN_AIRBORNE_HEIGHT_M;
    }
}
