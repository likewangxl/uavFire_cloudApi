package com.yx.uavfire.wayline;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.component.oss.model.OssConfiguration;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineCreateDTO;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineFileDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaypointDTO;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import com.yx.uavfire.wayline.model.param.CreatePlannedWaylineParam;
import com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam;
import com.yx.uavfire.wayline.model.param.PublishPlannedWaylineResponse;
import com.yx.uavfire.wayline.model.param.UpdatePlannedWaylineParam;
import com.yx.uavfire.wayline.service.impl.PlannedWaylineServiceImpl;
import com.yx.uavfire.wayline.service.IWaylineFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dji.sdk.cloudapi.device.DeviceEnum;
import com.dji.sdk.cloudapi.wayline.GetWaylineListResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PlannedWaylineServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), PlannedWaylineEntity.class);
    }

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
    void importKmzFileShouldCreateExecutableFileGeneratedPlannedWayline() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        AtomicReference<PlannedWaylineEntity> inserted = new AtomicReference<>();
        ArgumentCaptor<PublishedWaylineCreateDTO> publishedCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        byte[] kmzBytes = Files.readAllBytes(Path.of("../../rcplus-msdk-agent/app/src/main/res/raw/m4t_probe.kmz"));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "M4T Official.kmz",
                "application/vnd.google-earth.kmz",
                kmzBytes);

        when(waylineFileService.createPublishedWayline(eq("workspace-001"), publishedCaptor.capture()))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-file-001")
                        .name("M4T Official")
                        .objectKey("planned-imports/imported.kmz")
                        .build());
        when(waylineFileService.getWaylineByWaylineId("workspace-001", "wayline-file-001"))
                .thenReturn(java.util.Optional.of(new GetWaylineListResponse()
                        .setId("wayline-file-001")
                        .setName("M4T Official")
                        .setDroneModelKey(DeviceEnum.M4T)
                        .setObjectKey("planned-imports/imported.kmz")
                        .setSign("0123456789abcdef0123456789abcdef")));
        when(waylineFileService.getObjectUrl("workspace-001", "wayline-file-001"))
                .thenReturn(new URL("http://localhost:6789/kmz/imported.kmz"));
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity entity = invocation.getArgument(0);
            entity.setId(1);
            inserted.set(entity);
            return 1;
        });
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        PlannedWaylineDTO dto = service.importKmzFile("workspace-001", "alice", file);

        assertAll(
                () -> assertEquals("file_generated", dto.getStatus()),
                () -> assertEquals("file_generated", dto.getTaskStatus()),
                () -> assertEquals("workspace-001", dto.getWorkspaceId()),
                () -> assertEquals("alice", dto.getCreator()),
                () -> assertEquals("M4T Official", dto.getName()),
                () -> assertEquals("M4T", dto.getAircraftModelKey()),
                () -> assertEquals("wayline-file-001", dto.getPublishedWaylineId()),
                () -> assertEquals("http://localhost:6789/kmz/imported.kmz", dto.getKmzUrl()),
                () -> assertEquals("0123456789abcdef0123456789abcdef", dto.getKmzMd5()),
                () -> assertEquals("planned-imports/imported.kmz", dto.getKmzObjectKey()),
                () -> assertEquals(2, dto.getWaypoints().size()),
                () -> assertEquals(113.0, dto.getWaypoints().get(0).getWgsLng()),
                () -> assertEquals(22.0, dto.getWaypoints().get(0).getWgsLat()),
                () -> assertEquals(30.0, dto.getWaypoints().get(0).getHeight()),
                () -> assertEquals(5.0, dto.getWaypoints().get(0).getSpeed()),
                () -> assertEquals(2, objectMapper.readTree(inserted.get().getWaypointsJson()).size()),
                () -> assertNotNull(dto.getFileGeneratedTime()),
                () -> assertNotNull(dto.getPlannedWaylineId()),
                () -> assertEquals("M4T Official.kmz", publishedCaptor.getValue().getFilename()),
                () -> assertEquals(kmzBytes.length, publishedCaptor.getValue().getContent().length),
                () -> assertTrue(publishedCaptor.getValue().getObjectKey().endsWith(".kmz")),
                () -> assertNotNull(inserted.get()));
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
    void getOneShouldIncludeMsdkAircraftPositionForAgentWayline() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        PlannedWaylineEntity entity = PlannedWaylineEntity.builder()
                .id(1)
                .plannedWaylineId("planned-001")
                .workspaceId("workspace-001")
                .name("M4T Route")
                .aircraftModelKey("M4T")
                .droneSn("M4T-SN-001")
                .waypointsJson("[]")
                .status("executing")
                .taskStatus("executing")
                .build();
        when(mapper.selectOne(any())).thenReturn(entity);
        MsdkDeviceStateService msdkDeviceStateService = new MsdkDeviceStateService();
        msdkDeviceStateService.upsert(new MsdkDeviceStateDTO()
                .setAircraftSn("M4T-SN-001")
                .setOnline(true)
                .setConnectionState("CONNECTED")
                .setLatitude(34.123456)
                .setLongitude(108.654321)
                .setHeight(42.0)
                .setUpdatedAt(123456789L));
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(
                mapper, objectMapper, mock(IWaylineFileService.class));
        setField(service, "msdkDeviceStateService", msdkDeviceStateService);

        PlannedWaylineDTO dto = service.getOne("workspace-001", "planned-001").orElseThrow();

        assertAll(
                () -> assertEquals(108.654321, dto.getAircraftLng()),
                () -> assertEquals(34.123456, dto.getAircraftLat()),
                () -> assertEquals(108.6588387712004, dto.getAircraftGcjLng()),
                () -> assertEquals(34.12184034579389, dto.getAircraftGcjLat()),
                () -> assertEquals(42.0, dto.getAircraftHeight()),
                () -> assertEquals(123456789L, dto.getAircraftUpdatedAt()));
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

        // Field set asserted here mirrors Pilot 2's real M4T export
        // (see /Users/likewang/uavfire/kmz/麟游官坪.kmz baseline).
        assertAll("template.kml WPML compliance",
                () -> assertTrue(template.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\""), "wpml namespace 1.0.6"),
                () -> assertTrue(template.contains("<wpml:missionConfig>"), "missionConfig"),
                () -> assertTrue(template.contains("<wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>"), "flyToWaylineMode"),
                () -> assertTrue(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"), "finishAction=goHome"),
                () -> assertTrue(template.contains("<wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>"), "exitOnRCLost=goContinue"),
                () -> assertTrue(template.contains("<wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>"), "executeRCLostAction"),
                () -> assertTrue(template.contains("<wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>"), "takeOffSecurityHeight"),
                () -> assertTrue(template.contains("<wpml:globalTransitionalSpeed>5</wpml:globalTransitionalSpeed>"), "globalTransitionalSpeed"),
                () -> assertFalse(template.contains("<wpml:globalRTHHeight>"), "globalRTHHeight must NOT be in missionConfig (Pilot 2 doesn't emit it here)"),
                () -> assertTrue(template.contains("<wpml:droneInfo>"), "droneInfo block"),
                () -> assertTrue(template.contains("<wpml:droneEnumValue>67</wpml:droneEnumValue>"), "M30T droneEnumValue=67"),
                () -> assertTrue(template.contains("<wpml:droneSubEnumValue>1</wpml:droneSubEnumValue>"), "M30T droneSubEnumValue=1"),
                () -> assertTrue(template.contains("<wpml:waylineAvoidLimitAreaMode>0</wpml:waylineAvoidLimitAreaMode>"), "waylineAvoidLimitAreaMode"),
                () -> assertTrue(template.contains("<wpml:payloadInfo>"), "payloadInfo block"),
                () -> assertTrue(template.contains("<wpml:payloadEnumValue>53</wpml:payloadEnumValue>"), "M30T_CAMERA payloadEnumValue=53"),
                () -> assertTrue(template.contains("<wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>"), "payloadPositionIndex"),
                () -> assertTrue(template.contains("<wpml:templateType>waypoint</wpml:templateType>"), "templateType=waypoint"),
                () -> assertTrue(template.contains("<wpml:templateId>0</wpml:templateId>"), "templateId"),
                () -> assertTrue(template.contains("<wpml:waylineCoordinateSysParam>"), "coord sys param"),
                () -> assertTrue(template.contains("<wpml:coordinateMode>WGS84</wpml:coordinateMode>"), "coordinateMode=WGS84"),
                () -> assertTrue(template.contains("<wpml:heightMode>relativeToStartPoint</wpml:heightMode>"), "heightMode"),
                () -> assertTrue(template.contains("<wpml:positioningType>GPS</wpml:positioningType>"), "positioningType=GPS"),
                () -> assertTrue(template.contains("<wpml:autoFlightSpeed>5</wpml:autoFlightSpeed>"), "autoFlightSpeed"),
                () -> assertTrue(template.contains("<wpml:globalHeight>"), "globalHeight"),
                () -> assertTrue(template.contains("<wpml:caliFlightEnable>0</wpml:caliFlightEnable>"), "caliFlightEnable"),
                () -> assertTrue(template.contains("<wpml:gimbalPitchMode>manual</wpml:gimbalPitchMode>"), "gimbalPitchMode"),
                () -> assertTrue(template.contains("<wpml:globalWaypointHeadingParam>"), "global heading param"),
                () -> assertFalse(template.contains("waypointHeadingPathMode"), "no waypointHeadingPathMode (Pilot 2 omits)"),
                () -> assertTrue(template.contains("<wpml:globalWaypointTurnMode>"), "global turn mode"),
                () -> assertFalse(template.contains("<wpml:payloadParam>"), "no Folder-level payloadParam (Pilot 2 doesn't emit)"),
                () -> assertTrue(template.contains("<Placemark>"), "placemarks present"),
                () -> assertTrue(template.contains("<wpml:index>0</wpml:index>"), "first waypoint index"),
                () -> assertTrue(template.contains("<wpml:index>2</wpml:index>"), "third waypoint index"),
                () -> assertTrue(template.contains("<wpml:ellipsoidHeight>30.0</wpml:ellipsoidHeight>"), "ellipsoidHeight per waypoint"),
                () -> assertTrue(template.contains("<wpml:height>30.0</wpml:height>"), "height per waypoint"),
                () -> assertTrue(template.contains("<wpml:waypointTurnMode>toPointAndPassWithContinuityCurvature</wpml:waypointTurnMode>"), "per-waypoint turn mode"),
                () -> assertTrue(template.contains("<wpml:useGlobalSpeed>1</wpml:useGlobalSpeed>"), "useGlobalSpeed"),
                () -> assertTrue(template.contains("<wpml:useGlobalHeadingParam>1</wpml:useGlobalHeadingParam>"), "useGlobalHeadingParam"),
                () -> assertTrue(template.contains("<wpml:useStraightLine>1</wpml:useStraightLine>"), "useStraightLine"),
                () -> assertTrue(template.contains("<wpml:isRisky>0</wpml:isRisky>"), "isRisky"));

        assertAll("waylines.wpml WPML compliance",
                () -> assertTrue(waylines.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\""), "wpml namespace 1.0.6"),
                () -> assertTrue(waylines.contains("<wpml:missionConfig>"), "missionConfig"),
                () -> assertTrue(waylines.contains("<wpml:finishAction>goHome</wpml:finishAction>"), "finishAction"),
                () -> assertTrue(waylines.contains("<wpml:droneEnumValue>67</wpml:droneEnumValue>"), "drone enum"),
                () -> assertTrue(waylines.contains("<wpml:waylineAvoidLimitAreaMode>0</wpml:waylineAvoidLimitAreaMode>"), "waylineAvoidLimitAreaMode"),
                () -> assertTrue(waylines.contains("<wpml:payloadEnumValue>53</wpml:payloadEnumValue>"), "payload enum"),
                () -> assertTrue(waylines.contains("<wpml:templateId>0</wpml:templateId>"), "templateId"),
                () -> assertTrue(waylines.contains("<wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>"), "executeHeightMode"),
                () -> assertTrue(waylines.contains("<wpml:waylineId>0</wpml:waylineId>"), "waylineId"),
                () -> assertTrue(waylines.contains("<wpml:distance>"), "Folder distance"),
                () -> assertTrue(waylines.contains("<wpml:duration>"), "Folder duration"),
                () -> assertTrue(waylines.contains("<wpml:autoFlightSpeed>5</wpml:autoFlightSpeed>"), "wayline autoFlightSpeed"),
                () -> assertFalse(waylines.contains("<wpml:payloadParam>"), "no Folder-level payloadParam"),
                () -> assertTrue(waylines.contains("<wpml:executeHeight>30.0</wpml:executeHeight>"), "per-waypoint executeHeight"),
                () -> assertTrue(waylines.contains("<wpml:waypointSpeed>5</wpml:waypointSpeed>"), "per-waypoint waypointSpeed"),
                () -> assertTrue(waylines.contains("<wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>"), "waypointHeadingAngleEnable"),
                () -> assertTrue(waylines.contains("<wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>"), "waypointHeadingPoiIndex"),
                () -> assertFalse(waylines.contains("waypointHeadingPathMode"), "no waypointHeadingPathMode (Pilot 2 omits)"),
                () -> assertTrue(waylines.contains("<wpml:waypointTurnMode>toPointAndPassWithContinuityCurvature</wpml:waypointTurnMode>"), "wayline placemark turn mode"),
                () -> assertTrue(waylines.contains("<wpml:waypointTurnDampingDist>10</wpml:waypointTurnDampingDist>"), "wayline placemark damping=10"),
                () -> assertTrue(waylines.contains("<wpml:waypointGimbalHeadingParam>"), "waypointGimbalHeadingParam block"),
                () -> assertTrue(waylines.contains("<wpml:waypointGimbalPitchAngle>0</wpml:waypointGimbalPitchAngle>"), "gimbal pitch"),
                () -> assertTrue(waylines.contains("<wpml:waypointGimbalYawAngle>0</wpml:waypointGimbalYawAngle>"), "gimbal yaw"),
                () -> assertTrue(waylines.contains("<wpml:waypointWorkType>0</wpml:waypointWorkType>"), "waypointWorkType"),
                () -> assertTrue(waylines.contains("<wpml:useStraightLine>1</wpml:useStraightLine>"), "useStraightLine=1"),
                () -> assertTrue(waylines.contains("<wpml:isRisky>0</wpml:isRisky>"), "isRisky"),
                () -> assertTrue(waylines.contains("<coordinates>113.0,22.0</coordinates>"), "2D coordinates, not 3D"),
                () -> assertFalse(waylines.contains("<coordinates>113.0,22.0,30.0</coordinates>"), "no 3D coords slip-through"));
    }

    @Test
    void m4tKmzHasPilot2EnumValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(1001)
                .plannedWaylineId("pw-m4t")
                .workspaceId("workspace-001")
                .name("M4T Probe")
                .aircraftModelKey("M4T")
                .gatewaySn("GW-002")
                .aircraftSn("AC-002")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":30.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-m4t")
                        .name("M4T Probe")
                        .objectKey("custom-prefix/pw-m4t.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-m4t", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        // Pilot 2 真机导出的 M4T KMZ 实测值 (kmz/麟游*.kmz)
        assertAll("M4T enum values match Pilot 2 real export",
                () -> assertTrue(template.contains("<wpml:droneEnumValue>100</wpml:droneEnumValue>"), "M4T droneEnumValue=100"),
                () -> assertTrue(template.contains("<wpml:droneSubEnumValue>1</wpml:droneSubEnumValue>"), "M4T droneSubEnumValue=1"),
                () -> assertTrue(template.contains("<wpml:payloadEnumValue>99</wpml:payloadEnumValue>"), "M4T payloadEnumValue=99"),
                () -> assertTrue(template.contains("<wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue>"), "M4T payloadSubEnumValue=0"),
                () -> assertTrue(waylines.contains("<wpml:droneEnumValue>100</wpml:droneEnumValue>"), "M4T drone enum in waylines"),
                () -> assertTrue(waylines.contains("<wpml:payloadEnumValue>99</wpml:payloadEnumValue>"), "M4T payload enum in waylines"));

        // When -Dkmz.dump.path=<path> is set, also write the generated KMZ to that
        // path so it can be adb-pushed to an RC for real-aircraft validation.
        String dumpPath = System.getProperty("kmz.dump.path");
        if (dumpPath != null && !dumpPath.isBlank()) {
            java.nio.file.Files.write(java.nio.file.Paths.get(dumpPath), createCaptor.getValue().getContent());
        }
    }

    @Test
    void missionConfigUsesEntityValuesNotHardcoded() throws Exception {
        // L1: KMZ <wpml:missionConfig> 必须从 entity 字段读,不能继续 hardcode。
        // 配置一组与默认值都不同的 mission 参数,断言全部出现在生成 KMZ 中。
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2001)
                .plannedWaylineId("pw-cfg")
                .workspaceId("workspace-001")
                .name("Mission Config Test")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(8.0)
                .finishAction("autoLand")
                .exitOnRcLost("executeLostAction")
                .rcLostAction("hover")
                .takeoffSecurityHeight(50)
                .globalTransitionalSpeed(10.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":30.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-cfg")
                        .name("Mission Config Test")
                        .objectKey("custom-prefix/pw-cfg.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-cfg", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        assertAll("mission config 来自 entity",
                () -> assertTrue(template.contains("<wpml:finishAction>autoLand</wpml:finishAction>"), "template finishAction=autoLand"),
                () -> assertTrue(waylines.contains("<wpml:finishAction>autoLand</wpml:finishAction>"), "waylines finishAction=autoLand"),
                () -> assertTrue(template.contains("<wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>"), "template exitOnRCLost=executeLostAction"),
                () -> assertTrue(template.contains("<wpml:executeRCLostAction>hover</wpml:executeRCLostAction>"), "template executeRCLostAction=hover"),
                () -> assertTrue(template.contains("<wpml:takeOffSecurityHeight>50</wpml:takeOffSecurityHeight>"), "template takeOffSecurityHeight=50"),
                () -> assertTrue(template.contains("<wpml:globalTransitionalSpeed>10"), "template globalTransitionalSpeed=10"),
                () -> assertFalse(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"), "no hardcoded goHome"),
                () -> assertFalse(template.contains("<wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>"), "no hardcoded 20"));
    }

    @Test
    void missionConfigFallsBackToDefaultsWhenEntityFieldsNull() throws Exception {
        // 向后兼容:entity 的 6 个 mission 配置字段为 null 时,KMZ 必须用 contract 默认值
        // (goHome / goContinue / goBack / 20 / 5)。
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2002)
                .plannedWaylineId("pw-defaults")
                .workspaceId("workspace-001")
                .name("Defaults Test")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                // 6 个 mission 配置全部不设
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":30.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-defaults")
                        .name("Defaults Test")
                        .objectKey("custom-prefix/pw-defaults.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-defaults", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");

        assertAll("entity 字段 null 时走默认值",
                () -> assertTrue(template.contains("<wpml:finishAction>goHome</wpml:finishAction>"), "default goHome"),
                () -> assertTrue(template.contains("<wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>"), "default goContinue"),
                () -> assertTrue(template.contains("<wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>"), "default goBack"),
                () -> assertTrue(template.contains("<wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>"), "default 20"),
                () -> assertTrue(template.contains("<wpml:globalTransitionalSpeed>5"), "default 5"));
    }

    @Test
    void waypointOverrideFieldsFlowIntoKmz() throws Exception {
        // L1: wp[0] 自定义 speed / gimbal / heading / turn,wp[1] 全部 null 走全局。
        // 断言生成 KMZ 同时反映 override 和全局默认。
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2003)
                .plannedWaylineId("pw-wpoverride")
                .workspaceId("workspace-001")
                .name("Waypoint Override Test")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0," +
                        "  \"speed\":10.0,\"gimbalPitch\":-45.0,\"gimbalYaw\":0.0," +
                        "  \"headingMode\":\"fixed\",\"headingAngle\":90.0," +
                        "  \"turnMode\":\"coordinateTurn\",\"turnDamping\":2.0}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":30.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-wpoverride")
                        .name("Waypoint Override Test")
                        .objectKey("custom-prefix/pw-wpoverride.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-wpoverride", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        assertAll("wp[0] override 字段写入 KMZ",
                () -> assertTrue(waylines.contains("<wpml:waypointSpeed>10</wpml:waypointSpeed>"), "wp[0] speed override = 10"),
                () -> assertTrue(waylines.contains("<wpml:waypointGimbalPitchAngle>-45</wpml:waypointGimbalPitchAngle>"), "wp[0] gimbalPitch = -45"),
                () -> assertTrue(waylines.contains("<wpml:waypointHeadingMode>fixed</wpml:waypointHeadingMode>"), "wp[0] headingMode = fixed"),
                () -> assertTrue(waylines.contains("<wpml:waypointHeadingAngle>90</wpml:waypointHeadingAngle>"), "wp[0] headingAngle = 90"),
                () -> assertTrue(waylines.contains("<wpml:waypointTurnMode>coordinateTurn</wpml:waypointTurnMode>"), "wp[0] turnMode = coordinateTurn"),
                () -> assertTrue(waylines.contains("<wpml:waypointTurnDampingDist>2</wpml:waypointTurnDampingDist>"), "wp[0] turnDamping = 2"));

        assertAll("wp[1] 全 null 走全局默认",
                () -> assertTrue(waylines.contains("<wpml:waypointTurnMode>toPointAndPassWithContinuityCurvature</wpml:waypointTurnMode>"), "wp[1] default turnMode"),
                () -> assertTrue(waylines.contains("<wpml:waypointTurnDampingDist>10</wpml:waypointTurnDampingDist>"), "wp[1] default turnDamping = 10"),
                () -> assertTrue(waylines.contains("<wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>"), "wp[1] default headingMode"));
    }

    @Test
    void generatedKmzUsesPlannedMaxSpeedForAutoFlightAndDefaultWaypointSpeed() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2010)
                .plannedWaylineId("pw-speed")
                .workspaceId("workspace-001")
                .name("Speed Test")
                .aircraftModelKey("M4T")
                .defaultHeight(60.0)
                .maxSpeed(9.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":60.0}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":60.0}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-speed")
                        .name("Speed Test")
                        .objectKey("custom-prefix/pw-speed.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-speed", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        assertAll("planned maxSpeed is the generated default flight speed",
                () -> assertTrue(template.contains("<wpml:autoFlightSpeed>9</wpml:autoFlightSpeed>"), "template autoFlightSpeed"),
                () -> assertTrue(waylines.contains("<wpml:autoFlightSpeed>9</wpml:autoFlightSpeed>"), "waylines autoFlightSpeed"),
                () -> assertEquals(2, waylines.split("<wpml:waypointSpeed>9</wpml:waypointSpeed>", -1).length - 1, "default waypointSpeed"),
                () -> assertFalse(waylines.contains("<wpml:waypointSpeed>5</wpml:waypointSpeed>"), "no hardcoded waypointSpeed=5"));
    }

    @Test
    void actionGroupsEmittedPerWaypointInBothKmzFiles() throws Exception {
        // P1.b: wp[0] takePhoto, wp[1] gimbalRotate, wp[2] hover。每个航点一个 actionGroup,
        // 出现在 template.kml 和 waylines.wpml 中,结构对齐 Pilot 2 真机导出 (kmz/麟游官坪.kmz)。
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2004)
                .plannedWaylineId("pw-actions")
                .workspaceId("workspace-001")
                .name("Action Group Test")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0," +
                        "  \"actions\":[{\"actuatorFunc\":\"takePhoto\",\"params\":{\"fileSuffix\":\"wp0\",\"payloadPositionIndex\":0}}]}," +
                        "{\"order\":2,\"gcjLng\":113.002,\"gcjLat\":22.002,\"wgsLng\":113.001,\"wgsLat\":22.001,\"height\":30.0," +
                        "  \"actions\":[{\"actuatorFunc\":\"gimbalRotate\",\"params\":{\"gimbalRotateMode\":\"absoluteAngle\",\"gimbalPitchRotateEnable\":1,\"gimbalPitchRotateAngle\":-30,\"gimbalYawRotateEnable\":0,\"gimbalYawRotateAngle\":0,\"gimbalRotateTimeEnable\":0,\"gimbalRotateTime\":0,\"payloadPositionIndex\":0}}]}," +
                        "{\"order\":3,\"gcjLng\":113.003,\"gcjLat\":22.003,\"wgsLng\":113.002,\"wgsLat\":22.002,\"height\":30.0," +
                        "  \"actions\":[{\"actuatorFunc\":\"hover\",\"params\":{\"hoverTime\":3}}]}]")
                .status("draft")
                .creator("alice")
                .createTime(1000L)
                .updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder()
                        .waylineId("wayline-actions")
                        .name("Action Group Test")
                        .objectKey("custom-prefix/pw-actions.kmz")
                        .build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-actions", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        // 结构断言:每个 placemark 内嵌 actionGroup (template + waylines 双份)
        for (String file : new String[]{template, waylines}) {
            String label = (file == template) ? "template.kml" : "waylines.wpml";
            assertAll(label + " — 通用 actionGroup 结构",
                    () -> assertTrue(file.contains("<wpml:actionGroupId>0</wpml:actionGroupId>"), "wp[0] actionGroupId=0"),
                    () -> assertTrue(file.contains("<wpml:actionGroupId>1</wpml:actionGroupId>"), "wp[1] actionGroupId=1"),
                    () -> assertTrue(file.contains("<wpml:actionGroupId>2</wpml:actionGroupId>"), "wp[2] actionGroupId=2"),
                    () -> assertTrue(file.contains("<wpml:actionGroupStartIndex>0</wpml:actionGroupStartIndex>"), "wp[0] startIndex=0"),
                    () -> assertTrue(file.contains("<wpml:actionGroupStartIndex>1</wpml:actionGroupStartIndex>"), "wp[1] startIndex=1"),
                    () -> assertTrue(file.contains("<wpml:actionGroupStartIndex>2</wpml:actionGroupStartIndex>"), "wp[2] startIndex=2"),
                    () -> assertTrue(file.contains("<wpml:actionGroupMode>sequence</wpml:actionGroupMode>"), "sequence mode"),
                    () -> assertTrue(file.contains("<wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>"), "trigger reachPoint default"));
        }

        assertAll("waylines — wp[0] takePhoto",
                () -> assertTrue(waylines.contains("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>"), "actuatorFunc=takePhoto"),
                () -> assertTrue(waylines.contains("<wpml:fileSuffix>wp0</wpml:fileSuffix>"), "fileSuffix=wp0"),
                () -> assertTrue(waylines.contains("<wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>"), "payloadPositionIndex=0"));

        assertAll("waylines — wp[1] gimbalRotate",
                () -> assertTrue(waylines.contains("<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>"), "actuatorFunc=gimbalRotate"),
                () -> assertTrue(waylines.contains("<wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>"), "rotateMode=absoluteAngle"),
                () -> assertTrue(waylines.contains("<wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>"), "pitchEnable=1"),
                () -> assertTrue(waylines.contains("<wpml:gimbalPitchRotateAngle>-30</wpml:gimbalPitchRotateAngle>"), "pitchAngle=-30"),
                () -> assertTrue(waylines.contains("<wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>"), "yawEnable=0"),
                () -> assertTrue(waylines.contains("<wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>"), "rotateTimeEnable=0"));

        assertAll("waylines — wp[2] hover",
                () -> assertTrue(waylines.contains("<wpml:actionActuatorFunc>hover</wpml:actionActuatorFunc>"), "actuatorFunc=hover"),
                () -> assertTrue(waylines.contains("<wpml:hoverTime>3</wpml:hoverTime>"), "hoverTime=3"));
    }

    @Test
    void actionGroupMultipleActionsPerWaypointInSequence() throws Exception {
        // 同一个航点挂 2 个 action:先转云台再拍照。actionId 递增,Pilot 2 spec sequence 模式。
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2005)
                .plannedWaylineId("pw-multi")
                .workspaceId("workspace-001")
                .name("Multi-Action Test")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[" +
                        "{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0," +
                        "  \"actions\":[" +
                        "    {\"actuatorFunc\":\"gimbalRotate\",\"params\":{\"gimbalRotateMode\":\"absoluteAngle\",\"gimbalPitchRotateEnable\":1,\"gimbalPitchRotateAngle\":-90,\"gimbalYawRotateEnable\":0,\"gimbalYawRotateAngle\":0,\"gimbalRotateTimeEnable\":0,\"gimbalRotateTime\":0,\"payloadPositionIndex\":0}}," +
                        "    {\"actuatorFunc\":\"takePhoto\",\"params\":{\"fileSuffix\":\"after-rotate\",\"payloadPositionIndex\":0}}" +
                        "  ]}]")
                .status("draft").creator("alice").createTime(1000L).updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder().waylineId("wayline-multi").name("Multi-Action Test").objectKey("custom-prefix/pw-multi.kmz").build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-multi", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");

        // 同航点 2 个 action,actionId 0 和 1
        assertAll("同航点多 action 顺序",
                () -> assertTrue(waylines.contains("<wpml:actionId>0</wpml:actionId>"), "first actionId=0"),
                () -> assertTrue(waylines.contains("<wpml:actionId>1</wpml:actionId>"), "second actionId=1"),
                () -> assertTrue(waylines.contains("<wpml:actuatorFunc>gimbalRotate</wpml:actuatorFunc>".replace("actuatorFunc", "actionActuatorFunc")), "has gimbalRotate"),
                () -> assertTrue(waylines.contains("<wpml:fileSuffix>after-rotate</wpml:fileSuffix>"), "fileSuffix=after-rotate"),
                // 2 个 action 应在同一个 actionGroup 内,所以只有 1 个 <wpml:actionGroup> 标签
                () -> assertEquals(1, waylines.split("<wpml:actionGroup>", -1).length - 1, "single actionGroup wraps both actions"));
    }

    @Test
    void waypointsWithoutActionsEmitNoActionGroup() throws Exception {
        // 向后兼容:wp.actions 为 null / 空时,placemark 内不能出现 <wpml:actionGroup>。
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(2006).plannedWaylineId("pw-noaction").workspaceId("workspace-001").name("No Action")
                .aircraftModelKey("M4T").defaultHeight(30.0).maxSpeed(5.0)
                .waypointsJson("[{\"order\":1,\"gcjLng\":113.001,\"gcjLat\":22.001,\"wgsLng\":113.0,\"wgsLat\":22.0,\"height\":30.0}]")
                .status("draft").creator("alice").createTime(1000L).updateTime(1000L)
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(waylineFileService.createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), any(PublishedWaylineCreateDTO.class)))
                .thenReturn(PublishedWaylineFileDTO.builder().waylineId("wayline-noaction").name("No Action").objectKey("custom-prefix/pw-noaction.kmz").build());
        when(mapper.update(any(PlannedWaylineEntity.class), any())).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);

        service.publish("workspace-001", "pw-noaction", "bob");

        ArgumentCaptor<PublishedWaylineCreateDTO> createCaptor = ArgumentCaptor.forClass(PublishedWaylineCreateDTO.class);
        verify(waylineFileService).createPublishedWayline(org.mockito.ArgumentMatchers.eq("workspace-001"), createCaptor.capture());
        String waylines = readZipEntry(createCaptor.getValue().getContent(), "wpmz/waylines.wpml");
        String template = readZipEntry(createCaptor.getValue().getContent(), "wpmz/template.kml");

        assertAll("无 action 不输出 actionGroup",
                () -> assertFalse(template.contains("<wpml:actionGroup>"), "no actionGroup in template"),
                () -> assertFalse(waylines.contains("<wpml:actionGroup>"), "no actionGroup in waylines"));
    }

    @Test
    void executeAgentWaylineDoesNotStartAiDetectionBeforeFirstWaypointProgress() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        com.yx.uavfire.firedetection.AiServiceClient aiServiceClient =
                mock(com.yx.uavfire.firedetection.AiServiceClient.class);
        com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService =
                mock(com.yx.uavfire.wayline.agent.service.IWaylineAgentService.class);
        Path kmzPath = Files.createTempFile("pw-agent-ai", ".kmz");
        Files.write(kmzPath, new byte[]{1, 2, 3});
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(3001)
                .plannedWaylineId("pw-agent-ai")
                .workspaceId("workspace-001")
                .flightId("flight-agent-ai")
                .name("Agent AI")
                .aircraftModelKey("M4T")
                .droneSn("M4T-SN-001")
                .aircraftSn("M4T-SN-001")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[]")
                .status("ready")
                .taskStatus("ready")
                .kmzUrl(kmzPath.toUri().toURL().toString())
                .kmzMd5("md5-agent-ai")
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);
        setField(service, "waylineAgentService", waylineAgentService);

        service.executeTask("workspace-001", "pw-agent-ai");

        verify(aiServiceClient, never()).startDetection(any(), any(), any(), any());
    }

    @Test
    void executeAgentWaylineShouldRejectMissingAircraftTarget() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService =
                mock(com.yx.uavfire.wayline.agent.service.IWaylineAgentService.class);
        Path kmzPath = Files.createTempFile("pw-agent-no-target", ".kmz");
        Files.write(kmzPath, new byte[]{1, 2, 3});
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(3002)
                .plannedWaylineId("pw-agent-no-target")
                .workspaceId("workspace-001")
                .flightId("flight-agent-no-target")
                .name("Agent No Target")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[]")
                .status("ready")
                .taskStatus("ready")
                .kmzUrl(kmzPath.toUri().toURL().toString())
                .kmzMd5("md5-no-target")
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);
        setField(service, "waylineAgentService", waylineAgentService);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.executeTask("workspace-001", "pw-agent-no-target"));

        assertEquals("执行航线前需要选择在线飞行器。", thrown.getMessage());
        verify(waylineAgentService, never()).prepareKmz(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(byte[].class));
        verify(waylineAgentService, never()).dispatchWayline(
                org.mockito.ArgumentMatchers.anyString(),
                any(com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO.class));
        verify(mapper, never()).updateById(any(PlannedWaylineEntity.class));
    }

    @Test
    void executeAgentWaylineShouldUseRequestAircraftTargetWhenRecordMissingTarget() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService =
                mock(com.yx.uavfire.wayline.agent.service.IWaylineAgentService.class);
        Path kmzPath = Files.createTempFile("pw-agent-request-target", ".kmz");
        Files.write(kmzPath, new byte[]{1, 2, 3});
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(3005)
                .plannedWaylineId("pw-agent-request-target")
                .workspaceId("workspace-001")
                .flightId("flight-agent-request-target")
                .name("Agent Request Target")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[]")
                .status("ready")
                .taskStatus("ready")
                .kmzUrl(kmzPath.toUri().toURL().toString())
                .kmzMd5("md5-request-target")
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);
        setField(service, "waylineAgentService", waylineAgentService);

        PreparePlannedWaylineTaskParam param = new PreparePlannedWaylineTaskParam();
        param.setDroneSn("1581F7K3D249W00AF7PE");
        service.executeTask("workspace-001", "pw-agent-request-target", param);

        verify(waylineAgentService).prepareKmz(
                eq("1581F7K3D249W00AF7PE"),
                eq("flight-agent-request-target"),
                org.mockito.ArgumentMatchers.any(byte[].class));
        verify(waylineAgentService).dispatchWayline(
                eq("1581F7K3D249W00AF7PE"),
                any(com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO.class));
    }

    @Test
    void executeAgentWaylineShouldUseSingleOnlineMsdkAircraftWhenTargetMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService =
                mock(com.yx.uavfire.wayline.agent.service.IWaylineAgentService.class);
        com.yx.uavfire.msdk.service.MsdkDeviceStateService msdkDeviceStateService =
                new com.yx.uavfire.msdk.service.MsdkDeviceStateService();
        msdkDeviceStateService.upsert(new com.yx.uavfire.msdk.model.MsdkDeviceStateDTO()
                .setGatewaySn("RC-001")
                .setAircraftSn("M4T-SN-ONLINE")
                .setOnline(true)
                .setConnectionState("CONNECTED"));
        Path kmzPath = Files.createTempFile("pw-agent-online", ".kmz");
        Files.write(kmzPath, new byte[]{1, 2, 3});
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(3003)
                .plannedWaylineId("pw-agent-online")
                .workspaceId("workspace-001")
                .flightId("flight-agent-online")
                .name("Agent Online")
                .aircraftModelKey("M4T")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[]")
                .status("ready")
                .taskStatus("ready")
                .kmzUrl(kmzPath.toUri().toURL().toString())
                .kmzMd5("md5-online")
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);
        setField(service, "waylineAgentService", waylineAgentService);
        setField(service, "msdkDeviceStateService", msdkDeviceStateService);

        service.executeTask("workspace-001", "pw-agent-online");

        verify(waylineAgentService).prepareKmz(
                eq("M4T-SN-ONLINE"),
                eq("flight-agent-online"),
                org.mockito.ArgumentMatchers.any(byte[].class));
        verify(waylineAgentService).dispatchWayline(
                eq("M4T-SN-ONLINE"),
                any(com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO.class));
        ArgumentCaptor<PlannedWaylineEntity> updateCaptor = ArgumentCaptor.forClass(PlannedWaylineEntity.class);
        verify(mapper).updateById(updateCaptor.capture());
        assertEquals("M4T-SN-ONLINE", updateCaptor.getValue().getDroneSn());
    }

    @Test
    void executeAgentWaylineShouldNormalizePilotM4tKmzBeforeDispatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IPlannedWaylineMapper mapper = mock(IPlannedWaylineMapper.class);
        IWaylineFileService waylineFileService = mock(IWaylineFileService.class);
        com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService =
                mock(com.yx.uavfire.wayline.agent.service.IWaylineAgentService.class);
        Path kmzPath = Files.createTempFile("pw-agent-m4t-normalize", ".kmz");
        Files.write(kmzPath, buildM4tPilotRuntimeKmz());
        PlannedWaylineEntity existing = PlannedWaylineEntity.builder()
                .id(3004)
                .plannedWaylineId("pw-agent-m4t-normalize")
                .workspaceId("workspace-001")
                .flightId("flight-agent-m4t-normalize")
                .name("Agent M4T Normalize")
                .aircraftModelKey("M4T")
                .droneSn("M4T-SN-001")
                .aircraftSn("M4T-SN-001")
                .defaultHeight(30.0)
                .maxSpeed(5.0)
                .waypointsJson("[]")
                .status("ready")
                .taskStatus("ready")
                .kmzUrl(kmzPath.toUri().toURL().toString())
                .kmzMd5("original-md5")
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(PlannedWaylineEntity.class))).thenReturn(1);
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl(mapper, objectMapper, waylineFileService);
        setField(service, "waylineAgentService", waylineAgentService);

        service.executeTask("workspace-001", "pw-agent-m4t-normalize");

        ArgumentCaptor<byte[]> kmzCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(waylineAgentService).prepareKmz(eq("M4T-SN-001"), eq("flight-agent-m4t-normalize"), kmzCaptor.capture());
        String normalizedTemplate = readZipEntry(kmzCaptor.getValue(), "wpmz/template.kml");
        String normalizedWaylines = readZipEntry(kmzCaptor.getValue(), "wpmz/waylines.wpml");
        ArgumentCaptor<com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO> dispatchCaptor =
                ArgumentCaptor.forClass(com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO.class);
        verify(waylineAgentService).dispatchWayline(eq("M4T-SN-001"), dispatchCaptor.capture());
        double normalizedTurnDamping = firstTurnDamping(normalizedWaylines);

        assertAll("M4T KMZ runtime normalization",
                () -> assertTrue(normalizedTemplate.contains("<wpml:droneEnumValue>99</wpml:droneEnumValue>")),
                () -> assertTrue(normalizedTemplate.contains("<wpml:payloadEnumValue>89</wpml:payloadEnumValue>")),
                () -> assertFalse(normalizedTemplate.contains("<wpml:droneEnumValue>100</wpml:droneEnumValue>")),
                () -> assertFalse(normalizedTemplate.contains("<wpml:payloadEnumValue>99</wpml:payloadEnumValue>")),
                () -> assertTrue(normalizedWaylines.contains("<wpml:droneEnumValue>99</wpml:droneEnumValue>")),
                () -> assertTrue(normalizedWaylines.contains("<wpml:payloadEnumValue>89</wpml:payloadEnumValue>")),
                () -> assertTrue(normalizedWaylines.contains("<wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>")),
                () -> assertTrue(normalizedWaylines.contains("<wpml:globalTransitionalSpeed>5</wpml:globalTransitionalSpeed>")),
                () -> assertFalse(normalizedTemplate.contains("<wpml:payloadParam>")),
                () -> assertFalse(normalizedWaylines.contains("<wpml:realTimeFollowSurfaceByFov>")),
                () -> assertTrue(normalizedTemplate.contains("<wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode>")),
                () -> assertTrue(normalizedTemplate.contains("<wpml:waypointTurnParam>")),
                () -> assertTrue(normalizedTemplate.contains("<wpml:waypointTurnMode>toPointAndPassWithContinuityCurvature</wpml:waypointTurnMode>")),
                () -> assertFalse(normalizedTemplate.contains("<wpml:useGlobalTurnParam>")),
                () -> assertFalse(normalizedTemplate.contains("<wpml:useGlobalHeight>")),
                () -> assertTrue(normalizedWaylines.contains("<wpml:waypointTurnMode>toPointAndPassWithContinuityCurvature</wpml:waypointTurnMode>")),
                () -> assertTrue(normalizedTurnDamping > 0, "turn damping must be positive per DJI WPML"),
                () -> assertTrue(normalizedTurnDamping * 2 < 15.0, "two turn intercepts must fit short segment"),
                () -> assertTrue(normalizedTemplate.contains("<wpml:waypointTurnDampingDist>" + formatTurnDamping(normalizedTurnDamping) + "</wpml:waypointTurnDampingDist>")),
                () -> assertTrue(normalizedWaylines.contains("<wpml:useStraightLine>1</wpml:useStraightLine>")),
                () -> assertEquals(org.springframework.util.DigestUtils.md5DigestAsHex(kmzCaptor.getValue()),
                        dispatchCaptor.getValue().getKmzMd5()));
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

    private static double firstTurnDamping(String wpml) {
        Matcher matcher = Pattern.compile("<wpml:waypointTurnDampingDist>([^<]+)</wpml:waypointTurnDampingDist>")
                .matcher(wpml);
        if (!matcher.find()) {
            throw new AssertionError("Missing waypointTurnDampingDist");
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static String formatTurnDamping(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static byte[] buildM4tPilotRuntimeKmz() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\"><Document><wpml:missionConfig>"
                    + "<wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost><wpml:globalTransitionalSpeed>15</wpml:globalTransitionalSpeed>"
                    + "<wpml:droneInfo><wpml:droneEnumValue>99</wpml:droneEnumValue><wpml:droneSubEnumValue>1</wpml:droneSubEnumValue></wpml:droneInfo>"
                    + "<wpml:payloadInfo><wpml:payloadEnumValue>89</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue></wpml:payloadInfo>"
                    + "</wpml:missionConfig><Folder><wpml:templateType>waypoint</wpml:templateType>"
                    + "<wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode>"
                    + "<Placemark><Point><coordinates>120.0,30.1</coordinates></Point><wpml:index>0</wpml:index>"
                    + "<wpml:height>100</wpml:height><wpml:useGlobalHeight>1</wpml:useGlobalHeight>"
                    + "<wpml:useGlobalTurnParam>1</wpml:useGlobalTurnParam><wpml:useStraightLine>0</wpml:useStraightLine></Placemark>"
                    + "<Placemark><Point><coordinates>120.0001,30.1001</coordinates></Point><wpml:index>1</wpml:index>"
                    + "<wpml:height>100</wpml:height><wpml:useGlobalHeight>1</wpml:useGlobalHeight>"
                    + "<wpml:useGlobalTurnParam>1</wpml:useGlobalTurnParam><wpml:useStraightLine>0</wpml:useStraightLine></Placemark>"
                    + "<wpml:payloadParam><wpml:imageFormat>visable,ir</wpml:imageFormat></wpml:payloadParam>"
                    + "</Folder></Document></kml>")
                    .getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\"><Document><wpml:missionConfig>"
                    + "<wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost><wpml:globalTransitionalSpeed>15</wpml:globalTransitionalSpeed>"
                    + "<wpml:droneInfo><wpml:droneEnumValue>99</wpml:droneEnumValue><wpml:droneSubEnumValue>1</wpml:droneSubEnumValue></wpml:droneInfo>"
                    + "<wpml:payloadInfo><wpml:payloadEnumValue>89</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue></wpml:payloadInfo>"
                    + "</wpml:missionConfig><Folder><wpml:realTimeFollowSurfaceByFov>0</wpml:realTimeFollowSurfaceByFov>"
                    + "<Placemark><Point><coordinates>120.0,30.1</coordinates></Point>"
                    + "<wpml:waypointTurnParam><wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>"
                    + "<wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist></wpml:waypointTurnParam>"
                    + "<wpml:useStraightLine>0</wpml:useStraightLine></Placemark>"
                    + "<Placemark><Point><coordinates>120.0001,30.1001</coordinates></Point>"
                    + "<wpml:waypointTurnParam><wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>"
                    + "<wpml:waypointTurnDampingDist>10</wpml:waypointTurnDampingDist></wpml:waypointTurnParam>"
                    + "<wpml:useStraightLine>0</wpml:useStraightLine></Placemark></Folder></Document></kml>")
                    .getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return outputStream.toByteArray();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
