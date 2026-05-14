package com.dji.sample.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dji.sample.component.oss.model.OssConfiguration;
import com.dji.sample.wayline.dao.IPlannedWaylineMapper;
import com.dji.sample.wayline.model.dto.PublishedWaylineCreateDTO;
import com.dji.sample.wayline.model.dto.PublishedWaylineFileDTO;
import com.dji.sample.wayline.model.dto.PlannedWaylineDTO;
import com.dji.sample.wayline.model.dto.PlannedWaypointDTO;
import com.dji.sample.wayline.model.entity.PlannedWaylineEntity;
import com.dji.sample.wayline.model.param.CreatePlannedWaylineParam;
import com.dji.sample.wayline.model.param.PreparePlannedWaylineTaskParam;
import com.dji.sample.wayline.model.param.PublishPlannedWaylineResponse;
import com.dji.sample.wayline.model.param.UpdatePlannedWaylineParam;
import com.dji.sample.wayline.service.IPlannedWaylineService;
import com.dji.sample.wayline.service.IWaylineFileService;
import com.dji.sdk.cloudapi.device.DeviceEnum;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Transactional
@RequiredArgsConstructor
public class PlannedWaylineServiceImpl implements IPlannedWaylineService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_FILE_GENERATED = "file_generated";
    private static final String STATUS_PUBLISHING = "publishing";
    private static final String STATUS_EXECUTING = "executing";
    private static final String STATUS_CANCELED = "canceled";

    private final IPlannedWaylineMapper mapper;

    private final ObjectMapper objectMapper;

    private final IWaylineFileService waylineFileService;

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
        if (STATUS_PUBLISHED.equalsIgnoreCase(existing.getStatus()) || StringUtils.hasText(existing.getPublishedWaylineId())) {
            throw new IllegalArgumentException("Published planned wayline cannot be updated. Save as a new planned wayline instead.");
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
    public PublishPlannedWaylineResponse publish(String workspaceId, String id, String username) {
        PlannedWaylineEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<PlannedWaylineEntity>()
                        .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                        .eq(PlannedWaylineEntity::getPlannedWaylineId, id));
        if (Objects.isNull(existing)) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }
        if (StringUtils.hasText(existing.getPublishedWaylineId())) {
            return PublishPlannedWaylineResponse.builder()
                    .plannedWaylineId(existing.getPlannedWaylineId())
                    .publishedWaylineId(existing.getPublishedWaylineId())
                    .publishedWaylineName(existing.getName())
                    .publisher(existing.getPublisher())
                    .publishTime(existing.getPublishTime())
                    .build();
        }

        validatePublishableRecord(existing);
        PublishedWaylineFileDTO publishedWayline = waylineFileService.createPublishedWayline(
                workspaceId, buildPublishedWaylineCreate(existing));

        long updateTime = System.currentTimeMillis();
        PlannedWaylineEntity publishUpdate = PlannedWaylineEntity.builder()
                .id(existing.getId())
                .publishedWaylineId(publishedWayline.getWaylineId())
                .status(STATUS_PUBLISHED)
                .publisher(username)
                .publishTime(updateTime)
                .updateTime(updateTime)
                .build();
        int updated;
        try {
            updated = mapper.update(publishUpdate, new LambdaUpdateWrapper<PlannedWaylineEntity>()
                    .eq(PlannedWaylineEntity::getId, existing.getId())
                    .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                    .eq(PlannedWaylineEntity::getPlannedWaylineId, id)
                    .eq(PlannedWaylineEntity::getStatus, STATUS_DRAFT)
                    .isNull(PlannedWaylineEntity::getPublishedWaylineId));
        } catch (RuntimeException e) {
            rollbackPublishedWayline(workspaceId, publishedWayline.getWaylineId());
            throw e;
        }
        if (updated <= 0) {
            rollbackPublishedWayline(workspaceId, publishedWayline.getWaylineId());
            PlannedWaylineEntity current = mapper.selectOne(
                    new LambdaQueryWrapper<PlannedWaylineEntity>()
                            .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                            .eq(PlannedWaylineEntity::getPlannedWaylineId, id));
            if (current != null && StringUtils.hasText(current.getPublishedWaylineId())) {
                return PublishPlannedWaylineResponse.builder()
                        .plannedWaylineId(current.getPlannedWaylineId())
                        .publishedWaylineId(current.getPublishedWaylineId())
                        .publishedWaylineName(current.getName())
                        .publisher(current.getPublisher())
                        .publishTime(current.getPublishTime())
                        .build();
            }
            throw new IllegalArgumentException("Failed to publish planned wayline.");
        }
        existing.setPublishedWaylineId(publishedWayline.getWaylineId());
        existing.setStatus(STATUS_PUBLISHED);
        existing.setPublisher(username);
        existing.setPublishTime(updateTime);
        existing.setUpdateTime(updateTime);

        return PublishPlannedWaylineResponse.builder()
                .plannedWaylineId(existing.getPlannedWaylineId())
                .publishedWaylineId(existing.getPublishedWaylineId())
                .publishedWaylineName(existing.getName())
                .publisher(existing.getPublisher())
                .publishTime(existing.getPublishTime())
                .build();
    }

    @Override
    public PlannedWaylineDTO generateFile(String workspaceId, String id, String username) {
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        String previousPublishedWaylineId = null;
        if (StringUtils.hasText(existing.getPublishedWaylineId())) {
            if (isGeneratedWaylineSafe(workspaceId, existing)) {
                enrichGeneratedFileMetadata(workspaceId, existing);
                return entity2Dto(existing);
            }
            previousPublishedWaylineId = existing.getPublishedWaylineId();
            waylineFileService.deleteByWaylineId(workspaceId, existing.getPublishedWaylineId());
            existing.setPublishedWaylineId(null);
            existing.setKmzUrl(null);
            existing.setKmzMd5(null);
            existing.setKmzObjectKey(null);
            existing.setFileGeneratedTime(null);
        }

        existing.setName(sanitizeDjiWaylineName(existing.getName(), existing.getPlannedWaylineId()));
        validatePublishableRecord(existing);
        PublishedWaylineFileDTO publishedWayline = waylineFileService.createPublishedWayline(
                workspaceId, buildPublishedWaylineCreate(existing));
        long now = System.currentTimeMillis();
        existing.setPublishedWaylineId(publishedWayline.getWaylineId());
        existing.setStatus(STATUS_FILE_GENERATED);
        existing.setKmzObjectKey(publishedWayline.getObjectKey());
        existing.setFileGeneratedTime(now);
        existing.setPublisher(username);
        existing.setPublishTime(now);
        enrichGeneratedFileMetadata(workspaceId, existing);
        existing.setUpdateTime(now);

        PlannedWaylineEntity update = PlannedWaylineEntity.builder()
                .id(existing.getId())
                .name(existing.getName())
                .publishedWaylineId(existing.getPublishedWaylineId())
                .status(existing.getStatus())
                .kmzUrl(existing.getKmzUrl())
                .kmzMd5(existing.getKmzMd5())
                .kmzObjectKey(existing.getKmzObjectKey())
                .fileGeneratedTime(existing.getFileGeneratedTime())
                .publisher(existing.getPublisher())
                .publishTime(existing.getPublishTime())
                .updateTime(existing.getUpdateTime())
                .build();
        LambdaUpdateWrapper<PlannedWaylineEntity> updateWrapper = new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getId, existing.getId())
                .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                .eq(PlannedWaylineEntity::getPlannedWaylineId, id);
        if (StringUtils.hasText(previousPublishedWaylineId)) {
            updateWrapper.eq(PlannedWaylineEntity::getPublishedWaylineId, previousPublishedWaylineId);
        } else {
            updateWrapper.isNull(PlannedWaylineEntity::getPublishedWaylineId);
        }
        int updated = mapper.update(update, updateWrapper);
        if (updated <= 0) {
            rollbackPublishedWayline(workspaceId, publishedWayline.getWaylineId());
            throw new IllegalArgumentException("Failed to generate planned wayline file.");
        }
        return entity2Dto(existing);
    }

    @Override
    public PlannedWaylineDTO prepareTask(String workspaceId, String id, String username, PreparePlannedWaylineTaskParam param) {
        if (param == null || !StringUtils.hasText(param.getDockSn())) {
            throw new IllegalArgumentException("Dock sn is required.");
        }
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        if (!StringUtils.hasText(existing.getPublishedWaylineId())) {
            throw new IllegalArgumentException("Generate the planned wayline file before preparing the flight task.");
        }

        long now = System.currentTimeMillis();
        existing.setStatus(STATUS_PUBLISHING);
        existing.setTaskStatus(STATUS_PUBLISHING);
        existing.setFlightId(UUID.randomUUID().toString());
        existing.setDockSn(param.getDockSn());
        existing.setDroneSn(param.getDroneSn());
        existing.setPublisher(username);
        existing.setPublishTime(now);
        existing.setUpdateTime(now);

        updateTaskFields(existing);
        return entity2Dto(existing);
    }

    @Override
    public PlannedWaylineDTO executeTask(String workspaceId, String id) {
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        if (!StringUtils.hasText(existing.getFlightId())) {
            throw new IllegalArgumentException("Prepare the planned wayline task before executing it.");
        }
        long now = System.currentTimeMillis();
        existing.setStatus(STATUS_EXECUTING);
        existing.setTaskStatus(STATUS_EXECUTING);
        existing.setExecutedTime(now);
        existing.setUpdateTime(now);

        updateTaskFields(existing);
        return entity2Dto(existing);
    }

    @Override
    public PlannedWaylineDTO cancelTask(String workspaceId, String id) {
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        if (!StringUtils.hasText(existing.getFlightId())) {
            throw new IllegalArgumentException("Prepare the planned wayline task before canceling it.");
        }
        long now = System.currentTimeMillis();
        existing.setStatus(STATUS_CANCELED);
        existing.setTaskStatus(STATUS_CANCELED);
        existing.setUpdateTime(now);

        updateTaskFields(existing);
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

    private PlannedWaylineEntity getExisting(String workspaceId, String id) {
        PlannedWaylineEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<PlannedWaylineEntity>()
                        .eq(PlannedWaylineEntity::getWorkspaceId, workspaceId)
                        .eq(PlannedWaylineEntity::getPlannedWaylineId, id));
        if (Objects.isNull(existing)) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }
        return existing;
    }

    private void enrichGeneratedFileMetadata(String workspaceId, PlannedWaylineEntity entity) {
        if (!StringUtils.hasText(entity.getPublishedWaylineId())) {
            return;
        }
        waylineFileService.getWaylineByWaylineId(workspaceId, entity.getPublishedWaylineId())
                .ifPresent(file -> {
                    entity.setKmzMd5(file.getSign());
                    if (!StringUtils.hasText(entity.getKmzObjectKey())) {
                        entity.setKmzObjectKey(file.getObjectKey());
                    }
                });
        try {
            URL url = waylineFileService.getObjectUrl(workspaceId, entity.getPublishedWaylineId());
            entity.setKmzUrl(url.toString());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get generated planned wayline file URL.", e);
        }
    }

    private void updateTaskFields(PlannedWaylineEntity existing) {
        PlannedWaylineEntity update = PlannedWaylineEntity.builder()
                .id(existing.getId())
                .status(existing.getStatus())
                .flightId(existing.getFlightId())
                .dockSn(existing.getDockSn())
                .droneSn(existing.getDroneSn())
                .taskStatus(existing.getTaskStatus())
                .taskStatusReason(existing.getTaskStatusReason())
                .taskProgress(existing.getTaskProgress())
                .preparedTime(existing.getPreparedTime())
                .executedTime(existing.getExecutedTime())
                .publisher(existing.getPublisher())
                .publishTime(existing.getPublishTime())
                .updateTime(existing.getUpdateTime())
                .build();
        int updated = mapper.updateById(update);
        if (updated <= 0) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }
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

    private void validatePublishableRecord(PlannedWaylineEntity entity) {
        if (!isFinite(entity.getDefaultHeight()) || entity.getDefaultHeight() <= 0) {
            throw new IllegalArgumentException("Planned wayline default height is invalid.");
        }
        if (!isFinite(entity.getMaxSpeed()) || entity.getMaxSpeed() <= 0) {
            throw new IllegalArgumentException("Planned wayline max speed is invalid.");
        }

        List<PlannedWaypointDTO> waypoints = readWaypoints(entity.getWaypointsJson());
        if (CollectionUtils.isEmpty(waypoints)) {
            throw new IllegalArgumentException("Planned wayline waypoints are required.");
        }
        for (int i = 0; i < waypoints.size(); i++) {
            PlannedWaypointDTO waypoint = waypoints.get(i);
            if (!isLegalLongitude(waypoint.getGcjLng())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] gcjLng is invalid.");
            }
            if (!isLegalLatitude(waypoint.getGcjLat())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] gcjLat is invalid.");
            }
            if (!isLegalLongitude(waypoint.getWgsLng())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] wgsLng is invalid.");
            }
            if (!isLegalLatitude(waypoint.getWgsLat())) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] wgsLat is invalid.");
            }
            if (!isFinite(waypoint.getHeight()) || waypoint.getHeight() <= 0) {
                throw new IllegalArgumentException("Planned wayline waypoint[" + i + "] height is invalid.");
            }
        }
    }

    private boolean isLegalLongitude(Double value) {
        return isFinite(value) && value >= -180.0 && value <= 180.0;
    }

    private boolean isLegalLatitude(Double value) {
        return isFinite(value) && value >= -90.0 && value <= 90.0;
    }

    private PublishedWaylineCreateDTO buildPublishedWaylineCreate(PlannedWaylineEntity entity) {
        String publishedName = sanitizeDjiWaylineName(entity.getName(), entity.getPlannedWaylineId());
        String filename = publishedName + ".kmz";
        String objectKey = StringUtils.hasText(OssConfiguration.objectDirPrefix)
                ? trimTrailingSlash(OssConfiguration.objectDirPrefix) + "/" + entity.getPlannedWaylineId() + ".kmz"
                : entity.getPlannedWaylineId() + ".kmz";
        return PublishedWaylineCreateDTO.builder()
                .filename(filename)
                .objectKey(objectKey)
                .username(entity.getCreator())
                .content(buildPublishedKmz(entity, publishedName))
                .build();
    }

    private byte[] buildPublishedKmz(PlannedWaylineEntity entity, String publishedName) {
        DeviceEnum droneDevice = resolveDroneDevice(entity.getAircraftModelKey());
        DeviceEnum payloadDevice = resolvePayloadDevice(droneDevice);
        List<PlannedWaypointDTO> waypoints = readWaypoints(entity.getWaypointsJson());

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
                zipOutputStream.write(buildTemplateKml(droneDevice, payloadDevice).getBytes(StandardCharsets.UTF_8));
                zipOutputStream.closeEntry();
                zipOutputStream.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
                zipOutputStream.write(buildWaylinesWpml(publishedName, waypoints).getBytes(StandardCharsets.UTF_8));
                zipOutputStream.closeEntry();
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate published KMZ.", e);
        }
    }

    private DeviceEnum resolveDroneDevice(String aircraftModelKey) {
        try {
            return DeviceEnum.valueOf(aircraftModelKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported aircraft model for planned-wayline publish: " + aircraftModelKey);
        }
    }

    private DeviceEnum resolvePayloadDevice(DeviceEnum droneDevice) {
        switch (droneDevice) {
            case M30:
                return DeviceEnum.M30_CAMERA;
            case M30T:
                return DeviceEnum.M30T_CAMERA;
            case M3E:
                return DeviceEnum.M3E_CAMERA;
            case M3T:
                return DeviceEnum.M3T_CAMERA;
            case M3M:
                return DeviceEnum.M3M_CAMERA;
            case M3D:
                return DeviceEnum.M3D_CAMERA;
            case M3TD:
                return DeviceEnum.M3TD_CAMERA;
            case M300:
            case M350:
                return DeviceEnum.H20T;
            default:
                throw new IllegalArgumentException("Unsupported aircraft model for planned-wayline publish: " + droneDevice.name());
        }
    }

    private String buildTemplateKml(DeviceEnum droneDevice, DeviceEnum payloadDevice) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.2\">"
                + "<Document>"
                + "<wpml:templateType>waypoint</wpml:templateType>"
                + "<wpml:droneInfo>"
                + "<wpml:droneEnumValue>" + droneDevice.getType().getType() + "</wpml:droneEnumValue>"
                + "<wpml:droneSubEnumValue>" + droneDevice.getSubType().getSubType() + "</wpml:droneSubEnumValue>"
                + "</wpml:droneInfo>"
                + "<wpml:payloadInfo>"
                + "<wpml:payloadEnumValue>" + payloadDevice.getType().getType() + "</wpml:payloadEnumValue>"
                + "<wpml:payloadSubEnumValue>" + payloadDevice.getSubType().getSubType() + "</wpml:payloadSubEnumValue>"
                + "</wpml:payloadInfo>"
                + "</Document>"
                + "</kml>";
    }

    private String buildWaylinesWpml(String publishedName, List<PlannedWaypointDTO> waypoints) {
        String placemarks = waypoints.stream()
                .map(waypoint -> "<Placemark>"
                        + "<name>" + waypoint.getOrder() + "</name>"
                        + "<Point><coordinates>" + waypoint.getWgsLng() + "," + waypoint.getWgsLat() + "," + waypoint.getHeight() + "</coordinates></Point>"
                        + "</Placemark>")
                .collect(Collectors.joining());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.2\">"
                + "<Document>"
                + "<name>" + escapeXml(publishedName) + "</name>"
                + "<wpml:waylineCoordinateSysParam>"
                + "<wpml:coordinateMode>WGS84</wpml:coordinateMode>"
                + "<wpml:heightMode>relativeToStartPoint</wpml:heightMode>"
                + "</wpml:waylineCoordinateSysParam>"
                + "<Folder>"
                + placemarks
                + "</Folder>"
                + "</Document>"
                + "</kml>";
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String sanitizeDjiWaylineName(String name, String fallback) {
        String sanitized = StringUtils.hasText(name) ? name.trim() : "";
        sanitized = sanitized.replaceAll("[<>:\"/|?*._\\\\]+", "-")
                .replaceAll("\\s+", " ")
                .replaceAll("^-+|-+$", "")
                .trim();
        if (StringUtils.hasText(sanitized)) {
            return sanitized;
        }
        return StringUtils.hasText(fallback) ? fallback : "planned-wayline";
    }

    private boolean isDjiSafeWaylineName(String name) {
        return StringUtils.hasText(name) && name.matches("^[^<>:\"/|?*._\\\\]+$");
    }

    private boolean isGeneratedWaylineSafe(String workspaceId, PlannedWaylineEntity entity) {
        if (!isDjiSafeWaylineName(entity.getName())) {
            return false;
        }
        return waylineFileService.getWaylineByWaylineId(workspaceId, entity.getPublishedWaylineId())
                .map(file -> isDjiSafeWaylineName(file.getName()))
                .orElse(false);
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void rollbackPublishedWayline(String workspaceId, String publishedWaylineId) {
        try {
            boolean deleted = waylineFileService.deleteByWaylineId(workspaceId, publishedWaylineId);
            if (!deleted) {
                throw new IllegalStateException("Failed to rollback published wayline after planned publish error.");
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to rollback published wayline after planned publish error.", e);
        }
    }

    private void applyEditableFields(PlannedWaylineEntity target, UpdatePlannedWaylineParam param) {
        target.setName(sanitizeDjiWaylineName(param.getName(), target.getPlannedWaylineId()));
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
                .name(sanitizeDjiWaylineName(param.getName(), null))
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
                .kmzUrl(dto.getKmzUrl())
                .kmzMd5(dto.getKmzMd5())
                .kmzObjectKey(dto.getKmzObjectKey())
                .fileGeneratedTime(dto.getFileGeneratedTime())
                .flightId(dto.getFlightId())
                .dockSn(dto.getDockSn())
                .droneSn(dto.getDroneSn())
                .taskStatus(dto.getTaskStatus())
                .taskStatusReason(dto.getTaskStatusReason())
                .taskProgress(dto.getTaskProgress())
                .preparedTime(dto.getPreparedTime())
                .executedTime(dto.getExecutedTime())
                .creator(dto.getCreator())
                .publisher(dto.getPublisher())
                .publishTime(dto.getPublishTime())
                .createTime(dto.getCreateTime())
                .updateTime(dto.getUpdateTime())
                .build();
    }

    private PlannedWaylineEntity dto2Entity(UpdatePlannedWaylineParam param) {
        if (param == null) {
            return new PlannedWaylineEntity();
        }
        return PlannedWaylineEntity.builder()
                .name(sanitizeDjiWaylineName(param.getName(), null))
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
                .kmzUrl(entity.getKmzUrl())
                .kmzMd5(entity.getKmzMd5())
                .kmzObjectKey(entity.getKmzObjectKey())
                .fileGeneratedTime(entity.getFileGeneratedTime())
                .flightId(entity.getFlightId())
                .dockSn(entity.getDockSn())
                .droneSn(entity.getDroneSn())
                .taskStatus(entity.getTaskStatus())
                .taskStatusReason(entity.getTaskStatusReason())
                .taskProgress(entity.getTaskProgress())
                .preparedTime(entity.getPreparedTime())
                .executedTime(entity.getExecutedTime())
                .creator(entity.getCreator())
                .publisher(entity.getPublisher())
                .publishTime(entity.getPublishTime())
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
