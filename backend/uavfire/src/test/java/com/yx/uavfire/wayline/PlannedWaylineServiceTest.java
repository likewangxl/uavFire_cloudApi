package com.yx.uavfire.wayline;

import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.component.oss.model.OssConfiguration;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineCreateDTO;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineFileDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaypointDTO;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import com.yx.uavfire.wayline.model.param.CreatePlannedWaylineParam;
import com.yx.uavfire.wayline.model.param.PublishPlannedWaylineResponse;
import com.yx.uavfire.wayline.model.param.UpdatePlannedWaylineParam;
import com.yx.uavfire.wayline.service.impl.PlannedWaylineServiceImpl;
import com.yx.uavfire.wayline.service.IWaylineFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PlannedWaylineServiceTest {

    @Test
    void createShouldDefaultStatusToDraft() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        AtomicReference<PlannedWaylineEntity> inserted = new AtomicReference<>();
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity entity = invocation.getArgument(0);
            entity.setId(1);
            entity.setCreateTime(1000L);
            entity.setUpdateTime(1000L);
            inserted.set(entity);
            return 1;
        });
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

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
    void createShouldStoreDjiSafeNameForUnsafeCopyName() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        AtomicReference<PlannedWaylineEntity> inserted = new AtomicReference<>();
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity entity = invocation.getArgument(0);
            entity.setId(1);
            inserted.set(entity);
            return 1;
        });
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, objectMapper, mock(IWaylineFileService.class));

        PlannedWaylineDTO dto = service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name("规划航线 2026/5/9 15:02:17 副本")
                .aircraftModelKey("M30T")
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

        assertAll(
                () -> assertEquals("规划航线 2026-5-9 15-02-17 副本", dto.getName()),
                () -> assertEquals("规划航线 2026-5-9 15-02-17 副本", inserted.get().getName()),
                () -> assertTrue(dto.getName().matches("^[^<>:\\\"/|?*._\\\\]+$")));
    }

    @Test
    void createShouldFailWhenInsertDoesNotPersist() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenReturn(0);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, mock(IWaylineFileService.class));

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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mock(IPlannedWaylineMapper.class), new ObjectMapper(), mock(IWaylineFileService.class));

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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mock(IPlannedWaylineMapper.class), new ObjectMapper(), mock(IWaylineFileService.class));

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
    void createShouldAllowPlanningWithoutSelectedAircraft() {
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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, objectMapper, mock(IWaylineFileService.class));

        PlannedWaylineDTO dto = service.create("workspace-001", "alice", CreatePlannedWaylineParam.builder()
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("")
                .aircraftSn("")
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

        assertAll(
                () -> assertEquals("draft", dto.getStatus()),
                () -> assertEquals("", inserted.get().getGatewaySn()),
                () -> assertEquals("", inserted.get().getAircraftSn()));
    }


    @Test
    void createAndGetOneShouldPreserveWaypointOrderAndCoordinates() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

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
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

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
        assertNull(updated.getPublishedWaylineId());
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
                () -> assertNull(updatedCaptor.getValue().getPublishedWaylineId()),
                () -> assertEquals("alice", updatedCaptor.getValue().getCreator()),
                () -> assertTrue(updatedCaptor.getValue().getUpdateTime() >= updatedCaptor.getValue().getCreateTime()));
    }

    @Test
    void updateShouldRejectPublishedRecord() {
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .status("published")
                .publishedWaylineId("wayline-001")
                .build();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.selectOne(any())).thenReturn(existing);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.update("workspace-001", "pw-001", UpdatePlannedWaylineParam.builder()
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

        assertEquals("Published planned wayline cannot be updated. Save as a new planned wayline instead.",
                thrown.getMessage());
        verify(mapper, never()).updateById(any(PlannedWaylineEntity.class));
    }

    @Test
    void updateShouldFailWhenRecordDoesNotExist() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));

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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));

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
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));

        assertThrows(IllegalArgumentException.class, () -> service.delete("workspace-001", "pw-001"));
    }

    @Test
    void deleteShouldSucceedWhenRecordExists() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.delete(any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));

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

    @Test
    void publishShouldCreateFormalWaylineAndMarkDraftPublished() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "custom-prefix";
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
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-001")
                        .name("Survey A")
                        .objectKey("wayline/pw-001.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PublishPlannedWaylineResponse response = service.publish("workspace-001", "pw-001", "bob");

        assertAll(
                () -> assertNotNull(response),
                () -> assertEquals("pw-001", response.getPlannedWaylineId()),
                () -> assertEquals("wayline-001", response.getPublishedWaylineId()),
                () -> assertEquals("Survey A", response.getPublishedWaylineName()),
                () -> assertEquals("bob", response.getPublisher()),
                () -> assertTrue(response.getPublishTime() >= 1000L),
                () -> assertEquals("published", existing.getStatus()),
                () -> assertEquals("wayline-001", existing.getPublishedWaylineId()),
                () -> assertEquals("bob", existing.getPublisher()),
                () -> assertTrue(existing.getPublishTime() >= 1000L));
        ArgumentCaptor<PlannedWaylineEntity> updatedCaptor = ArgumentCaptor.forClass(PlannedWaylineEntity.class);
        verify(mapper).update(updatedCaptor.capture(), any());
        assertAll(
                () -> assertEquals(1, updatedCaptor.getValue().getId()),
                () -> assertEquals("published", updatedCaptor.getValue().getStatus()),
                () -> assertEquals("wayline-001", updatedCaptor.getValue().getPublishedWaylineId()),
                () -> assertEquals("bob", updatedCaptor.getValue().getPublisher()),
                () -> assertTrue(updatedCaptor.getValue().getPublishTime() >= 1000L));
        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        assertAll(
                () -> assertEquals("Survey A.kmz", createCaptor.getValue().getFilename()),
                () -> assertEquals("custom-prefix/pw-001.kmz", createCaptor.getValue().getObjectKey()),
                () -> assertEquals("alice", createCaptor.getValue().getUsername()),
                () -> assertZipContains(createCaptor.getValue().getContent(), "wpmz/template.kml"),
                () -> assertZipContains(createCaptor.getValue().getContent(), "wpmz/waylines.wpml"),
                () -> assertTrue(readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml").contains("<wpml:templateType>waypoint</wpml:templateType>")),
                () -> assertTrue(readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml").contains("<wpml:waylineCoordinateSysParam>")),
                () -> assertTrue(readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml").contains("<name>Survey A</name>")),
                () -> assertTrue(readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml").contains("<coordinates>120.0,30.1</coordinates>")),
                () -> assertTrue(readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml").contains("<wpml:executeHeight>80.0</wpml:executeHeight>")));
        PlannedWaylineDTO reloaded = service.getOne("workspace-001", "pw-001").orElseThrow();
        assertEquals("pw-001", reloaded.getPlannedWaylineId());
        assertEquals("published", reloaded.getStatus());
        assertEquals("wayline-001", reloaded.getPublishedWaylineId());
        assertEquals("bob", reloaded.getPublisher());
        assertTrue(reloaded.getPublishTime() >= 1000L);
    }

    @Test
    void generateFileShouldCreateKmzAndMarkFileGenerated() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "wayline";
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
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-001")
                        .name("Survey A")
                        .objectKey("wayline/pw-001.kmz")
                        .build());
        when(waylineFileService.getWaylineByWaylineId("workspace-001", "wayline-001"))
                .thenReturn(java.util.Optional.of(new com.dji.sdk.cloudapi.wayline.GetWaylineListResponse()
                        .setId("wayline-001")
                        .setObjectKey("wayline/pw-001.kmz")
                        .setSign("md5-001")));
        when(waylineFileService.getObjectUrl("workspace-001", "wayline-001"))
                .thenReturn(new URL("http://example.test/wayline/pw-001.kmz"));
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PlannedWaylineDTO generated = service.generateFile("workspace-001", "pw-001", "bob");

        assertAll(
                () -> assertEquals("file_generated", generated.getStatus()),
                () -> assertEquals("wayline-001", generated.getPublishedWaylineId()),
                () -> assertEquals("wayline/pw-001.kmz", generated.getKmzObjectKey()),
                () -> assertEquals("md5-001", generated.getKmzMd5()),
                () -> assertEquals("http://example.test/wayline/pw-001.kmz", generated.getKmzUrl()),
                () -> assertTrue(generated.getFileGeneratedTime() >= 1000L),
                () -> assertEquals("bob", generated.getPublisher()));
        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        assertAll(
                () -> assertZipContains(createCaptor.getValue().getContent(), "wpmz/template.kml"),
                () -> assertZipContains(createCaptor.getValue().getContent(), "wpmz/waylines.wpml"));
        ArgumentCaptor<PlannedWaylineEntity> updateCaptor = ArgumentCaptor.forClass(PlannedWaylineEntity.class);
        verify(mapper).update(updateCaptor.capture(), any());
        assertAll(
                () -> assertEquals("file_generated", updateCaptor.getValue().getStatus()),
                () -> assertEquals("wayline-001", updateCaptor.getValue().getPublishedWaylineId()),
                () -> assertEquals("md5-001", updateCaptor.getValue().getKmzMd5()),
                () -> assertEquals("http://example.test/wayline/pw-001.kmz", updateCaptor.getValue().getKmzUrl()));
    }

    @Test
    void generateFileShouldSanitizeDjiUnsafeWaylineNameInKmz() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "wayline";
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-unsafe")
                .workspaceId("workspace-001")
                .name("规划航线 2026/5/9 15:02:17")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypointsJson("[{\"order\":1,\"gcjLng\":120.1,\"gcjLat\":30.2,\"wgsLng\":120.0,\"wgsLat\":30.1,\"height\":80.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-unsafe")
                        .name("规划航线 2026-5-9 15-02-17")
                        .objectKey("wayline/pw-unsafe.kmz")
                        .build());
        when(waylineFileService.getWaylineByWaylineId("workspace-001", "wayline-unsafe"))
                .thenReturn(java.util.Optional.of(new com.dji.sdk.cloudapi.wayline.GetWaylineListResponse()
                        .setId("wayline-unsafe")
                        .setObjectKey("wayline/pw-unsafe.kmz")
                        .setSign("md5-unsafe")));
        when(waylineFileService.getObjectUrl("workspace-001", "wayline-unsafe"))
                .thenReturn(new URL("http://example.test/wayline/pw-unsafe.kmz"));
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.generateFile("workspace-001", "pw-unsafe", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");
        assertAll(
                () -> assertEquals("规划航线 2026-5-9 15-02-17.kmz", createCaptor.getValue().getFilename()),
                () -> assertTrue(waylines.contains("<name>规划航线 2026-5-9 15-02-17</name>")),
                () -> assertTrue(waylines.matches("(?s).*<name>[^<>:\\\"/|?*._\\\\]+</name>.*")));
    }

    @Test
    void generateFileShouldRegenerateExistingUnsafeNamedPublishedFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-copy")
                .workspaceId("workspace-001")
                .name("规划航线 2026/5/9 15:02:17 副本")
                .aircraftModelKey("M30T")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypointsJson("[{\"order\":1,\"gcjLng\":120.1,\"gcjLat\":30.2,\"wgsLng\":120.0,\"wgsLat\":30.1,\"height\":80.0}]")
                .status("file_generated")
                .publishedWaylineId("old-wayline")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.deleteByWaylineId("workspace-001", "old-wayline")).thenReturn(true);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("new-wayline")
                        .name("规划航线 2026-5-9 15-02-17 副本")
                        .objectKey("wayline/pw-copy.kmz")
                        .build());
        when(waylineFileService.getWaylineByWaylineId("workspace-001", "new-wayline"))
                .thenReturn(java.util.Optional.of(new com.dji.sdk.cloudapi.wayline.GetWaylineListResponse()
                        .setId("new-wayline")
                        .setObjectKey("wayline/pw-copy.kmz")
                        .setSign("md5-copy")));
        when(waylineFileService.getObjectUrl("workspace-001", "new-wayline"))
                .thenReturn(new URL("http://example.test/wayline/pw-copy.kmz"));
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PlannedWaylineDTO generated = service.generateFile("workspace-001", "pw-copy", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).deleteByWaylineId("workspace-001", "old-wayline");
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        ArgumentCaptor<PlannedWaylineEntity> updateCaptor = ArgumentCaptor.forClass(PlannedWaylineEntity.class);
        verify(mapper).update(updateCaptor.capture(), any());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");
        assertAll(
                () -> assertEquals("new-wayline", generated.getPublishedWaylineId()),
                () -> assertEquals("规划航线 2026-5-9 15-02-17 副本", generated.getName()),
                () -> assertEquals("规划航线 2026-5-9 15-02-17 副本.kmz", createCaptor.getValue().getFilename()),
                () -> assertTrue(waylines.contains("<name>规划航线 2026-5-9 15-02-17 副本</name>")),
                () -> assertEquals("规划航线 2026-5-9 15-02-17 副本", updateCaptor.getValue().getName()));
    }

    @Test
    void generateFileShouldRegenerateExistingUnsafeWaylineFileName() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-safe-record")
                .workspaceId("workspace-001")
                .name("规划航线 2026-5-9 15-02-17 副本")
                .aircraftModelKey("M30T")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypointsJson("[{\"order\":1,\"gcjLng\":120.1,\"gcjLat\":30.2,\"wgsLng\":120.0,\"wgsLat\":30.1,\"height\":80.0}]")
                .status("file_generated")
                .publishedWaylineId("old-unsafe-file")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.getWaylineByWaylineId("workspace-001", "old-unsafe-file"))
                .thenReturn(java.util.Optional.of(new com.dji.sdk.cloudapi.wayline.GetWaylineListResponse()
                        .setId("old-unsafe-file")
                        .setName("规划航线 2026/5/9 15:02:17 副本")
                        .setObjectKey("wayline/old.kmz")
                        .setSign("md5-old")));
        when(waylineFileService.deleteByWaylineId("workspace-001", "old-unsafe-file")).thenReturn(true);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("new-safe-file")
                        .name("规划航线 2026-5-9 15-02-17 副本")
                        .objectKey("wayline/pw-safe-record.kmz")
                        .build());
        when(waylineFileService.getWaylineByWaylineId("workspace-001", "new-safe-file"))
                .thenReturn(java.util.Optional.of(new com.dji.sdk.cloudapi.wayline.GetWaylineListResponse()
                        .setId("new-safe-file")
                        .setName("规划航线 2026-5-9 15-02-17 副本")
                        .setObjectKey("wayline/pw-safe-record.kmz")
                        .setSign("md5-new")));
        when(waylineFileService.getObjectUrl("workspace-001", "new-safe-file"))
                .thenReturn(new URL("http://example.test/wayline/pw-safe-record.kmz"));
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PlannedWaylineDTO generated = service.generateFile("workspace-001", "pw-safe-record", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).deleteByWaylineId("workspace-001", "old-unsafe-file");
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");
        assertAll(
                () -> assertEquals("new-safe-file", generated.getPublishedWaylineId()),
                () -> assertEquals("规划航线 2026-5-9 15-02-17 副本.kmz", createCaptor.getValue().getFilename()),
                () -> assertTrue(waylines.contains("<name>规划航线 2026-5-9 15-02-17 副本</name>")));
    }

    @Test
    void prepareTaskShouldRequireGeneratedFile() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.selectOne(any())).thenReturn(PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .status("draft")
                .build());
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));
        com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam param =
                new com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam();
        param.setDockSn("DOCK-001");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.prepareTask("workspace-001", "pw-001", "alice", param));

        assertEquals("Generate the planned wayline file before preparing the flight task.", thrown.getMessage());
        verify(mapper, never()).updateById(any(PlannedWaylineEntity.class));
    }

    @Test
    void prepareTaskShouldAssignFlightIdAndPublishingStatus() {
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        when(mapper.selectOne(any())).thenReturn(PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .status("file_generated")
                .publishedWaylineId("wayline-001")
                .build());
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, new ObjectMapper(), mock(IWaylineFileService.class));
        com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam param =
                new com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam();
        param.setDockSn("DOCK-001");
        param.setDroneSn("DRONE-001");

        PlannedWaylineDTO prepared = service.prepareTask("workspace-001", "pw-001", "alice", param);

        assertAll(
                () -> assertEquals("publishing", prepared.getStatus()),
                () -> assertEquals("publishing", prepared.getTaskStatus()),
                () -> assertNotNull(prepared.getFlightId()),
                () -> assertEquals("DOCK-001", prepared.getDockSn()),
                () -> assertEquals("DRONE-001", prepared.getDroneSn()),
                () -> assertEquals("alice", prepared.getPublisher()),
                () -> assertTrue(prepared.getPublishTime() > 0));
        ArgumentCaptor<PlannedWaylineEntity> updateCaptor = ArgumentCaptor.forClass(PlannedWaylineEntity.class);
        verify(mapper).updateById(updateCaptor.capture());
        assertAll(
                () -> assertEquals("publishing", updateCaptor.getValue().getStatus()),
                () -> assertEquals("publishing", updateCaptor.getValue().getTaskStatus()),
                () -> assertNotNull(updateCaptor.getValue().getFlightId()));
    }

    @Test
    void publishFailureShouldKeepOriginalDraftState() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
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
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenThrow(new IllegalStateException("publish failed"));
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        assertThrows(IllegalStateException.class, () -> service.publish("workspace-001", "pw-001", "alice"));
        assertAll(
                () -> assertEquals("draft", existing.getStatus()),
                () -> assertNull(existing.getPublishedWaylineId()));
        verify(mapper, never()).update(any(PlannedWaylineEntity.class), any());
    }

    @Test
    void publishShouldReturnExistingPublishedWaylineWhenAlreadyPublished() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .name("Survey A")
                .status("published")
                .publishedWaylineId("wayline-001")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PublishPlannedWaylineResponse response = service.publish("workspace-001", "pw-001", "alice");

        assertAll(
                () -> assertEquals("pw-001", response.getPlannedWaylineId()),
                () -> assertEquals("wayline-001", response.getPublishedWaylineId()),
                () -> assertEquals("Survey A", response.getPublishedWaylineName()));
        verify(waylineFileService, never()).createPublishedWayline(any(), any(PublishedWaylineCreateDTO.class));
        verify(mapper, never()).update(any(PlannedWaylineEntity.class), any());
    }

    @Test
    void publishShouldCleanupCreatedFormalWaylineWhenPlannedUpdateFails() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "wayline";
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
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-001")
                        .name("Survey A")
                        .objectKey("wayline/pw-001.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(0);
        when(waylineFileService.deleteByWaylineId("workspace-001", "wayline-001")).thenReturn(true);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.publish("workspace-001", "pw-001", "alice"));

        assertEquals("Failed to publish planned wayline.", thrown.getMessage());
        verify(waylineFileService).deleteByWaylineId("workspace-001", "wayline-001");
    }

    @Test
    void publishShouldCleanupCreatedFormalWaylineWhenPlannedUpdateThrows() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "wayline";
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
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-001")
                        .name("Survey A")
                        .objectKey("wayline/pw-001.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenThrow(new RuntimeException("db failure"));
        when(waylineFileService.deleteByWaylineId("workspace-001", "wayline-001")).thenReturn(true);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.publish("workspace-001", "pw-001", "alice"));

        assertEquals("db failure", thrown.getMessage());
        verify(waylineFileService).deleteByWaylineId("workspace-001", "wayline-001");
    }

    @Test
    void publishShouldDeleteNewFormalWaylineAndReturnExistingReferenceWhenConditionalUpdateLosesRace() {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "wayline";
        PlannedWaylineEntity draft = PlannedWaylineEntity.builder()
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
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        PlannedWaylineEntity published = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .name("Survey A")
                .status("published")
                .publishedWaylineId("wayline-existing")
                .creator("alice")
                .createTime(1000L)
                .updateTime(2000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(draft, published);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-new")
                        .name("Survey A")
                        .objectKey("wayline/pw-001.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(0);
        when(waylineFileService.deleteByWaylineId("workspace-001", "wayline-new")).thenReturn(true);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PublishPlannedWaylineResponse response = service.publish("workspace-001", "pw-001", "alice");

        assertAll(
                () -> assertEquals("pw-001", response.getPlannedWaylineId()),
                () -> assertEquals("wayline-existing", response.getPublishedWaylineId()),
                () -> assertEquals("Survey A", response.getPublishedWaylineName()));
        verify(waylineFileService).deleteByWaylineId("workspace-001", "wayline-new");
        verify(mapper).update(any(PlannedWaylineEntity.class), any());
    }

    @Test
    void publishShouldEscapeAndSanitizeUnsafePlannedNameInGeneratedKmz() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "custom-prefix";
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-unsafe")
                .workspaceId("workspace-001")
                .name("Unsafe <Route> & \"Alpha\"")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .waypointsJson("[{\"order\":1,\"gcjLng\":120.1,\"gcjLat\":30.2,\"wgsLng\":120.0,\"wgsLat\":30.1,\"height\":80.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-unsafe")
                        .name("Unsafe <Route> & \"Alpha\"")
                        .objectKey("custom-prefix/pw-unsafe.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-unsafe", "alice");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");
        assertAll(
                () -> assertTrue(waylines.contains("Unsafe -Route- &amp; -Alpha")),
                () -> assertTrue(waylines.contains("<name>Unsafe -Route- &amp; -Alpha</name>")),
                () -> assertTrue(template.contains("<wpml:waylineCoordinateSysParam>")));
    }

    @Test
    void publishedKmzShouldContainAllRequiredWpmlFields() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        OssConfiguration.objectDirPrefix = "custom-prefix";
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("pw-spec")
                .workspaceId("workspace-001")
                .name("Spec Probe")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":30.0}," +
                        "{\"order\":3,\"gcjLng\":113.003,\"gcjLat\":22.003,\"wgsLng\":113.002,\"wgsLat\":22.002,\"height\":30.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-spec")
                        .name("Spec Probe")
                        .objectKey("custom-prefix/pw-spec.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-spec", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        assertAll("template.kml WPML compliance",
                () -> assertTrue(template.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.2\""), "wpml namespace"),
                () -> assertTrue(template.contains("<wpml:missionConfig>"), "missionConfig"),
                () -> assertTrue(template.contains("<wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>"), "flyToWaylineMode"),
                () -> assertTrue(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"), "finishAction=goHome"),
                () -> assertTrue(template.contains("<wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>"), "exitOnRCLost=goContinue"),
                () -> assertTrue(template.contains("<wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>"), "takeOffSecurityHeight"),
                () -> assertTrue(template.contains("<wpml:globalTransitionalSpeed>5</wpml:globalTransitionalSpeed>"), "globalTransitionalSpeed"),
                () -> assertTrue(template.contains("<wpml:globalRTHHeight>50</wpml:globalRTHHeight>"), "globalRTHHeight"),
                () -> assertTrue(template.contains("<wpml:droneInfo>"), "droneInfo block"),
                () -> assertTrue(template.contains("<wpml:droneEnumValue>67</wpml:droneEnumValue>"), "M30T droneEnumValue=67"),
                () -> assertTrue(template.contains("<wpml:droneSubEnumValue>1</wpml:droneSubEnumValue>"), "M30T droneSubEnumValue=1"),
                () -> assertTrue(template.contains("<wpml:payloadInfo>"), "payloadInfo block"),
                () -> assertTrue(template.contains("<wpml:payloadEnumValue>53</wpml:payloadEnumValue>"), "M30T_CAMERA payloadEnumValue=53"),
                () -> assertTrue(template.contains("<wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>"), "payloadPositionIndex"),
                () -> assertTrue(template.contains("<wpml:templateType>waypoint</wpml:templateType>"), "templateType=waypoint"),
                () -> assertTrue(template.contains("<wpml:templateId>0</wpml:templateId>"), "templateId"),
                () -> assertTrue(template.contains("<wpml:waylineCoordinateSysParam>"), "coord sys param"),
                () -> assertTrue(template.contains("<wpml:coordinateMode>WGS84</wpml:coordinateMode>"), "coordinateMode=WGS84"),
                () -> assertTrue(template.contains("<wpml:heightMode>relativeToStartPoint</wpml:heightMode>"), "heightMode"),
                () -> assertTrue(template.contains("<wpml:autoFlightSpeed>5</wpml:autoFlightSpeed>"), "autoFlightSpeed"),
                () -> assertTrue(template.contains("<wpml:imageFormat>zoom,ir</wpml:imageFormat>"), "M30T imageFormat=zoom,ir"),
                () -> assertTrue(template.contains("<wpml:globalWaypointHeadingParam>"), "global heading param"),
                () -> assertTrue(template.contains("<wpml:globalWaypointTurnMode>"), "global turn mode"),
                () -> assertTrue(template.contains("<Placemark>"), "placemarks present"),
                () -> assertTrue(template.contains("<wpml:index>0</wpml:index>"), "first waypoint index"),
                () -> assertTrue(template.contains("<wpml:index>2</wpml:index>"), "third waypoint index"),
                () -> assertTrue(template.contains("<wpml:ellipsoidHeight>30.0</wpml:ellipsoidHeight>"), "ellipsoidHeight per waypoint"),
                () -> assertTrue(template.contains("<wpml:height>30.0</wpml:height>"), "height per waypoint"));

        assertAll("waylines.wpml WPML compliance",
                () -> assertTrue(waylines.contains("<wpml:missionConfig>"), "missionConfig"),
                () -> assertTrue(waylines.contains("<wpml:finishAction>goHome</wpml:finishAction>"), "finishAction"),
                () -> assertTrue(waylines.contains("<wpml:droneEnumValue>67</wpml:droneEnumValue>"), "drone enum"),
                () -> assertTrue(waylines.contains("<wpml:templateId>0</wpml:templateId>"), "templateId"),
                () -> assertTrue(waylines.contains("<wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>"), "executeHeightMode"),
                () -> assertTrue(waylines.contains("<wpml:waylineId>0</wpml:waylineId>"), "waylineId"),
                () -> assertTrue(waylines.contains("<wpml:autoFlightSpeed>5</wpml:autoFlightSpeed>"), "wayline autoFlightSpeed"),
                () -> assertTrue(waylines.contains("<wpml:executeHeight>30.0</wpml:executeHeight>"), "per-waypoint executeHeight"),
                () -> assertTrue(waylines.contains("<wpml:waypointSpeed>5</wpml:waypointSpeed>"), "per-waypoint waypointSpeed"),
                () -> assertTrue(waylines.contains("<wpml:waypointHeadingParam>"), "per-waypoint heading param"),
                () -> assertTrue(waylines.contains("<wpml:waypointTurnParam>"), "per-waypoint turn param"),
                () -> assertTrue(waylines.contains("<coordinates>113.0,22.0</coordinates>"), "2D coordinates, not 3D"),
                () -> assertFalse(waylines.contains("<coordinates>113.0,22.0,30.0</coordinates>"), "no 3D coords slip-through"));
    }

    private static void assertZipContains(byte[] content, String expectedEntry) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (expectedEntry.equals(entry.getName())) {
                    return;
                }
                entry = zipInputStream.getNextEntry();
            }
        }
        throw new AssertionError("Missing zip entry: " + expectedEntry);
    }

    private static String readZipEntry(byte[] content, String entryName) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (entryName.equals(entry.getName())) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    zipInputStream.transferTo(outputStream);
                    return outputStream.toString(StandardCharsets.UTF_8);
                }
                entry = zipInputStream.getNextEntry();
            }
        }
        throw new AssertionError("Missing zip entry: " + entryName);
    }
}
