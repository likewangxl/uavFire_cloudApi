package com.dji.sample.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dji.sample.wayline.dao.IPlannedWaylineMapper;
import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import com.dji.sample.wayline.model.entity.PlannedWaylineEntity;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sample.wayline.service.IPlannedWaylineService;
import com.dji.sdk.common.Pagination;
import com.dji.sdk.common.PaginationData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class PlannedWaylineServiceImpl implements IPlannedWaylineService {

    private static final String STATUS_DRAFT = "draft";

    private final IPlannedWaylineMapper mapper;

    private final ObjectMapper objectMapper;

    @Override
    public PaginationData<PlannedWaylineDTO> getByWorkspace(String workspaceId, long page, long pageSize) {
        Page<PlannedWaylineEntity> pageData = mapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<PlannedWaylineEntity>()
                        .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(PlannedWaylineEntity::getId));
        List<PlannedWaylineDTO> records = pageData.getRecords().stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());
        return new PaginationData<>(records, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));
    }

    @Override
    public PlannedWaylineDTO create(String workspaceId, String username, CreatePlannedWaylineParam param) {
        validateParam(param);
        PlannedWaylineEntity entity = dto2Entity(param);
        entity.setWorkspaceId(workspaceId);
        entity.setPlannedWaylineId(UUID.randomUUID().toString());
        entity.setStatus(STATUS_DRAFT);
        entity.setCreator(username);
        entity.setCreateTime(System.currentTimeMillis());
        entity.setUpdateTime(entity.getCreateTime());
        int inserted = mapper.insert(entity);
        if (inserted <= 0) {
            throw new IllegalArgumentException("Failed to create planned wayline.");
        }
        return entity2Dto(entity);
    }

    @Override
    public PlannedWaylineDTO update(String workspaceId, String id, UpdatePlannedWaylineParam param) {
        validateParam(param);
        PlannedWaylineEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<PlannedWaylineEntity>()
                        .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                        .eq(PlannedWaylineEntity::getPlannedWaylineId, id));
        if (Objects.isNull(existing)) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }

        applyEditableFields(existing, param);
        existing.setUpdateTime(System.currentTimeMillis());
        int updated = mapper.updateById(existing);
        if (updated <= 0) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }
        return entity2Dto(existing);
    }

    @Override
    public void delete(String workspaceId, String id) {
        int deleted = mapper.delete(new LambdaQueryWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                .eq(PlannedWaylineEntity::getPlannedWaylineId, id));
        if (deleted <= 0) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }
    }

    @Override
    public Optional<PlannedWaylineDTO> getOne(String workspaceId, String id) {
        PlannedWaylineEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<PlannedWaylineEntity>()
                        .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                        .eq(PlannedWaylineEntity::getPlannedWaylineId, id));
        return Optional.ofNullable(entity2Dto(entity));
    }

    private void validateParam(CreatePlannedWaylineParam param) {
        if (Objects.isNull(param)) {
            throw new IllegalArgumentException("Planned wayline param is required.");
        }
        validateEditableFields(param.getName(), param.getAircraftModelKey(), param.getGatewaySn(),
                param.getAircraftSn(), param.getDefaultHeight(), param.getMaxSpeed(), param.getWaypoints());
    }

    private void validateParam(UpdatePlannedWaylineParam param) {
        if (Objects.isNull(param)) {
            throw new IllegalArgumentException("Planned wayline param is required.");
        }
        validateEditableFields(param.getName(), param.getAircraftModelKey(), param.getGatewaySn(),
                param.getAircraftSn(), param.getDefaultHeight(), param.getMaxSpeed(), param.getWaypoints());
    }

    private void validateEditableFields(String name,
                                        String aircraftModelKey,
                                        String gatewaySn,
                                        String aircraftSn,
                                        Double defaultHeight,
                                        Double maxSpeed,
                                        List<PlannedWaypointDTO> waypoints) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Planned wayline name is required.");
        }
        if (!StringUtils.hasText(aircraftModelKey)) {
            throw new IllegalArgumentException("Planned wayline aircraft model key is required.");
        }
        if (!StringUtils.hasText(gatewaySn)) {
            throw new IllegalArgumentException("Planned wayline gateway sn is required.");
        }
        if (!StringUtils.hasText(aircraftSn)) {
            throw new IllegalArgumentException("Planned wayline aircraft sn is required.");
        }
        if (!isFinite(defaultHeight)) {
            throw new IllegalArgumentException("Planned wayline default height is required.");
        }
        if (!isFinite(maxSpeed)) {
            throw new IllegalArgumentException("Planned wayline max speed is required.");
        }
        validateWaypoints(waypoints);
    }

    private void validateWaypoints(List<PlannedWaypointDTO> waypoints) {
        if (CollectionUtils.isEmpty(waypoints)) {
            throw new IllegalArgumentException("Planned wayline waypoints are required.");
        }
        for (int i = 0; i < waypoints.size(); i++) {
            PlannedWaypointDTO waypoint = waypoints.get(i);
            if (Objects.isNull(waypoint)) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] is required.");
            }
            if (Objects.isNull(waypoint.getOrder()) || waypoint.getOrder() < 1) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] order is required.");
            }
            if (!isFinite(waypoint.getGcjLng())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] gcjLng is required.");
            }
            if (!isFinite(waypoint.getGcjLat())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] gcjLat is required.");
            }
            if (!isFinite(waypoint.getWgsLng())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] wgsLng is required.");
            }
            if (!isFinite(waypoint.getWgsLat())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] wgsLat is required.");
            }
            if (!isFinite(waypoint.getHeight())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] height is required.");
            }
        }
    }

    private boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private void applyEditableFields(PlannedWaylineEntity target, UpdatePlannedWaylineParam param) {
        target.setName(param.getName());
        target.setAircraftModelKey(param.getAircraftModelKey());
        target.setGatewaySn(param.getGatewaySn());
        target.setAircraftSn(param.getAircraftSn());
        target.setDefaultHeight(param.getDefaultHeight());
        target.setMaxSpeed(param.getMaxSpeed());
        target.setWaypointsJson(writeWaypoints(param.getWaypoints()));
    }

    private PlannedWaylineEntity dto2Entity(CreatePlannedWaylineParam param) {
        if (param == null) {
            return new PlannedWaylineEntity();
        }
        return PlannedWaylineEntity.builder()
                .name(param.getName())
                .aircraftModelKey(param.getAircraftModelKey())
                .gatewaySn(param.getGatewaySn())
                .aircraftSn(param.getAircraftSn())
                .defaultHeight(param.getDefaultHeight())
                .maxSpeed(param.getMaxSpeed())
                .waypointsJson(writeWaypoints(param.getWaypoints()))
                .build();
    }

    private PlannedWaylineEntity dto2Entity(PlannedWaylineDTO dto) {
        if (dto == null) {
            return new PlannedWaylineEntity();
        }
        return PlannedWaylineEntity.builder()
                .plannedWaylineId(dto.getPlannedWaylineId())
                .workspaceId(dto.getWorkspaceId())
                .name(dto.getName())
                .aircraftModelKey(dto.getAircraftModelKey())
                .gatewaySn(dto.getGatewaySn())
                .aircraftSn(dto.getAircraftSn())
                .defaultHeight(dto.getDefaultHeight())
                .maxSpeed(dto.getMaxSpeed())
                .waypointsJson(writeWaypoints(dto.getWaypoints()))
                .status(dto.getStatus())
                .publishedWaylineId(dto.getPublishedWaylineId())
                .creator(dto.getCreator())
                .createTime(dto.getCreateTime())
                .updateTime(dto.getUpdateTime())
                .build();
    }

    private PlannedWaylineEntity dto2Entity(UpdatePlannedWaylineParam param) {
        if (param == null) {
            return new PlannedWaylineEntity();
        }
        return PlannedWaylineEntity.builder()
                .name(param.getName())
                .aircraftModelKey(param.getAircraftModelKey())
                .gatewaySn(param.getGatewaySn())
                .aircraftSn(param.getAircraftSn())
                .defaultHeight(param.getDefaultHeight())
                .maxSpeed(param.getMaxSpeed())
                .waypointsJson(writeWaypoints(param.getWaypoints()))
                .build();
    }

    private PlannedWaylineDTO entity2Dto(PlannedWaylineEntity entity) {
        if (entity == null) {
            return null;
        }
        return PlannedWaylineDTO.builder()
                .plannedWaylineId(entity.getPlannedWaylineId())
                .workspaceId(entity.getWorkspaceId())
                .name(entity.getName())
                .aircraftModelKey(entity.getAircraftModelKey())
                .gatewaySn(entity.getGatewaySn())
                .aircraftSn(entity.getAircraftSn())
                .defaultHeight(entity.getDefaultHeight())
                .maxSpeed(entity.getMaxSpeed())
                .waypoints(readWaypoints(entity.getWaypointsJson()))
                .status(entity.getStatus())
                .publishedWaylineId(entity.getPublishedWaylineId())
                .creator(entity.getCreator())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private String writeWaypoints(List<PlannedWaypointDTO> waypoints) {
        try {
            return objectMapper.writeValueAsString(waypoints);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize planned waypoints.", e);
        }
    }

    private List<PlannedWaypointDTO> readWaypoints(String waypointsJson) {
        if (!StringUtils.hasText(waypointsJson)) {
            return new ArrayList<>();
        }
        try {
            List<PlannedWaypointDTO> waypoints = objectMapper.readValue(
                    waypointsJson, new TypeReference<List<PlannedWaypointDTO>>() {
                    });
            return waypoints == null ? new ArrayList<>() : waypoints;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize planned waypoints.", e);
        }
    }
}
