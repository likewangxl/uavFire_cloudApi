package com.dji.sample.wayline;

import com.dji.sample.wayline.dao.IPlannedWaylineMapper;
import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import com.dji.sample.wayline.model.entity.PlannedWaylineEntity;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sample.wayline.service.impl.PlannedWaylineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PlannedWaylineServiceTest {

    @Test
    void createShouldDefaultStatusToDraft() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        AtomicReference<PlannedWaylineEntity> inserted = new AtomicReference<>();
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity entity = invocation.getArgument(0);
            entity.setId(1);
            entity.setCreateTime(1000L);
            entity.setUpdateTime(1000L);
            inserted.set(entity);
            return 1;
        });
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper);

        PlannedWaylineDTO dto = service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypoints(List.of(new PlannedWaypointDTO()
                        .setOrder(1)
                        .setGcjLng(120.1)
                        .setGcjLat(30.2)
                        .setWgsLng(120.0)
                        .setWgsLat(30.1)
                        .setHeight(80.0)))
                .build());

        assertNotNull(dto);
        assertEquals("draft", dto.getStatus());
        assertEquals("workspace-001", dto.getWorkspaceId());
        assertEquals("alice", dto.getCreator());
        assertNotNull(inserted.get());
        assertEquals("draft", inserted.get().getStatus());
    }

    @Test
    void createShouldFailWhenInsertDoesNotPersist() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenReturn(0);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper);

        assertThrows(IllegalArgumentException.class, () -> service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypoints(List.of(new PlannedWaypointDTO()
                        .setOrder(1)
                        .setGcjLng(120.1)
                        .setGcjLat(30.2)
                        .setWgsLng(120.0)
                        .setWgsLat(30.1)
                        .setHeight(80.0)))
                .build()));
    }

    @Test
    void createShouldRejectWaypointMissingOrderAtServiceLayer() {
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mock(IPlannedWaylineMapper.class), new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypoints(List.of(new PlannedWaypointDTO()
                        .setGcjLng(120.1)
                        .setGcjLat(30.2)
                        .setWgsLng(120.0)
                        .setWgsLat(30.1)
                        .setHeight(80.0)))
                .build()));
    }

    @Test
    void createShouldRejectBlankNameAtServiceLayer() {
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mock(IPlannedWaylineMapper.class), new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name(" ")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypoints(List.of(new PlannedWaypointDTO()
                        .setOrder(1)
                        .setGcjLng(120.1)
                        .setGcjLat(30.2)
                        .setWgsLng(120.0)
                        .setWgsLat(30.1)
                        .setHeight(80.0)))
                .build()));
    }

    @Test
    void createAndGetOneShouldPreserveWaypointOrderAndCoordinates() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        AtomicReference<PlannedWaylineEntity> inserted = new AtomicReference<>();
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity entity = invocation.getArgument(0);
            entity.setId(1);
            entity.setCreateTime(1000L);
            entity.setUpdateTime(1000L);
            inserted.set(entity);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> inserted.get());
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper);

        PlannedWaylineDTO created = service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypoints(List.of(
                        new PlannedWaypointDTO()
                                .setOrder(1)
                                .setGcjLng(120.1)
                                .setGcjLat(30.2)
                                .setWgsLng(120.0)
                                .setWgsLat(30.1)
                                .setHeight(80.0),
                        new PlannedWaypointDTO()
                                .setOrder(2)
                                .setGcjLng(120.2)
                                .setGcjLat(30.3)
                                .setWgsLng(120.1)
                                .setWgsLat(30.2)
                                .setHeight(82.0)))
                .build());

        PlannedWaylineDTO restored = service.getOne("workspace-001", created.getPlannedWaylineId()).orElseThrow();

        assertEquals(2, restored.getWaypoints().size());
        assertEquals(1, restored.getWaypoints().get(0).getOrder());
        assertEquals(120.1, restored.getWaypoints().get(0).getGcjLng(), 0.0001);
        assertEquals(30.3, restored.getWaypoints().get(1).getGcjLat(), 0.0001);
        assertEquals(82.0, restored.getWaypoints().get(1).getHeight(), 0.0001);
    }

    @Test
    void updateShouldOverwriteEditableFieldsOnExistingRecord() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypointsJson("[{\"order\":1,\"gcjLng\":120.1,\"gcjLat\":30.2,\"wgsLng\":120.0,\"wgsLat\":30.1,\"height\":80.0}]")
                .status("draft")
                .publishedWaylineId("published-001")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity updated = invocation.getArgument(0);
            assertEquals("Survey B", updated.getName());
            assertEquals("M300RTK", updated.getAircraftModelKey());
            assertEquals("GW-002", updated.getGatewaySn());
            assertEquals("AC-002", updated.getAircraftSn());
            assertEquals(100.0, updated.getDefaultHeight(), 0.0001);
            assertEquals(18.0, updated.getMaxSpeed(), 0.0001);
            return 1;
        });
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper);

        PlannedWaylineDTO updated = service.update("workspace-001", "pw-001", UpdatePlannedWaylineParam.builder()
                .name("Survey B")
                .aircraftModelKey("M300RTK")
                .gatewaySn("GW-002")
                .aircraftSn("AC-002")
                .defaultHeight(100.0)
                .maxSpeed(18.0)
                .waypoints(List.of(
                        new PlannedWaypointDTO()
                                .setOrder(1)
                                .setGcjLng(121.1)
                                .setGcjLat(31.2)
                                .setWgsLng(121.0)
                                .setWgsLat(31.1)
                                .setHeight(100.0)))
                .build());

        assertEquals("Survey B", updated.getName());
        assertEquals("M300RTK", updated.getAircraftModelKey());
        assertEquals("GW-002", updated.getGatewaySn());
        assertEquals("AC-002", updated.getAircraftSn());
        assertEquals(100.0, updated.getDefaultHeight(), 0.0001);
        assertEquals(18.0, updated.getMaxSpeed(), 0.0001);
        assertEquals("draft", updated.getStatus());
        assertEquals("published-001", updated.getPublishedWaylineId());
        assertEquals("alice", updated.getCreator());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlannedWaylineEntity>> selectCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mapper).selectOne(selectCaptor.capture());
        assertEquals(7, selectCaptor.getValue().getExpression().getNormal().size());
        assertEquals(2L, selectCaptor.getValue().getExpression().getNormal().stream()
                .filter(segment -> "EQ".equals(segment.toString()))
                .count());
        assertTrue(selectCaptor.getValue().getExpression().getNormal().stream()
                .anyMatch(segment -> "AND".equals(segment.toString())));
        ArgumentCaptor<PlannedWaylineEntity> updatedCaptor = ArgumentCaptor.forClass(PlannedWaylineEntity.class);
        verify(mapper).updateById(updatedCaptor.capture());
        assertAll(
                () -> assertEquals(1, updatedCaptor.getValue().getId()),
                () -> assertEquals("pw-001", updatedCaptor.getValue().getPlannedWaylineId()),
                () -> assertEquals("workspace-001", updatedCaptor.getValue().getWorkspaceId()),
                () -> assertEquals("draft", updatedCaptor.getValue().getStatus()),
                () -> assertEquals("published-001", updatedCaptor.getValue().getPublishedWaylineId()),
                () -> assertEquals("alice", updatedCaptor.getValue().getCreator()),
                () -> assertTrue(updatedCaptor.getValue().getUpdateTime() >= updatedCaptor.getValue().getCreateTime()));
    }

    @Test
    void updateShouldFailWhenRecordDoesNotExist() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.update("workspace-001", "pw-001",
                UpdatePlannedWaylineParam.builder()
                        .name("Survey B")
                        .aircraftModelKey("M300RTK")
                        .gatewaySn("GW-002")
                        .aircraftSn("AC-002")
                        .defaultHeight(100.0)
                        .maxSpeed(18.0)
                        .waypoints(List.of(new PlannedWaypointDTO()
                                .setOrder(1)
                                .setGcjLng(121.1)
                                .setGcjLat(31.2)
                                .setWgsLng(121.0)
                                .setWgsLat(31.1)
                                .setHeight(100.0)))
                        .build()));
    }

    @Test
    void updateShouldRejectWaypointMissingHeightAtServiceLayer() {
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .status("draft")
                .build();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.selectOne(any())).thenReturn(existing);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.update("workspace-001", "pw-001", UpdatePlannedWaylineParam.builder()
                .name("Survey B")
                .aircraftModelKey("M300RTK")
                .gatewaySn("GW-002")
                .aircraftSn("AC-002")
                .defaultHeight(100.0)
                .maxSpeed(18.0)
                .waypoints(List.of(new PlannedWaypointDTO()
                        .setOrder(1)
                        .setGcjLng(121.1)
                        .setGcjLat(31.2)
                        .setWgsLng(121.0)
                        .setWgsLat(31.1)))
                .build()));
    }

    @Test
    void deleteShouldFailWhenRecordDoesNotExist() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.delete(any())).thenReturn(0);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.delete("workspace-001", "pw-001"));
    }

    @Test
    void deleteShouldSucceedWhenRecordExists() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.delete(any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, new ObjectMapper());

        assertDoesNotThrow(() -> service.delete("workspace-001", "pw-001"));
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlannedWaylineEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mapper).delete(wrapperCaptor.capture());
        assertEquals(7, wrapperCaptor.getValue().getExpression().getNormal().size());
        assertEquals(2L, wrapperCaptor.getValue().getExpression().getNormal().stream()
                .filter(segment -> "EQ".equals(segment.toString()))
                .count());
        assertTrue(wrapperCaptor.getValue().getExpression().getNormal().stream()
                .anyMatch(segment -> "AND".equals(segment.toString())));
    }
}
