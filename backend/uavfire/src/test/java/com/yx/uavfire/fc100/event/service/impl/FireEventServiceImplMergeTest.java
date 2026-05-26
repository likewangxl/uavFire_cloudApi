package com.yx.uavfire.fc100.event.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.enums.FireEventStatus;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FireEventServiceImplMergeTest {

    private final FireEventMapper events = mock(FireEventMapper.class);
    private final FireEventHistoryMapper histories = mock(FireEventHistoryMapper.class);
    private final FireMissionMapper missions = mock(FireMissionMapper.class);
    private final MissionNoGenerator noGen = mock(MissionNoGenerator.class);
    private final Clock clock = mock(Clock.class);
    private final IDeviceRedisService redis = mock(IDeviceRedisService.class);

    private FireEventServiceImpl build() {
        when(clock.now()).thenReturn(1779163500000L);
        when(noGen.next()).thenReturn("M-001");
        return new FireEventServiceImpl(events, histories, missions, noGen, clock, redis);
    }

    @Test
    void mergesSameDeviceEventWithinTenMetersAndFiveMinutesWithoutNewMission() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        FireEventCreateResponse response = build().create(param("new-event", 34.658650, 109.340650, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(1L, response.getFireEventId());
        assertEquals("old-event", response.getEventId());
        assertFalse(response.getMissionCreated());
        assertTrue(response.getMerged());
        assertFalse(response.getNotificationRequired());
        assertEquals("DUPLICATE_SUPPRESSED", response.getNotificationReason());

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        FireEventEntity updated = updateCaptor.getValue();
        assertEquals(1779163440000L, updated.getLastSeenTime());
        assertEquals(4, updated.getReportCount());
        assertEquals("new-event", updated.getLastSourceEventId());
        assertEquals(new BigDecimal("0.72"), updated.getConfidence());
        verify(events, never()).insert(any(FireEventEntity.class));
        verify(missions, never()).insert(any(FireMissionEntity.class));
    }

    @Test
    void createsNewEventWhenCandidateIsOutsideTenMeters() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "MEDIUM", "0.45", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventCreateResponse response = build().create(param("far-event", 34.659600, 109.341600, "MEDIUM", "0.72", 1779163440000L));

        assertEquals(2L, response.getFireEventId());
        assertEquals("far-event", response.getEventId());
        assertTrue(response.getMissionCreated());
        assertTrue(response.getCreated());
        assertFalse(response.getMerged());
        assertEquals("CREATED", response.getNotificationReason());
        verify(events).insert(any(FireEventEntity.class));
        verify(missions).insert(any(FireMissionEntity.class));
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
    void mergeUsesLatestConfidenceInsteadOfKeepingStalePeakScore() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "HIGH", "1.0000", 1779163200000L);
        when(events.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(events.selectList(any(QueryWrapper.class))).thenReturn(List.of(existing));

        build().create(param("thermal-reference", 34.658650, 109.340650, "LOW", "0.011", 1779163440000L));

        ArgumentCaptor<FireEventEntity> updateCaptor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).updateById(updateCaptor.capture());
        assertEquals(new BigDecimal("0.011"), updateCaptor.getValue().getConfidence());
    }

    @Test
    void mergeUsesLatestFireLevelForListWhileKeepingHistorySnapshots() {
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
    void mergingRiskUpgradeRequiresNotificationAndIncrementsVersion() {
        FireEventEntity existing = existingEvent(1L, 34.658600, 109.340600, "LOW", "0.18", 1779163200000L);
        existing.setNotificationVersion(2);
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
        assertEquals(3, updated.getNotificationVersion());
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
