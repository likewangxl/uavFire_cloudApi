package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.enums.FireEventStatus;
import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
@Slf4j
public class FireApproachDispatcher {

    private static final String ACTION = "fire-confirmation-mission";

    private final Supplier<IDualStreamService> dualStreamServiceSupplier;
    private final Clock clock;

    @Value("${fc100.fire-event.auto-approach-enabled:false}")
    private boolean autoApproachEnabled = false;

    @Value("${fc100.fire-event.auto-approach-cooldown-ms:600000}")
    private long autoApproachCooldownMs = 600000L;

    // Single-instance assumption: multi-instance deployments need a distributed cooldown lock/cache.
    private final ConcurrentHashMap<Long, Long> lastDispatchAtByEventId = new ConcurrentHashMap<>();

    @Autowired
    public FireApproachDispatcher(ObjectProvider<IDualStreamService> dualStreamServiceProvider, Clock clock) {
        this.dualStreamServiceSupplier = dualStreamServiceProvider::getObject;
        this.clock = clock;
    }

    FireApproachDispatcher(IDualStreamService dualStreamService, Clock clock) {
        this.dualStreamServiceSupplier = () -> dualStreamService;
        this.clock = clock;
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
}
