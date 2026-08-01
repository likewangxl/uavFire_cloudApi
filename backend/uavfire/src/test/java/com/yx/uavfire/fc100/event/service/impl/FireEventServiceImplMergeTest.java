package com.yx.uavfire.fc100.event.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.model.dto.FireGeoSnapshotDTO;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.enums.FireEventStatus;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.model.param.FireLaserLocationParam;
import com.yx.uavfire.fc100.event.service.FireGeoLocationResult;
import com.yx.uavfire.fc100.event.service.FireGeoLocationService;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.ReleaseExecutionMode;
import com.yx.uavfire.fc100.mission.model.enums.ReleasePolicy;
import com.yx.uavfire.manage.model.dto.DualStreamCommandDTO;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.yx.uavfire.manage.service.IDualStreamService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FireEventServiceImplMergeTest {

    private final FireEventMapper events = mock(FireEventMapper.class);
    private final FireEventHistoryMapper histories = mock(FireEventHistoryMapper.class);
    private final FireMissionMapper missions = mock(FireMissionMapper.class);
    private final MissionNoGenerator noGen = mock(MissionNoGenerator.class);
    private final Clock clock = mock(Clock.class);
    private final IDeviceRedisService redis = mock(IDeviceRedisService.class);
    private final IDualStreamService dualStream = mock(IDualStreamService.class);

    private FireEventServiceImpl build() {
        when(clock.now()).thenReturn(1779163500000L);
        when(noGen.next()).thenReturn("M-001");
        when(missions.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        return new FireEventServiceImpl(events, histories, missions, noGen, clock, redis);
    }

    private FireEventServiceImpl buildWithGeoService(FireGeoLocationService geoService) {
        when(clock.now()).thenReturn(1779163500000L);
        when(noGen.next()).thenReturn("M-001");
        when(missions.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        return new FireEventServiceImpl(events, histories, missions, noGen, clock, redis, geoService);
    }

    private FireEventServiceImpl buildWithApproachDispatcher(Object ignored) {
        when(clock.now()).thenReturn(1779163500000L);
        when(noGen.next()).thenReturn("M-001");
        when(missions.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        return build();
    }

    private Object dispatcher(boolean enabled) {
        return new Object();
    }

    @Test
    void create_merges_into_nearby_active_event() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("new-event", 34.658870, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(1L, response.getFireEventId());
        assertEquals("old-event", response.getEventId());
        assertFalse(response.getMissionCreated());
        assertTrue(response.getMerged());
        assertFalse(response.getNotificationRequired());
        assertEquals("MERGED_NEARBY", response.getNotificationReason());

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        FireEventEntity updated = updateCaptor.getValue();
        assertEquals(1779163500000L, updated.getLastSeenTime());
        assertEquals(4, updated.getReportCount());
        assertEquals("new-event", updated.getLastSourceEventId());
        assertEquals(new BigDecimal("0.72"), updated.getConfidence());
        assertEquals("MEDIUM", updated.getFireLevel());
        assertEquals(1, updated.getNotificationVersion());
        verify(events, never()).insert(any(FireEventEntity.class));
        verify(missions, never()).insert(any(FireMissionEntity.class));
    }

    @Test
    void create_skips_merge_beyond_radius() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("far-event", 34.659140, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertEquals("far-event", response.getEventId());
        assertFalse(response.getMissionCreated());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        assertEquals("CREATED", response.getNotificationReason());
        verify(events).insert(any(FireEventEntity.class));
        verify(missions, never()).insert(any(FireMissionEntity.class));
    }

    @Test
    void laserLocatingCreate_dispatchesVisibleFireHoldCoordinator() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity entity = inv.getArgument(0);
            entity.setId(7L);
            return 1;
        });
        FireEventServiceImpl service = build();
        FireEventCreateParam request =
                param("task-visible-1779163440000", 34.659140, 109.340600,
                        "MEDIUM", "0.72", 1779163440000L);
        request.setDeviceSn("DRONE-1");
        request.setGeoMethod("LASER_RANGEFINDER");
        request.setGeoQuality("LASER_LOCATING");
        request.setVisibleRoi(Map.of(
                "x", 0.4, "y", 0.3, "width", 0.2, "height", 0.2));

        service.create(request);

        verifyNoInteractions(dualStream);
    }

    @Test
    void create_skips_merge_when_window_expired() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163500000L - 1800001L);
        existing.setStatus(FireEventStatus.CANDIDATE.name());
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("expired-window", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        verify(events).insert(any(FireEventEntity.class));
    }

    @Test
    void adaptive_radius_precise_pair_rejects_second_fire() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setGeoErrorRadiusM(5.0);
        FireEventCreateParam preciseReport = param("precise-second-fire", 34.658825, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        preciseReport.setGeoErrorRadiusM(5.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(preciseReport);

        assertEquals(2L, response.getFireEventId());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        verify(events).insert(any(FireEventEntity.class));
        verify(events, never()).updateById(any(FireEventEntity.class));
    }

    @Test
    void adaptive_radius_coarse_pair_merges_wide() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setGeoErrorRadiusM(30.0);
        FireEventCreateParam coarseReport = param("coarse-same-fire", 34.659049, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        coarseReport.setGeoErrorRadiusM(30.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(coarseReport);

        assertEquals(1L, response.getFireEventId());
        assertTrue(response.getMerged());
        assertFalse(response.getCreated());
        verify(events).updateById(any(FireEventEntity.class));
        verify(events, never()).insert(any(FireEventEntity.class));
    }

    @Test
    void adaptive_radius_null_error_falls_back_to_base() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setGeoErrorRadiusM(null);
        FireEventCreateParam reportWithoutError = param("base-radius-fallback", 34.658914, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        reportWithoutError.setGeoErrorRadiusM(5.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(reportWithoutError);

        assertEquals(1L, response.getFireEventId());
        assertTrue(response.getMerged());
        assertFalse(response.getCreated());
        verify(events).updateById(any(FireEventEntity.class));
        verify(events, never()).insert(any(FireEventEntity.class));
    }

    @Test
    void adaptive_radius_clamped_to_max() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setGeoErrorRadiusM(50.0);
        FireEventCreateParam distantReport = param("beyond-max-radius", 34.659229, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        distantReport.setGeoErrorRadiusM(50.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(distantReport);

        assertEquals(2L, response.getFireEventId());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        verify(events).insert(any(FireEventEntity.class));
        verify(events, never()).updateById(any(FireEventEntity.class));
    }

    @Test
    void mission_created_event_merges_beyond_window() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163500000L - 7200000L);
        existing.setStatus(FireEventStatus.MISSION_CREATED.name());
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("mission-created-recheck", 34.658780, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(1L, response.getFireEventId());
        assertTrue(response.getMerged());
        assertFalse(response.getCreated());
        verify(events).updateById(any(FireEventEntity.class));
        verify(events, never()).insert(any(FireEventEntity.class));
    }

    @Test
    void stale_new_event_outside_window_not_merged() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163500000L - 7200000L);
        existing.setStatus(FireEventStatus.CANDIDATE.name());
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("stale-new-recheck", 34.658780, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        verify(events).insert(any(FireEventEntity.class));
        verify(events, never()).updateById(any(FireEventEntity.class));
    }

    @Test
    void dedup_lock_acquired_and_released_for_created_path() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.acquireNamedLock("fire_event_dedup:DEFAULT", 3)).thenReturn(1);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("lock-created", 34.658600, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertTrue(response.getCreated());
        verify(events).acquireNamedLock("fire_event_dedup:DEFAULT", 3);
        verify(events).releaseNamedLock("fire_event_dedup:DEFAULT");
    }

    @Test
    void dedup_lock_acquired_and_released_for_merged_path() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.acquireNamedLock("fire_event_dedup:DEFAULT", 3)).thenReturn(1);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("lock-merged", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertTrue(response.getMerged());
        verify(events).acquireNamedLock("fire_event_dedup:DEFAULT", 3);
        verify(events).releaseNamedLock("fire_event_dedup:DEFAULT");
    }

    @Test
    void lock_timeout_proceeds_without_blocking() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.acquireNamedLock("fire_event_dedup:DEFAULT", 3)).thenReturn(0);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        Logger logger = (Logger) LoggerFactory.getLogger(FireEventServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        FireEventCreateResponse response;
        try {
            response = build().create(param("lock-timeout", 34.658600, 109.340600, "MEDIUM", "0.72", 1779163440000L));
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(response.getCreated());
        assertTrue(appender.list.stream().anyMatch(event ->
            event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("fire event spatial dedup lock not acquired")
                && event.getFormattedMessage().contains("proceeding without lock")));
        verify(events).acquireNamedLock("fire_event_dedup:DEFAULT", 3);
        verify(events, never()).releaseNamedLock("fire_event_dedup:DEFAULT");
    }

    @Test
    void create_skips_merge_for_ignored_event() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setStatus(FireEventStatus.IGNORED.name());
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("ignored-nearby", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        verify(events).insert(any(FireEventEntity.class));
    }

    @Test
    void createStoresCandidateEventWithoutAutoMissionBeforeManualConfirmation() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("policy-default-event", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L));

        assertFalse(response.getMissionCreated());
        assertEquals(FireEventStatus.CANDIDATE.name(), response.getStatus());
        ArgumentCaptor<FireEventEntity> eventCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).insert(eventCaptor.capture());
        assertEquals(FireEventStatus.CANDIDATE.name(), eventCaptor.getValue().getStatus());
        assertEquals("PENDING", eventCaptor.getValue().getConfirmedStatus());
        verify(missions, never()).insert(any(FireMissionEntity.class));
    }

    @Test
    void auto_approach_dispatches_on_created_event() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        FireEventCreateParam p = param("fire-DRONE-1-1779163440000", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L);
        p.setAlt(88.5);
        when(dualStream.issueCommand(any(), any(), any())).thenReturn(new DualStreamCommandDTO());

        FireEventCreateResponse response = buildWithApproachDispatcher(dispatcher(true)).create(p);

        assertEquals("CREATED", response.getNotificationReason());
        verifyNoInteractions(dualStream);
    }

    @Test
    void auto_approach_dispatches_on_merged_event() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setEventId("fire-DRONE-1-1779163200000");
        existing.setStatus(FireEventStatus.CANDIDATE.name());
        existing.setAlt(66.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(dualStream.issueCommand(any(), any(), any())).thenReturn(new DualStreamCommandDTO());

        FireEventCreateResponse response = buildWithApproachDispatcher(dispatcher(true))
            .create(param("merge-event", 34.658650, 109.340650, "MEDIUM", "0.72", 1779163440000L));

        assertEquals("MERGED_NEARBY", response.getNotificationReason());
        verifyNoInteractions(dualStream);
    }

    @Test
    void auto_approach_skipped_when_aircraft_on_ground() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        // OSD 相对高度 0.3m = 未起飞：地面点火调试时不派抵近（否则 agent 白等 90s fly-to 超时）
        OsdDockDrone grounded = new OsdDockDrone();
        grounded.setHeight(0.3f);
        when(redis.getDeviceOsd(eq("DRONE-1"), eq(OsdDockDrone.class))).thenReturn(Optional.of(grounded));
        Object dispatcher = dispatcher(true);

        buildWithApproachDispatcher(dispatcher)
            .create(param("fire-DRONE-1-1779163440000", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L));

        verify(dualStream, never()).issueCommand(any(), any(), any());
    }

    @Test
    void auto_approach_dispatches_when_airborne_by_osd_height() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        OsdDockDrone airborne = new OsdDockDrone();
        airborne.setHeight(35.0f);
        when(redis.getDeviceOsd(eq("DRONE-1"), eq(OsdDockDrone.class))).thenReturn(Optional.of(airborne));
        when(dualStream.issueCommand(any(), any(), any())).thenReturn(new DualStreamCommandDTO());
        Object dispatcher = dispatcher(true);

        buildWithApproachDispatcher(dispatcher)
            .create(param("fire-DRONE-1-1779163440000", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L));

        verifyNoInteractions(dualStream);
    }

    @Test
    void auto_approach_disabled_by_default() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        Object dispatcher = dispatcher(false);

        FireEventCreateResponse response = buildWithApproachDispatcher(dispatcher)
            .create(param("fire-DRONE-1-1779163440000", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals("CREATED", response.getNotificationReason());
        verify(dualStream, never()).issueCommand(any(), any(), any());
    }

    @Test
    void auto_approach_respects_cooldown() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setStatus(FireEventStatus.CANDIDATE.name());
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(dualStream.issueCommand(any(), any(), any())).thenReturn(new DualStreamCommandDTO());
        FireEventServiceImpl service = buildWithApproachDispatcher(dispatcher(true));

        service.create(param("merge-event-1", 34.658650, 109.340650, "MEDIUM", "0.72", 1779163440000L));
        service.create(param("merge-event-2", 34.658660, 109.340660, "MEDIUM", "0.73", 1779163450000L));

        verifyNoInteractions(dualStream);
    }

    @Test
    void auto_approach_skips_event_without_coordinates() {
        FireEventEntity event = existingEvent(2L, 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L);
        event.setStatus(FireEventStatus.CANDIDATE.name());
        event.setLat(null);
        event.setLng(null);

        // Automatic approach dispatcher was removed from production.

        verify(dualStream, never()).issueCommand(any(), any(), any());
    }

    @Test
    void auto_approach_skips_mission_created_status() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = buildWithApproachDispatcher(dispatcher(true))
            .create(param("merge-event", 34.658650, 109.340650, "MEDIUM", "0.72", 1779163440000L));

        assertEquals("MERGED_NEARBY", response.getNotificationReason());
        verify(dualStream, never()).issueCommand(any(), any(), any());
    }

    @Test
    void dispatch_failure_does_not_break_create() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        when(dualStream.issueCommand(any(), any(), any())).thenThrow(new IllegalStateException("queue down"));

        FireEventCreateResponse response = buildWithApproachDispatcher(dispatcher(true))
            .create(param("fire-DRONE-1-1779163440000", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertEquals("CREATED", response.getNotificationReason());
        verifyNoInteractions(dualStream);
    }

    @Test
    void mergesSameDeviceEventWithinThirtyMinuteWindowForThermalReferenceFrames() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("thermal-reference", 34.658650, 109.340650, "LOW", "0.011", 1779163920000L));

        assertEquals(1L, response.getFireEventId());
        assertFalse(response.getCreated());
        assertTrue(response.getMerged());
        verify(events).updateById(any(FireEventEntity.class));
        verify(events, never()).insert(any(FireEventEntity.class));
        verify(missions, never()).insert(any(FireMissionEntity.class));
    }

    @Test
    void merge_downgrade_overwrites_level_without_notification() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        existing.setNotificationVersion(3);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("thermal-reference", 34.658650, 109.340650, "LOW", "0.011", 1779163440000L));

        assertTrue(response.getMerged());
        assertFalse(response.getNotificationRequired());
        assertEquals("MERGED_NEARBY", response.getNotificationReason());

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        assertEquals(new BigDecimal("0.011"), updateCaptor.getValue().getConfidence());
        assertEquals("LOW", updateCaptor.getValue().getFireLevel());
        assertEquals(3, updateCaptor.getValue().getNotificationVersion());
        assertEquals(FireEventStatus.MISSION_CREATED.name(), updateCaptor.getValue().getStatus());
    }

    @Test
    void merge_keeps_history_snapshots_from_new_report() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        existing.setThermalTemperature(95.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        build().create(param("low-event", 34.658650, 109.340650, "LOW", "0.011", 1779163440000L));

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        FireEventEntity updated = updateCaptor.getValue();
        assertEquals("LOW", updated.getFireLevel());
        assertEquals(new BigDecimal("0.011"), updated.getConfidence());

        ArgumentCaptor<FireEventHistoryEntity> historyCaptor = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(histories).insert(historyCaptor.capture());
        FireEventHistoryEntity history = historyCaptor.getValue();
        assertEquals(1L, history.getFireEventId());
        assertEquals("old-event", history.getEventId());
        assertEquals("low-event", history.getSourceEventId());
        assertEquals("LOW", history.getFireLevel());
        assertEquals(new BigDecimal("0.011"), history.getConfidence());
        assertEquals("MERGED", history.getAction());
        assertEquals(1779163440000L, history.getEventTimestamp());
    }

    @Test
    void merge_upgrades_level_bumps_notification_version() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "LOW", "0.18", 1779163200000L);
        existing.setNotificationVersion(1);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("high-event", 34.658650, 109.340650, "HIGH", "0.91", 1779163440000L));

        assertTrue(response.getMerged());
        assertTrue(response.getNotificationRequired());
        assertEquals("LEVEL_UPGRADED", response.getNotificationReason());

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        FireEventEntity updated = updateCaptor.getValue();
        assertEquals("HIGH", updated.getFireLevel());
        assertEquals(new BigDecimal("0.91"), updated.getConfidence());
        assertEquals(2, updated.getNotificationVersion());
    }

    @Test
    void merge_same_level_keeps_notification_version() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        existing.setNotificationVersion(5);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("same-level-event", 34.658650, 109.340650, "MEDIUM", "0.72", 1779163440000L));

        assertTrue(response.getMerged());
        assertFalse(response.getNotificationRequired());
        assertEquals("MERGED_NEARBY", response.getNotificationReason());

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        FireEventEntity updated = updateCaptor.getValue();
        assertEquals("MEDIUM", updated.getFireLevel());
        assertEquals(5, updated.getNotificationVersion());
    }

    @Test
    void merge_updates_confidence_from_new_report() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        build().create(param("confidence-update", 34.658650, 109.340650, "MEDIUM", "0.72", 1779163440000L));

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        assertEquals(new BigDecimal("0.72"), updateCaptor.getValue().getConfidence());
    }

    @Test
    void merge_keeps_better_geo() {
        FireEventEntity betterExisting = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        betterExisting.setGeoErrorRadiusM(5.0);
        betterExisting.setGeoMethod("OLD_GEO");
        FireEventCreateParam worseNew = param("worse-geo", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        worseNew.setGeoErrorRadiusM(20.0);
        worseNew.setGeoMethod("NEW_GEO");
        worseNew.setGeoQuality("LOW_ACCURACY");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(betterExisting));

        build().create(worseNew);

        ArgumentCaptor<FireEventEntity> firstUpdate = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(firstUpdate.capture());
        assertEquals(34.658600, firstUpdate.getValue().getLat(), 1e-6);
        assertEquals("OLD_GEO", firstUpdate.getValue().getGeoMethod());

        reset(events, histories, missions, noGen, clock, redis);
        FireEventEntity worseExisting = existingEvent(2L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        worseExisting.setGeoErrorRadiusM(20.0);
        worseExisting.setGeoMethod("OLD_GEO");
        FireEventCreateParam betterNew = param("better-geo", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        betterNew.setAlt(388.0);
        betterNew.setAltitudeReference("AGL");
        betterNew.setGeoErrorRadiusM(5.0);
        betterNew.setGeoMethod("NEW_GEO");
        betterNew.setGeoQuality("PRECISE");
        betterNew.setGeoSourceTs(1779163439000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(worseExisting));

        build().create(betterNew);

        ArgumentCaptor<FireEventEntity> secondUpdate = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(secondUpdate.capture());
        FireEventEntity updated = secondUpdate.getValue();
        assertEquals(34.658650, updated.getLat(), 1e-6);
        assertEquals(109.340600, updated.getLng(), 1e-6);
        assertEquals(388.0, updated.getAlt(), 1e-6);
        assertEquals("AGL", updated.getAltitudeReference());
        assertEquals("NEW_GEO", updated.getGeoMethod());
        assertEquals(5.0, updated.getGeoErrorRadiusM(), 1e-6);
        assertEquals("PRECISE", updated.getGeoQuality());
        assertEquals(1779163439000L, updated.getGeoSourceTs());
    }

    @Test
    void thermal_event_with_laser_geo_creates_precise_fire_event() {
        FireGeoLocationService geoService = mock(FireGeoLocationService.class);
        FireGeoSnapshotDTO snapshot = new FireGeoSnapshotDTO().setSourceTs(1779163439000L);
        FireEventCreateParam laser = param("laser-event", 34.658650, 109.340600, "HIGH", "0.95", 1779163440000L);
        laser.setAlt(386.0);
        laser.setGeoMethod("LASER_RANGEFINDER");
        laser.setGeoErrorRadiusM(5.0);
        laser.setGeoSnapshot(snapshot);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        buildWithGeoService(geoService).create(laser);

        verify(geoService, never()).resolve(any(FireGeoSnapshotDTO.class));
        ArgumentCaptor<FireEventEntity> insertCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).insert(insertCaptor.capture());
        FireEventEntity inserted = insertCaptor.getValue();
        assertEquals(34.658650, inserted.getLat(), 1e-6);
        assertEquals(109.340600, inserted.getLng(), 1e-6);
        assertEquals(386.0, inserted.getAlt(), 1e-6);
        assertEquals("LASER_RANGEFINDER", inserted.getGeoMethod());
        assertEquals(5.0, inserted.getGeoErrorRadiusM(), 1e-6);
        assertEquals("PRECISE", inserted.getGeoQuality());
        assertEquals(1779163440000L, inserted.getGeoSourceTs());
    }

    @Test
    void laser_location_success_updates_original_event_once() {
        FireEventEntity locating = existingEvent(
            7L, 34.9607, 109.3163, "HIGH", "0.81", 1779163440000L);
        locating.setEventId("visible-laser-event");
        locating.setGeoMethod("LASER_RANGEFINDER");
        locating.setGeoQuality("LASER_LOCATING");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(locating);
        FireLaserLocationParam fix = new FireLaserLocationParam()
            .setFireLat(34.961234)
            .setFireLng(109.317654)
            .setFireAlt(386.2)
            .setSourceTs(1779163445000L)
            .setGeoErrorRadiusM(5.0);
        FireEventServiceImpl service = build();

        assertTrue(service.applyLaserLocation("visible-laser-event", fix));
        assertFalse(service.applyLaserLocation("visible-laser-event", fix));

        assertEquals(34.961234, locating.getLat(), 1e-6);
        assertEquals(109.317654, locating.getLng(), 1e-6);
        assertEquals(386.2, locating.getAlt(), 1e-6);
        assertEquals("LASER_RANGEFINDER", locating.getGeoMethod());
        assertEquals("PRECISE", locating.getGeoQuality());
        assertEquals(5.0, locating.getGeoErrorRadiusM(), 1e-6);
        assertEquals(1779163445000L, locating.getGeoSourceTs());
        verify(events).updateById(locating);
    }

    @Test
    void laser_failure_cannot_overwrite_precise_location() {
        FireEventEntity precise = existingEvent(
            8L, 34.961234, 109.317654, "HIGH", "0.81", 1779163440000L);
        precise.setEventId("already-precise");
        precise.setGeoMethod("LASER_RANGEFINDER");
        precise.setGeoQuality("PRECISE");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(precise);

        boolean changed = build().markLaserLocationFailed(
            "already-precise", "target-lost", 1779163445000L);

        assertFalse(changed);
        assertEquals("PRECISE", precise.getGeoQuality());
        verify(events, never()).updateById(any(FireEventEntity.class));
    }

    @Test
    void laser_locating_event_does_not_dispatch_legacy_auto_approach() {
        FireEventCreateParam locating = param(
            "visible-locating", 34.9607, 109.3163, "HIGH", "0.81", 1779163440000L);
        locating.setGeoMethod("LASER_RANGEFINDER");
        locating.setGeoQuality("LASER_LOCATING");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(9L);
            return 1;
        });

        build().create(locating);

        verifyNoInteractions(dualStream);
    }

    @Test
    void thermal_event_without_geo_uses_existing_resolution() {
        FireGeoLocationService geoService = mock(FireGeoLocationService.class);
        FireGeoSnapshotDTO snapshot = new FireGeoSnapshotDTO().setSourceTs(1779163439000L);
        when(geoService.resolve(snapshot)).thenReturn(new FireGeoLocationResult()
                .setLat(34.658700)
                .setLng(109.340700)
                .setAlt(381.0)
                .setGeoMethod("RAY_DEM_RTK")
                .setGeoErrorRadiusM(30.0)
                .setGeoQuality("LOW_ACCURACY")
                .setGeoSourceTs(1779163439000L));
        FireEventCreateParam rayDem = param("raydem-event", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        rayDem.setLat(null);
        rayDem.setLng(null);
        rayDem.setGeoSnapshot(snapshot);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        buildWithGeoService(geoService).create(rayDem);

        verify(geoService).resolve(snapshot);
        ArgumentCaptor<FireEventEntity> insertCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).insert(insertCaptor.capture());
        FireEventEntity inserted = insertCaptor.getValue();
        assertEquals(34.658700, inserted.getLat(), 1e-6);
        assertEquals(109.340700, inserted.getLng(), 1e-6);
        assertEquals(381.0, inserted.getAlt(), 1e-6);
        assertEquals("RAY_DEM_RTK", inserted.getGeoMethod());
        assertEquals(30.0, inserted.getGeoErrorRadiusM(), 1e-6);
        assertEquals("LOW_ACCURACY", inserted.getGeoQuality());
        assertEquals(1779163439000L, inserted.getGeoSourceTs());
    }

    @Test
    void merge_upgrades_raydem_event_to_laser_geo_with_smaller_error_radius() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.72", 1779163200000L);
        existing.setGeoMethod("RAY_DEM_RTK");
        existing.setGeoErrorRadiusM(30.0);
        existing.setGeoQuality("LOW_ACCURACY");
        FireEventCreateParam laser = param("laser-merge", 34.658650, 109.340600, "MEDIUM", "0.95", 1779163440000L);
        laser.setAlt(386.0);
        laser.setGeoMethod("LASER_RANGEFINDER");
        laser.setGeoErrorRadiusM(5.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(laser);

        assertTrue(response.getMerged());
        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        FireEventEntity updated = updateCaptor.getValue();
        assertEquals(34.658650, updated.getLat(), 1e-6);
        assertEquals(109.340600, updated.getLng(), 1e-6);
        assertEquals(386.0, updated.getAlt(), 1e-6);
        assertEquals("LASER_RANGEFINDER", updated.getGeoMethod());
        assertEquals(5.0, updated.getGeoErrorRadiusM(), 1e-6);
        assertEquals("PRECISE", updated.getGeoQuality());
    }

    @Test
    void merge_takes_max_temperature() {
        FireEventEntity hotExisting = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        hotExisting.setThermalTemperature(120.0);
        FireEventCreateParam coolerNew = param("cooler", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        coolerNew.setThermalTemperature(90.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(hotExisting));

        build().create(coolerNew);

        ArgumentCaptor<FireEventEntity> firstUpdate = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(firstUpdate.capture());
        assertEquals(120.0, firstUpdate.getValue().getThermalTemperature(), 1e-6);

        reset(events, histories, missions, noGen, clock, redis);
        FireEventEntity nullExisting = existingEvent(2L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        nullExisting.setThermalTemperature(null);
        FireEventCreateParam measuredNew = param("measured", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        measuredNew.setThermalTemperature(88.0);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(nullExisting));

        build().create(measuredNew);

        ArgumentCaptor<FireEventEntity> secondUpdate = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(secondUpdate.capture());
        assertEquals(88.0, secondUpdate.getValue().getThermalTemperature(), 1e-6);
    }

    @Test
    void dedup_disabled_preserves_current_behavior() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });
        FireEventServiceImpl service = build();
        ReflectionTestUtils.setField(service, "fireEventDedupEnabled", false);

        FireEventCreateResponse response = service.create(param("dedup-disabled", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        verify(events).insert(any(FireEventEntity.class));
    }

    @Test
    void create_without_coordinates_uses_osd_backfill_for_spatial_dedup() {
        // 串行确认链的事件不带火点坐标（由 OSD 回填飞机位置）：回填后必须参与空间去重，
        // 否则悬停在同一火点上每分钟建一个重复事件（2026-07-26 实飞同一盆火 10 连报）。
        OsdDockDrone osd = new OsdDockDrone();
        osd.setLatitude(34.65865f);
        osd.setLongitude(109.3406f);
        when(redis.getDeviceOsd(any(), any())).thenReturn(Optional.of(osd));
        FireEventEntity existing = existingEvent(1L, 34.658650, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        FireEventCreateParam p = param("no-coordinates", 34.658650, 109.340600, "MEDIUM", "0.72", 1779163440000L);
        p.setLat(null);
        p.setLng(null);

        FireEventCreateResponse response = build().create(p);

        assertEquals(1L, response.getFireEventId());
        assertTrue(response.getMerged());
        assertEquals("MERGED_NEARBY", response.getNotificationReason());
        verify(events, never()).insert(any(FireEventEntity.class));
    }

    @Test
    void event_id_dedup_takes_precedence() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        FireEventCreateResponse response = build().create(param("old-event", 34.658600, 109.340600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(1L, response.getFireEventId());
        assertEquals("EXISTING_EVENT_ID", response.getNotificationReason());
        verify(events, never()).selectList(any(QueryWrapper.class));
        verify(events, never()).updateById(any(FireEventEntity.class));
        verify(events, never()).insert(any(FireEventEntity.class));
    }

    @Test
    void listIncludesActiveMissionNoAndStatusForFireEventActions() {
        FireEventEntity event = existingEvent(7L, 34.658600, 109.340600, "HIGH", "0.95", 1779163200000L);
        event.setDetectionKind("FIRE");
        event.setDetectionStatus("RESULT_DURABLE");
        event.setLocationStatus("PRECISE");
        event.setFlightStatus("MISSION_RESUMED");
        event.setGeoMethod("LASER_RANGEFINDER");
        event.setLastAgentSequence(9L);
        FireMissionEntity mission = new FireMissionEntity();
        mission.setMissionNo("MISSION-001");
        mission.setStatus("WAITING_REVIEW");
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(event));

        FireEventServiceImpl service = build();
        when(missions.selectList(any(QueryWrapper.class))).thenReturn(List.of(mission));
        List<FireEventDTO> list = service.list("DEFAULT", null, 50);

        assertEquals("MISSION-001", list.get(0).getMissionNo());
        assertEquals("WAITING_REVIEW", list.get(0).getMissionStatus());
        assertEquals("FIRE", list.get(0).getDetectionKind());
        assertEquals("RESULT_DURABLE", list.get(0).getDetectionStatus());
        assertEquals("PRECISE", list.get(0).getLocationStatus());
        assertEquals("MISSION_RESUMED", list.get(0).getFlightStatus());
        assertEquals("LASER_RANGEFINDER", list.get(0).getGeoMethod());
        assertEquals(9L, list.get(0).getLastAgentSequence());
    }

    @Test
    void historyProjectsAgentDetectionAndLocationContractFields() {
        FireEventEntity event = existingEvent(7L, 34.658600, 109.340600, "HIGH", "0.95", 1779163200000L);
        FireEventHistoryEntity history = new FireEventHistoryEntity();
        history.setFireEventId(7L);
        history.setEventId("event-history-1");
        history.setDetectionKind("SMOKE");
        history.setDetectionStatus("RESULT_DURABLE");
        history.setLocationStatus("PRECISE");
        history.setFlightStatus("MISSION_RESUMED");
        history.setGeoMethod("LASER_RANGEFINDER");
        history.setAgentSequence(8L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(event);
        when(histories.selectList(any(QueryWrapper.class))).thenReturn(List.of(history));

        var result = build().listHistory(event.getEventId(), 50);

        assertEquals(1, result.size());
        assertEquals("SMOKE", result.get(0).getDetectionKind());
        assertEquals("RESULT_DURABLE", result.get(0).getDetectionStatus());
        assertEquals("PRECISE", result.get(0).getLocationStatus());
        assertEquals("MISSION_RESUMED", result.get(0).getFlightStatus());
        assertEquals("LASER_RANGEFINDER", result.get(0).getGeoMethod());
        assertEquals(8L, result.get(0).getAgentSequence());
    }

    @Test
    void createPersistsGeoQualityFieldsForSolvedFirePoint() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(9L);
            return 1;
        });

        FireEventCreateParam p = param("geo-event", 34.658600, 109.340600, "HIGH", "0.95", 1779163440000L);
        p.setGeoMethod("RAY_DEM_RTK");
        p.setGeoQuality("AUTO_WAYPOINT_READY");
        p.setGeoErrorRadiusM(6.5);
        p.setGeoSourceTs(1779163439900L);
        p.setAircraftLat(34.658000);
        p.setAircraftLng(109.340000);
        p.setAircraftAlt(120.0);
        p.setGimbalPitch(-45.0);
        p.setGimbalYaw(12.0);
        p.setGimbalRoll(0.0);
        p.setThermalRoi("{\"x\":0.4,\"y\":0.4,\"width\":0.2,\"height\":0.2}");

        build().create(p);

        ArgumentCaptor<FireEventEntity> eventCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).insert(eventCaptor.capture());
        FireEventEntity persisted = eventCaptor.getValue();
        assertEquals("RAY_DEM_RTK", persisted.getGeoMethod());
        assertEquals("AUTO_WAYPOINT_READY", persisted.getGeoQuality());
        assertEquals(6.5, persisted.getGeoErrorRadiusM(), 1e-6);
        assertEquals(1779163439900L, persisted.getGeoSourceTs());
        assertEquals(34.658000, persisted.getAircraftLat(), 1e-6);
        assertEquals("{\"x\":0.4,\"y\":0.4,\"width\":0.2,\"height\":0.2}", persisted.getThermalRoi());

        ArgumentCaptor<FireEventHistoryEntity> historyCaptor = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(histories).insert(historyCaptor.capture());
        assertEquals("AUTO_WAYPOINT_READY", historyCaptor.getValue().getGeoQuality());
    }

    @Test
    void createPersistsThermalMeasureRoiWhenGeoSnapshotIsAbsent() {
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(9L);
            return 1;
        });

        FireEventCreateParam p = param("roi-event", 34.658600, 109.340600, "MEDIUM", "0.48", 1779163440000L);
        p.setThermalMeasureRoi(Map.of("x", 0.62, "y", 0.44, "width", 0.07, "height", 0.07));

        build().create(p);

        ArgumentCaptor<FireEventEntity> eventCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).insert(eventCaptor.capture());
        assertEquals("{\"x\":0.62,\"y\":0.44,\"width\":0.07,\"height\":0.07}", eventCaptor.getValue().getThermalRoi());

        ArgumentCaptor<FireEventHistoryEntity> historyCaptor = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(histories).insert(historyCaptor.capture());
        assertEquals("{\"x\":0.62,\"y\":0.44,\"width\":0.07,\"height\":0.07}", historyCaptor.getValue().getThermalRoi());
    }

    @Test
    void getSupportsNumericFireEventIdFromMissionDetailPage() {
        FireEventEntity event = existingEvent(555L, 34.658600, 109.340600, "HIGH", "0.95", 1779163200000L);
        event.setEventId("fire-event-555");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(event);
        when(missions.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        FireEventDTO dto = build().get("555");

        assertEquals("fire-event-555", dto.getEventId());
        assertEquals(34.658600, dto.getLat());
        assertEquals(109.340600, dto.getLng());
    }

    @Test
    void attachVisibleImageUpdatesExistingEventAndWritesHistory() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        boolean updated = build().attachVisibleImage(
                "old-event",
                "visible-confirmation",
                "http://snapshots/actual-visible.jpg",
                "2026-05-18T18:40:05Z");

        assertTrue(updated);
        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        assertEquals("http://snapshots/actual-visible.jpg", updateCaptor.getValue().getVisibleImageUrl());

        ArgumentCaptor<FireEventHistoryEntity> historyCaptor = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(histories).insert(historyCaptor.capture());
        FireEventHistoryEntity history = historyCaptor.getValue();
        assertEquals("old-event", history.getEventId());
        assertEquals("visible-confirmation", history.getSourceEventId());
        assertEquals("VISIBLE_CONFIRM", history.getAction());
        assertEquals("http://snapshots/actual-visible.jpg", history.getVisibleImageUrl());
    }

    @Test
    void attachVisibleImageUsesAssociatedThermalImageInHistory() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        existing.setThermalImageUrl("http://snapshots/stale-thermal.jpg");
        existing.setThermalRoi("{\"x\":0.42,\"y\":0.46,\"width\":0.08,\"height\":0.08}");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        boolean updated = build().attachVisibleImage(
                "old-event",
                "visible-confirmation",
                "http://snapshots/actual-visible.jpg",
                "2026-05-18T18:40:05Z",
                "thermal-confirmation",
                "http://snapshots/associated-thermal.jpg");

        assertTrue(updated);
        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        assertEquals("http://snapshots/associated-thermal.jpg", updateCaptor.getValue().getThermalImageUrl());

        ArgumentCaptor<FireEventHistoryEntity> historyCaptor = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(histories).insert(historyCaptor.capture());
        FireEventHistoryEntity history = historyCaptor.getValue();
        assertEquals("visible-confirmation", history.getSourceEventId());
        assertEquals("VISIBLE_CONFIRM", history.getAction());
        assertEquals("http://snapshots/associated-thermal.jpg", history.getThermalImageUrl());
        assertEquals("http://snapshots/actual-visible.jpg", history.getVisibleImageUrl());
        assertEquals("{\"x\":0.42,\"y\":0.46,\"width\":0.08,\"height\":0.08}", history.getThermalRoi());
    }

    @Test
    void attachVisibleImageRejectsExplicitAssociationWithoutThermalImage() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        existing.setThermalImageUrl("http://snapshots/stale-thermal.jpg");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        boolean updated = build().attachVisibleImage(
                "old-event",
                "visible-confirmation",
                "http://snapshots/actual-visible.jpg",
                "2026-05-18T18:40:05Z",
                "thermal-confirmation",
                null);

        assertFalse(updated);
        verify(events, never()).updateById(any(FireEventEntity.class));
        verify(histories, never()).insert(any(FireEventHistoryEntity.class));
    }

    @Test
    void recordVisibleConfirmationStatusWritesAssociatedThermalAndVisibleEvidence() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        existing.setThermalImageUrl("http://snapshots/stale-thermal.jpg");
        existing.setThermalRoi("{\"x\":0.42,\"y\":0.46,\"width\":0.08,\"height\":0.08}");
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        boolean recorded = build().recordVisibleConfirmationStatus(
                "old-event",
                "visible-rejected",
                "VISIBLE_REJECTED",
                "http://snapshots/visible-low-score.jpg",
                "2026-05-18T18:40:05Z",
                "thermal-confirmation",
                "http://snapshots/associated-thermal.jpg");

        assertTrue(recorded);
        verify(events, never()).updateById(any(FireEventEntity.class));
        ArgumentCaptor<FireEventHistoryEntity> historyCaptor = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(histories).insert(historyCaptor.capture());
        FireEventHistoryEntity history = historyCaptor.getValue();
        assertEquals("old-event", history.getEventId());
        assertEquals("visible-rejected", history.getSourceEventId());
        assertEquals("VISIBLE_REJECTED", history.getAction());
        assertEquals("http://snapshots/associated-thermal.jpg", history.getThermalImageUrl());
        assertEquals("http://snapshots/visible-low-score.jpg", history.getVisibleImageUrl());
        assertEquals("{\"x\":0.42,\"y\":0.46,\"width\":0.08,\"height\":0.08}", history.getThermalRoi());
    }

    private FireEventCreateParam param(String eventId, double lat, double lng, String level, String confidence, long ts) {
        FireEventCreateParam p = new FireEventCreateParam();
        p.setEventId(eventId);
        p.setSource("M4T");
        p.setDeviceSn("DRONE-1");
        p.setWorkspaceId("DEFAULT");
        p.setLat(lat);
        p.setLng(lng);
        p.setConfidence(new BigDecimal(confidence));
        p.setFireLevel(level);
        p.setTimestamp(java.time.Instant.ofEpochMilli(ts).toString());
        p.setThermalImageUrl("thermal-" + eventId + ".jpg");
        return p;
    }

    private FireEventEntity existingEvent(Long id, double lat, double lng, String level, String confidence, long ts) {
        FireEventEntity e = new FireEventEntity();
        e.setId(id);
        e.setEventId("old-event");
        e.setWorkspaceId("DEFAULT");
        e.setSource("M4T");
        e.setDeviceSn("DRONE-1");
        e.setLat(lat);
        e.setLng(lng);
        e.setConfidence(new BigDecimal(confidence));
        e.setFireLevel(level);
        e.setThermalImageUrl("thermal-old.jpg");
        e.setEventTimestamp(ts);
        e.setLastSeenTime(ts);
        e.setReportCount(3);
        e.setLastSourceEventId("old-event");
        e.setNotificationVersion(1);
        e.setStatus(FireEventStatus.MISSION_CREATED.name());
        e.setDeleted(0);
        e.setCreateTime(ts);
        e.setUpdateTime(ts);
        return e;
    }
}
