package com.yx.uavfire.fc100.event.service.impl;

import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FireEventServiceImplOsdFillTest {

    private FireEventServiceImpl build(IDeviceRedisService redis, FireEventMapper events) {
        FireMissionMapper missions = mock(FireMissionMapper.class);
        MissionNoGenerator noGen = mock(MissionNoGenerator.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1779163200000L);
        when(noGen.next()).thenReturn("M-001");
        return new FireEventServiceImpl(events, missions, noGen, clock, redis);
    }

    private FireEventCreateParam paramWithoutPosition() {
        FireEventCreateParam p = new FireEventCreateParam();
        p.setEventId("zlm-demo-1779163200000");
        p.setSource("M4T");
        p.setDeviceSn("DRONE-1");
        p.setConfidence(new BigDecimal("0.78"));
        p.setFireLevel("MEDIUM");
        p.setTimestamp("2026-05-19T04:00:00.000Z");
        return p;
    }

    @Test
    void fillsLatLngFromOsdWhenMissing() {
        IDeviceRedisService redis = mock(IDeviceRedisService.class);
        FireEventMapper events = mock(FireEventMapper.class);
        OsdDockDrone osd = new OsdDockDrone();
        osd.setLatitude(31.2304f);
        osd.setLongitude(121.4737f);
        osd.setHeight(123.5f);
        when(redis.getDeviceOsd(eq("DRONE-1"), eq(OsdDockDrone.class))).thenReturn(Optional.of(osd));
        when(events.selectOne(any())).thenReturn(null);
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(1L);
            return 1;
        });

        FireEventServiceImpl service = build(redis, events);
        service.create(paramWithoutPosition());

        ArgumentCaptor<FireEventEntity> captor = ArgumentCaptor.forClass(FireEventEntity.class);
        verify(events).insert(captor.capture());
        FireEventEntity persisted = captor.getValue();
        assertEquals(31.2304, persisted.getLat(), 1e-4);
        assertEquals(121.4737, persisted.getLng(), 1e-4);
        assertEquals(123.5, persisted.getAlt(), 1e-2);
    }

    @Test
    void respectsCallerSuppliedPositionAndSkipsOsdLookup() {
        IDeviceRedisService redis = mock(IDeviceRedisService.class);
        FireEventMapper events = mock(FireEventMapper.class);
        FireEventCreateParam p = paramWithoutPosition();
        p.setLat(40.0);
        p.setLng(-74.0);
        when(events.selectOne(any())).thenReturn(null);
        when(events.insert(any(FireEventEntity.class))).thenAnswer(inv -> {
            FireEventEntity e = inv.getArgument(0);
            e.setId(2L);
            return 1;
        });

        FireEventServiceImpl service = build(redis, events);
        service.create(p);

        verify(redis, org.mockito.Mockito.never()).getDeviceOsd(any(), any());
    }

    @Test
    void throwsMissingDevicePositionWhenOsdNotCached() {
        IDeviceRedisService redis = mock(IDeviceRedisService.class);
        FireEventMapper events = mock(FireEventMapper.class);
        when(redis.getDeviceOsd(any(), eq(OsdDockDrone.class))).thenReturn(Optional.empty());

        FireEventServiceImpl service = build(redis, events);
        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> service.create(paramWithoutPosition()));
        assertEquals(Fc100ErrorCode.MISSING_DEVICE_POSITION, ex.getErrorCode());
    }

    @Test
    void throwsMissingDevicePositionWhenOsdLacksCoordinates() {
        IDeviceRedisService redis = mock(IDeviceRedisService.class);
        FireEventMapper events = mock(FireEventMapper.class);
        when(redis.getDeviceOsd(any(), eq(OsdDockDrone.class)))
            .thenReturn(Optional.of(new OsdDockDrone()));

        FireEventServiceImpl service = build(redis, events);
        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> service.create(paramWithoutPosition()));
        assertEquals(Fc100ErrorCode.MISSING_DEVICE_POSITION, ex.getErrorCode());
    }

    @Test
    void throwsMissingDevicePositionWhenDeviceSnAbsent() {
        IDeviceRedisService redis = mock(IDeviceRedisService.class);
        FireEventMapper events = mock(FireEventMapper.class);

        FireEventCreateParam p = paramWithoutPosition();
        p.setDeviceSn(null);

        FireEventServiceImpl service = build(redis, events);
        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> service.create(p));
        assertEquals(Fc100ErrorCode.MISSING_DEVICE_POSITION, ex.getErrorCode());
    }
}
