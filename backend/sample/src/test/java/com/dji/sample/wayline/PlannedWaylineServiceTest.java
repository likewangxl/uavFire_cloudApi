package com.dji.sample.wayline;

import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import com.dji.sample.wayline.model.entity.PlannedWaylineEntity;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.service.impl.PlannedWaylineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlannedWaylineServiceTest {

    @Test
    void createShouldDefaultStatusToDraft() {
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl();
        ObjectMapper objectMapper = new ObjectMapper();
        com.dji.sample.wayline.dao.IPlannedWaylineMapper mapper = mock(com.dji.sample.wayline.dao.IPlannedWaylineMapper.class);
        when(mapper.insert(any(PlannedWaylineEntity.class))).thenAnswer(invocation -> {
            PlannedWaylineEntity entity = invocation.getArgument(0);
            entity.setId(1);
            entity.setCreateTime(1000L);
            entity.setUpdateTime(1000L);
            return 1;
        });
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

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
    }

    @Test
    void dtoRoundTripShouldPreserveWaypointOrderAndCoordinates() {
        PlannedWaylineServiceImpl service = new PlannedWaylineServiceImpl();
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        PlannedWaylineDTO source = PlannedWaylineDTO.builder()
                .plannedWaylineId("pw-001")
                .workspaceId("workspace-001")
                .name("Survey A")
                .aircraftModelKey("M30T")
                .gatewaySn("GW-001")
                .aircraftSn("AC-001")
                .defaultHeight(80.0)
                .maxSpeed(12.5)
                .status("draft")
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
                .build();

        PlannedWaylineEntity entity = ReflectionTestUtils.invokeMethod(service, "dto2Entity", source);
        assertNotNull(entity);

        PlannedWaylineDTO restored = ReflectionTestUtils.invokeMethod(service, "entity2Dto", entity);
        assertNotNull(restored);
        assertEquals(2, restored.getWaypoints().size());
        assertEquals(1, restored.getWaypoints().get(0).getOrder());
        assertEquals(120.1, restored.getWaypoints().get(0).getGcjLng(), 0.0001);
        assertEquals(30.3, restored.getWaypoints().get(1).getGcjLat(), 0.0001);
        assertEquals(82.0, restored.getWaypoints().get(1).getHeight(), 0.0001);
    }
}
