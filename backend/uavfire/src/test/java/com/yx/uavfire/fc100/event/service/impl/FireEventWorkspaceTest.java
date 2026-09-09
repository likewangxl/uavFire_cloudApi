package com.yx.uavfire.fc100.event.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.MissionNoGenerator;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.dao.FireEventHistoryMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.entity.FireEventHistoryEntity;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.manage.dao.IDeviceMapper;
import com.yx.uavfire.manage.model.entity.DeviceEntity;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FireEventWorkspaceTest {
    private final FireEventMapper events = mock(FireEventMapper.class);
    private final FireEventHistoryMapper histories = mock(FireEventHistoryMapper.class);
    private final IDeviceMapper devices = mock(IDeviceMapper.class);
    private final FireEventServiceImpl service = new FireEventServiceImpl(events, histories,
        mock(FireMissionMapper.class), mock(MissionNoGenerator.class), () -> 1788430237006L,
        mock(IDeviceRedisService.class));

    private FireEventCreateParam report(String workspace) {
        ReflectionTestUtils.setField(service, "deviceMapper", devices);
        ReflectionTestUtils.setField(service, "fireEventDedupEnabled", false);
        FireEventCreateParam p = new FireEventCreateParam();
        p.setEventId("workspace-regression");
        p.setSource("DJI_AGENT");
        p.setDeviceSn("AIRCRAFT-1");
        p.setWorkspaceId(workspace);
        p.setTimestamp("2026-09-03T10:10:31.541Z");
        p.setConfidence(new BigDecimal("0.8"));
        p.setFireLevel("HIGH");
        p.setGeoQuality("UNLOCATED");
        p.setLat(34.0);
        p.setLng(109.0);
        return p;
    }

    private DeviceEntity bound(String workspace) {
        return DeviceEntity.builder().deviceSn("AIRCRAFT-1").workspaceId(workspace).boundStatus(true).build();
    }

    private void assertStoredWorkspace(String expected) {
        ArgumentCaptor<FireEventEntity> event = ArgumentCaptor.forClass(FireEventEntity.class);
        ArgumentCaptor<FireEventHistoryEntity> history = ArgumentCaptor.forClass(FireEventHistoryEntity.class);
        verify(events).insert(event.capture());
        verify(histories).insert(history.capture());
        assertEquals(expected, event.getValue().getWorkspaceId());
        assertEquals(expected, history.getValue().getWorkspaceId());
        assertEquals(1788430231541L, event.getValue().getEventTimestamp());
        assertEquals("UNLOCATED", event.getValue().getGeoQuality());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DEFAULT", "  "})
    void missingOrPlaceholderWorkspaceUsesBoundAircraftForEventAndHistory(String workspace) {
        when(devices.selectList(any())).thenReturn(List.of(bound("workspace-a")));
        service.create(report(workspace));
        assertStoredWorkspace("workspace-a");
        ArgumentCaptor<QueryWrapper<DeviceEntity>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(devices).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("device_sn"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("AIRCRAFT-1"));
    }

    @Test
    void explicitWorkspaceIsNotReassigned() {
        service.create(report("explicit-workspace"));
        assertStoredWorkspace("explicit-workspace");
        verifyNoInteractions(devices);
    }

    @Test
    void unknownAircraftDoesNotBorrowLoginOrAnotherWorkspace() {
        when(devices.selectList(any())).thenReturn(List.of());
        service.create(report(null));
        assertStoredWorkspace("DEFAULT");
    }

    @Test
    void unboundAndAmbiguousAircraftAreNotAssigned() {
        when(devices.selectList(any())).thenReturn(List.of(bound("workspace-a"), bound("workspace-b")));
        service.create(report(null));
        assertStoredWorkspace("DEFAULT");
    }

    @Test
    void unboundAircraftIsNotAssigned() {
        DeviceEntity unbound = bound("workspace-a");
        unbound.setBoundStatus(false);
        when(devices.selectList(any())).thenReturn(List.of(unbound));
        service.create(report(null));
        assertStoredWorkspace("DEFAULT");
    }

    @Test
    void spatialDedupUsesResolvedWorkspace() {
        FireEventCreateParam p = report(null);
        p.setGeoQuality("PRECISE");
        ReflectionTestUtils.setField(service, "fireEventDedupEnabled", true);
        when(devices.selectList(any())).thenReturn(List.of(bound("workspace-a")));
        when(events.selectList(any())).thenReturn(List.of());
        service.create(p);
        ArgumentCaptor<QueryWrapper<FireEventEntity>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(events).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("workspace_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("workspace-a"));
    }
}
