package com.yx.uavfire.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.component.oss.model.OssConfiguration;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineCreateDTO;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineFileDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.model.dto.PlannedWaypointDTO;
import com.yx.uavfire.wayline.model.dto.WaypointActionDTO;
import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;
import com.yx.uavfire.wayline.model.param.CreatePlannedWaylineParam;
import com.yx.uavfire.wayline.model.param.PreparePlannedWaylineTaskParam;
import com.yx.uavfire.wayline.model.param.PublishPlannedWaylineResponse;
import com.yx.uavfire.wayline.model.param.UpdatePlannedWaylineParam;
import com.yx.uavfire.wayline.service.IPlannedWaylineService;
import com.yx.uavfire.wayline.service.IWaylineFileService;
import com.dji.sdk.cloudapi.device.DeviceEnum;
import com.dji.sdk.cloudapi.device.DeviceTypeEnum;
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

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

@Service
@Transactional
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class PlannedWaylineServiceImpl implements IPlannedWaylineService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_FILE_GENERATED = "file_generated";
    private static final String STATUS_PUBLISHING = "publishing";
    private static final String STATUS_EXECUTING = "executing";
    private static final String STATUS_CANCELED = "canceled";

    private static final String STATUS_PAUSED = "paused";
    private static final String STATUS_STOPPED = "stopped";

    private final IPlannedWaylineMapper mapper;

    private final ObjectMapper objectMapper;

    private final IWaylineFileService waylineFileService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SDKWaylineService sdkWaylineService;

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
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        if (!StringUtils.hasText(existing.getPublishedWaylineId())) {
            throw new IllegalArgumentException("Generate the planned wayline file before preparing the flight task.");
        }

        long now = System.currentTimeMillis();
        existing.setStatus(STATUS_PUBLISHING);
        existing.setTaskStatus(STATUS_PUBLISHING);
        existing.setFlightId(UUID.randomUUID().toString());
        if (param != null && StringUtils.hasText(param.getDockSn())) {
            existing.setDockSn(param.getDockSn());
        }
        if (param != null && StringUtils.hasText(param.getDroneSn())) {
            existing.setDroneSn(param.getDroneSn());
        }
        existing.setPublisher(username);
        existing.setPublishTime(now);
        existing.setUpdateTime(now);
        existing.setPreparedTime(now);

        // P3: 如果是 dock 路径,真发 flighttaskPrepare MQTT 给机场
        if (StringUtils.hasText(existing.getDockSn())) {
            invokeDockPrepare(existing, param);
        }
        // Agent 路径无需发命令:用户在前端点 "执行" 时会触发 WAYLINE_DISPATCH (executeTask)

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

        // 按 dockSn 路由发命令
        if (StringUtils.hasText(existing.getDockSn())) {
            invokeDockExecute(existing);
        } else {
            invokeAgentDispatch(existing);
        }

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

        // 按 dockSn 路由发命令 (cancel 同义于 dock flighttaskUndo / agent STOP)
        if (StringUtils.hasText(existing.getDockSn())) {
            try {
                invokeDockControl(existing, ControlOp.STOP);
            } catch (RuntimeException e) {
                log.warn("dock cancel command failed, marking canceled locally: {}", e.getMessage());
            }
        } else {
            try {
                invokeAgentControl(existing, ControlOp.STOP);
            } catch (RuntimeException e) {
                log.warn("agent cancel command failed, marking canceled locally: {}", e.getMessage());
            }
        }

        existing.setStatus(STATUS_CANCELED);
        existing.setTaskStatus(STATUS_CANCELED);
        existing.setUpdateTime(now);

        updateTaskFields(existing);
        return entity2Dto(existing);
    }

    @Override
    public PlannedWaylineDTO pauseTask(String workspaceId, String id) {
        return controlTask(workspaceId, id, ControlOp.PAUSE);
    }

    @Override
    public PlannedWaylineDTO recoveryTask(String workspaceId, String id) {
        return controlTask(workspaceId, id, ControlOp.RECOVERY);
    }

    @Override
    public PlannedWaylineDTO stopTask(String workspaceId, String id) {
        return controlTask(workspaceId, id, ControlOp.STOP);
    }

    @Override
    public PlannedWaylineDTO queryBreakpoint(String workspaceId, String id) {
        return controlTask(workspaceId, id, ControlOp.QUERY_BREAKPOINT);
    }

    private enum ControlOp { PAUSE, RECOVERY, STOP, QUERY_BREAKPOINT }

    private PlannedWaylineDTO controlTask(String workspaceId, String id, ControlOp op) {
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        if (!StringUtils.hasText(existing.getFlightId())) {
            throw new IllegalArgumentException("Task has not been prepared yet.");
        }

        boolean isDockPath = StringUtils.hasText(existing.getDockSn());
        if (isDockPath) {
            invokeDockControl(existing, op);
        } else {
            invokeAgentControl(existing, op);
        }

        long now = System.currentTimeMillis();
        switch (op) {
            case PAUSE:
                existing.setTaskStatus(STATUS_PAUSED);
                break;
            case RECOVERY:
                existing.setTaskStatus(STATUS_EXECUTING);
                break;
            case STOP:
                existing.setTaskStatus(STATUS_STOPPED);
                break;
            case QUERY_BREAKPOINT:
                // 不动状态;agent/dock 返回断点会异步到 progress 事件,持久化到 break_point_json
                break;
        }
        existing.setUpdateTime(now);
        updateTaskFields(existing);
        return entity2Dto(existing);
    }

    /**
     * P3: Dock 路径 flighttask_prepare。装配 FlighttaskPrepareRequest 并通过
     * sdkWaylineService 发往机场。注:真飞 deferred,无机场时此路径不会走到。
     */
    private void invokeDockPrepare(PlannedWaylineEntity entity, PreparePlannedWaylineTaskParam param) {
        if (sdkWaylineService == null) {
            log.warn("sdkWaylineService not wired, skipping dock prepare for flight {} (local-only mode)", entity.getFlightId());
            return;
        }
        com.dji.sdk.config.version.GatewayManager gateway = com.dji.sdk.common.SDKManager.getDeviceSDK(entity.getDockSn());
        if (gateway == null) {
            throw new IllegalStateException("Dock " + entity.getDockSn() + " is not online.");
        }
        if (!StringUtils.hasText(entity.getKmzUrl())) {
            throw new IllegalStateException("KMZ url is missing; regenerate the planned wayline file.");
        }
        com.dji.sdk.cloudapi.wayline.FlighttaskPrepareRequest req = new com.dji.sdk.cloudapi.wayline.FlighttaskPrepareRequest()
                .setFlightId(entity.getFlightId())
                .setTaskType(com.dji.sdk.cloudapi.wayline.TaskTypeEnum.IMMEDIATE)
                .setWaylineType(com.dji.sdk.cloudapi.wayline.WaylineTypeEnum.WAYPOINT)
                .setFile(new com.dji.sdk.cloudapi.wayline.FlighttaskFile()
                        .setUrl(entity.getKmzUrl())
                        .setFingerprint(entity.getKmzMd5()));
        if (entity.getRthAltitude() != null) {
            req.setRthAltitude(entity.getRthAltitude());
        }
        if (param != null) {
            if (param.getExecuteTime() != null) {
                req.setExecuteTime(param.getExecuteTime());
            }
            if (Boolean.TRUE.equals(param.getSimulate()) && param.getSimulateLat() != null && param.getSimulateLng() != null) {
                req.setSimulateMission(new com.dji.sdk.cloudapi.wayline.SimulateMission()
                        .setIsEnable(com.dji.sdk.cloudapi.wayline.SimulateSwitchEnum.ENABLE)
                        .setLatitude(param.getSimulateLat().floatValue())
                        .setLongitude(param.getSimulateLng().floatValue()));
            }
            if (param.getMinBattery() != null || param.getBeginTime() != null || param.getEndTime() != null) {
                com.dji.sdk.cloudapi.wayline.ReadyConditions rc = new com.dji.sdk.cloudapi.wayline.ReadyConditions();
                if (param.getMinBattery() != null) rc.setBatteryCapacity(param.getMinBattery());
                if (param.getBeginTime() != null) rc.setBeginTime(param.getBeginTime());
                if (param.getEndTime() != null) rc.setEndTime(param.getEndTime());
                req.setReadyConditions(rc);
            }
        }
        com.dji.sdk.mqtt.services.TopicServicesResponse<com.dji.sdk.mqtt.services.ServicesReplyData> reply =
                sdkWaylineService.flighttaskPrepare(gateway, req);
        if (reply == null || reply.getData() == null || reply.getData().getResult() == null
                || !reply.getData().getResult().isSuccess()) {
            throw new IllegalStateException("Dock prepare failed: "
                    + (reply != null && reply.getData() != null ? reply.getData().getResult() : "no reply"));
        }
    }

    /** P3: Dock 路径 flighttask_execute。 */
    private void invokeDockExecute(PlannedWaylineEntity entity) {
        if (sdkWaylineService == null) {
            log.warn("sdkWaylineService not wired, skipping dock execute for flight {} (local-only mode)", entity.getFlightId());
            return;
        }
        com.dji.sdk.config.version.GatewayManager gateway = com.dji.sdk.common.SDKManager.getDeviceSDK(entity.getDockSn());
        if (gateway == null) {
            throw new IllegalStateException("Dock " + entity.getDockSn() + " is not online.");
        }
        com.dji.sdk.mqtt.services.TopicServicesResponse<com.dji.sdk.mqtt.services.ServicesReplyData> reply =
                sdkWaylineService.flighttaskExecute(gateway,
                        new com.dji.sdk.cloudapi.wayline.FlighttaskExecuteRequest().setFlightId(entity.getFlightId()));
        if (reply == null || reply.getData() == null || reply.getData().getResult() == null
                || !reply.getData().getResult().isSuccess()) {
            throw new IllegalStateException("Dock execute failed: "
                    + (reply != null && reply.getData() != null ? reply.getData().getResult() : "no reply"));
        }
    }

    /** Agent 路径派发航线 (WAYLINE_DISPATCH with KMZ url + missionId)。 */
    private void invokeAgentDispatch(PlannedWaylineEntity entity) {
        if (waylineAgentService == null) {
            log.warn("waylineAgentService not wired, skipping agent dispatch for flight {} (local-only mode)", entity.getFlightId());
            return;
        }
        if (!StringUtils.hasText(entity.getKmzUrl())) {
            log.warn("KMZ url missing for flight {}, skipping agent dispatch", entity.getFlightId());
            return;
        }
        String droneSn = StringUtils.hasText(entity.getDroneSn()) ? entity.getDroneSn() : "RC_PLUS_LOCAL";
        com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO data =
                new com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO()
                        .setMissionId(entity.getFlightId())
                        .setKmzUrl(entity.getKmzUrl())
                        .setKmzMd5(entity.getKmzMd5());
        waylineAgentService.dispatchWayline(droneSn, data);
    }

    private void invokeAgentControl(PlannedWaylineEntity entity, ControlOp op) {
        if (waylineAgentService == null) {
            throw new IllegalStateException("Agent service unavailable; cannot route control command.");
        }
        String droneSn = StringUtils.hasText(entity.getDroneSn()) ? entity.getDroneSn() : "RC_PLUS_LOCAL";
        com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO data =
                new com.yx.uavfire.wayline.agent.model.dto.WaylineControlDataDTO().setMissionId(entity.getFlightId());
        switch (op) {
            case PAUSE:           waylineAgentService.pauseMission(droneSn, data); break;
            case RECOVERY:        waylineAgentService.resumeMission(droneSn, data); break;
            case STOP:            waylineAgentService.stopMission(droneSn, data); break;
            case QUERY_BREAKPOINT: waylineAgentService.queryBreakpoint(droneSn, data); break;
        }
    }

    private void invokeDockControl(PlannedWaylineEntity entity, ControlOp op) {
        // P3: 真接通 dock 路径。实际真飞回归 deferred 到机场到位后。
        if (sdkWaylineService == null) {
            throw new IllegalStateException("Cloud SDK wayline service unavailable.");
        }
        com.dji.sdk.config.version.GatewayManager gateway;
        try {
            gateway = com.dji.sdk.common.SDKManager.getDeviceSDK(entity.getDockSn());
        } catch (Exception e) {
            throw new IllegalStateException("Dock " + entity.getDockSn() + " is not online via Cloud API.", e);
        }
        if (gateway == null) {
            throw new IllegalStateException("Dock " + entity.getDockSn() + " is not online via Cloud API.");
        }
        com.dji.sdk.mqtt.services.TopicServicesResponse<com.dji.sdk.mqtt.services.ServicesReplyData> reply;
        switch (op) {
            case PAUSE:
                reply = sdkWaylineService.flighttaskPause(gateway);
                break;
            case RECOVERY:
                reply = sdkWaylineService.flighttaskRecovery(gateway);
                break;
            case STOP:
                reply = sdkWaylineService.flighttaskUndo(gateway,
                        new com.dji.sdk.cloudapi.wayline.FlighttaskUndoRequest()
                                .setFlightIds(java.util.List.of(entity.getFlightId())));
                break;
            case QUERY_BREAKPOINT:
                // Cloud SDK 中 break_point 通过 flighttask_progress.ext.break_point 异步上报,
                // 无主动 query 命令;直接返回让 controller 反馈当前 break_point_json (来自 P2.b 持久化)。
                return;
            default:
                return;
        }
        if (reply == null || reply.getData() == null || reply.getData().getResult() == null
                || !reply.getData().getResult().isSuccess()) {
            throw new IllegalStateException("Dock control " + op + " failed: "
                    + (reply != null && reply.getData() != null ? reply.getData().getResult() : "no reply"));
        }
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

    // Field set + namespace mirrors Pilot 2's real M4T export
    // (baseline: /Users/likewang/uavfire/kmz/麟游官坪.kmz; see WAYLINE_AGENT_CONTRACT.md §2).
    private static final String NS_KML = "http://www.opengis.net/kml/2.2";
    private static final String NS_WPML = "http://www.dji.com/wpmz/1.0.6";
    private static final String FINISH_ACTION = "goHome";
    private static final String EXIT_ON_RC_LOST = "goContinue";
    private static final String EXECUTE_RC_LOST_ACTION = "goBack";
    private static final int TAKE_OFF_SECURITY_HEIGHT_M = 20;
    private static final int GLOBAL_TRANSITIONAL_SPEED_MPS = 5;
    private static final int AUTO_FLIGHT_SPEED_MPS = 5;

    private byte[] buildPublishedKmz(PlannedWaylineEntity entity, String publishedName) {
        DeviceEnum droneDevice = resolveDroneDevice(entity.getAircraftModelKey());
        DeviceEnum payloadDevice = resolvePayloadDevice(droneDevice);
        List<PlannedWaypointDTO> waypoints = readWaypoints(entity.getWaypointsJson());

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
                zipOutputStream.write(buildTemplateKml(publishedName, entity, droneDevice, payloadDevice, waypoints));
                zipOutputStream.closeEntry();
                zipOutputStream.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
                zipOutputStream.write(buildWaylinesWpml(publishedName, entity, droneDevice, payloadDevice, waypoints));
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
            case M4T:
                return DeviceEnum.M4T_CAMERA;
            case M300:
            case M350:
                return DeviceEnum.H20T;
            default:
                throw new IllegalArgumentException("Unsupported aircraft model for planned-wayline publish: " + droneDevice.name());
        }
    }

    private byte[] buildTemplateKml(String name, PlannedWaylineEntity entity, DeviceEnum droneDevice, DeviceEnum payloadDevice, List<PlannedWaypointDTO> waypoints) {
        return writeKml(w -> {
            elem(w, NS_KML, "name", name);
            long now = System.currentTimeMillis();
            elem(w, "createTime", String.valueOf(now));
            elem(w, "updateTime", String.valueOf(now));

            writeMissionConfig(w, entity, droneDevice, payloadDevice);

            w.writeStartElement("Folder");
            elem(w, "templateType", "waypoint");
            elem(w, "templateId", "0");

            w.writeStartElement(NS_WPML, "waylineCoordinateSysParam");
            elem(w, "coordinateMode", "WGS84");
            elem(w, "heightMode", "relativeToStartPoint");
            elem(w, "positioningType", "GPS");
            w.writeEndElement();

            elem(w, "autoFlightSpeed", String.valueOf(AUTO_FLIGHT_SPEED_MPS));
            elem(w, "globalHeight", String.valueOf(globalAvgHeight(waypoints)));
            elem(w, "caliFlightEnable", "0");
            elem(w, "gimbalPitchMode", "manual");

            w.writeStartElement(NS_WPML, "globalWaypointHeadingParam");
            elem(w, "waypointHeadingMode", "followWayline");
            elem(w, "waypointHeadingAngle", "0");
            elem(w, "waypointPoiPoint", "0.000000,0.000000,0.000000");
            elem(w, "waypointHeadingPoiIndex", "0");
            w.writeEndElement();

            elem(w, "globalWaypointTurnMode", "toPointAndStopWithDiscontinuityCurvature");
            elem(w, "globalUseStraightLine", "0");

            int index = 0;
            for (PlannedWaypointDTO wp : waypoints) {
                writeTemplatePlacemark(w, wp, index++);
            }

            w.writeEndElement(); // /Folder
        });
    }

    private byte[] buildWaylinesWpml(String name, PlannedWaylineEntity entity, DeviceEnum droneDevice, DeviceEnum payloadDevice, List<PlannedWaypointDTO> waypoints) {
        return writeKml(w -> {
            elem(w, NS_KML, "name", name);

            writeMissionConfig(w, entity, droneDevice, payloadDevice);

            w.writeStartElement("Folder");
            elem(w, "templateId", "0");
            elem(w, "executeHeightMode", "relativeToStartPoint");
            elem(w, "waylineId", "0");

            // 校验侧 WaylineFileServiceImpl.validKmzBytes 在 waylines.wpml 中查找该元素，
            // 缺失会以 "The file format is incorrect." 报错。Pilot 2 真机导出也包含此节点。
            w.writeStartElement(NS_WPML, "waylineCoordinateSysParam");
            elem(w, "coordinateMode", "WGS84");
            elem(w, "heightMode", "relativeToStartPoint");
            elem(w, "positioningType", "GPS");
            w.writeEndElement();

            double distance = totalDistanceMeters(waypoints);
            elem(w, "distance", String.valueOf(distance));
            elem(w, "duration", String.valueOf(distance / Math.max(AUTO_FLIGHT_SPEED_MPS, 1)));
            elem(w, "autoFlightSpeed", String.valueOf(AUTO_FLIGHT_SPEED_MPS));

            int index = 0;
            for (PlannedWaypointDTO wp : waypoints) {
                writeWaylinePlacemark(w, wp, index++);
            }

            w.writeEndElement(); // /Folder
        });
    }

    private void writeMissionConfig(XMLStreamWriter w, PlannedWaylineEntity entity, DeviceEnum droneDevice, DeviceEnum payloadDevice) throws XMLStreamException {
        w.writeStartElement(NS_WPML, "missionConfig");
        elem(w, "flyToWaylineMode", "safely");
        elem(w, "finishAction",
                entity.getFinishAction() != null ? entity.getFinishAction() : FINISH_ACTION);
        elem(w, "exitOnRCLost",
                entity.getExitOnRcLost() != null ? entity.getExitOnRcLost() : EXIT_ON_RC_LOST);
        elem(w, "executeRCLostAction",
                entity.getRcLostAction() != null ? entity.getRcLostAction() : EXECUTE_RC_LOST_ACTION);
        elem(w, "takeOffSecurityHeight", String.valueOf(
                entity.getTakeoffSecurityHeight() != null ? entity.getTakeoffSecurityHeight() : TAKE_OFF_SECURITY_HEIGHT_M));
        elem(w, "globalTransitionalSpeed", formatNumeric(
                entity.getGlobalTransitionalSpeed() != null ? entity.getGlobalTransitionalSpeed() : (double) GLOBAL_TRANSITIONAL_SPEED_MPS));
        writeDroneInfo(w, droneDevice);
        elem(w, "waylineAvoidLimitAreaMode", "0");
        writePayloadInfo(w, payloadDevice);
        w.writeEndElement(); // /missionConfig
    }

    private void writeDroneInfo(XMLStreamWriter w, DeviceEnum droneDevice) throws XMLStreamException {
        w.writeStartElement(NS_WPML, "droneInfo");
        // KMZ 离线导出和 Cloud API runtime 对 M4 系列飞机的 type 编码不一致：
        // KMZ 文件里 Pilot 2 实测写 100；Cloud API runtime 上报 99（DeviceTypeEnum.M4_SERIES）。
        int droneEnumValue = droneDevice.getType() == DeviceTypeEnum.M4_SERIES
                ? 100
                : droneDevice.getType().getType();
        elem(w, "droneEnumValue", String.valueOf(droneEnumValue));
        elem(w, "droneSubEnumValue", String.valueOf(droneDevice.getSubType().getSubType()));
        w.writeEndElement();
    }

    private void writePayloadInfo(XMLStreamWriter w, DeviceEnum payloadDevice) throws XMLStreamException {
        w.writeStartElement(NS_WPML, "payloadInfo");
        elem(w, "payloadEnumValue", String.valueOf(payloadDevice.getType().getType()));
        elem(w, "payloadSubEnumValue", String.valueOf(payloadDevice.getSubType().getSubType()));
        elem(w, "payloadPositionIndex", "0");
        w.writeEndElement();
    }

    private void writeTemplatePlacemark(XMLStreamWriter w, PlannedWaypointDTO wp, int index) throws XMLStreamException {
        w.writeStartElement("Placemark");
        w.writeStartElement("Point");
        elem(w, NS_KML, "coordinates", wp.getWgsLng() + "," + wp.getWgsLat());
        w.writeEndElement();
        elem(w, "index", String.valueOf(index));
        elem(w, "ellipsoidHeight", String.valueOf(wp.getHeight()));
        elem(w, "height", String.valueOf(wp.getHeight()));
        w.writeStartElement(NS_WPML, "waypointTurnParam");
        elem(w, "waypointTurnMode",
                wp.getTurnMode() != null ? wp.getTurnMode() : "toPointAndPassWithContinuityCurvature");
        elem(w, "waypointTurnDampingDist", formatNumeric(
                wp.getTurnDamping() != null ? wp.getTurnDamping() : 0.0));
        w.writeEndElement();
        elem(w, "useGlobalSpeed", wp.getSpeed() != null ? "0" : "1");
        elem(w, "useGlobalHeadingParam", hasCustomHeading(wp) ? "0" : "1");
        elem(w, "useStraightLine", "1");
        writeActionGroups(w, wp, index);
        elem(w, "isRisky", "0");
        w.writeEndElement();
    }

    private static boolean hasCustomHeading(PlannedWaypointDTO wp) {
        return wp.getHeadingMode() != null || wp.getHeadingAngle() != null || wp.getPoiLng() != null;
    }

    private void writeWaylinePlacemark(XMLStreamWriter w, PlannedWaypointDTO wp, int index) throws XMLStreamException {
        w.writeStartElement("Placemark");
        w.writeStartElement("Point");
        elem(w, NS_KML, "coordinates", wp.getWgsLng() + "," + wp.getWgsLat());
        w.writeEndElement();
        elem(w, "index", String.valueOf(index));
        elem(w, "executeHeight", String.valueOf(wp.getHeight()));
        elem(w, "waypointSpeed", formatNumeric(
                wp.getSpeed() != null ? wp.getSpeed() : (double) AUTO_FLIGHT_SPEED_MPS));
        w.writeStartElement(NS_WPML, "waypointHeadingParam");
        elem(w, "waypointHeadingMode",
                wp.getHeadingMode() != null ? wp.getHeadingMode() : "followWayline");
        elem(w, "waypointHeadingAngle", formatNumeric(
                wp.getHeadingAngle() != null ? wp.getHeadingAngle() : 0.0));
        String poiStr = "0.000000,0.000000,0.000000";
        if (wp.getPoiLng() != null && wp.getPoiLat() != null) {
            double alt = wp.getPoiAlt() != null ? wp.getPoiAlt() : 0.0;
            poiStr = wp.getPoiLng() + "," + wp.getPoiLat() + "," + alt;
        }
        elem(w, "waypointPoiPoint", poiStr);
        elem(w, "waypointHeadingAngleEnable", "0");
        elem(w, "waypointHeadingPoiIndex", "0");
        w.writeEndElement();
        w.writeStartElement(NS_WPML, "waypointTurnParam");
        elem(w, "waypointTurnMode",
                wp.getTurnMode() != null ? wp.getTurnMode() : "toPointAndPassWithContinuityCurvature");
        elem(w, "waypointTurnDampingDist", formatNumeric(
                wp.getTurnDamping() != null ? wp.getTurnDamping() : 10.0));
        w.writeEndElement();
        elem(w, "useStraightLine", "1");
        w.writeStartElement(NS_WPML, "waypointGimbalHeadingParam");
        elem(w, "waypointGimbalPitchAngle", formatNumeric(
                wp.getGimbalPitch() != null ? wp.getGimbalPitch() : 0.0));
        elem(w, "waypointGimbalYawAngle", formatNumeric(
                wp.getGimbalYaw() != null ? wp.getGimbalYaw() : 0.0));
        w.writeEndElement();
        writeActionGroups(w, wp, index);
        elem(w, "isRisky", "0");
        elem(w, "waypointWorkType", "0");
        w.writeEndElement();
    }

    /**
     * 输出航点 actionGroup 块。null/空 actions 不写。结构对齐 Pilot 2 真机导出
     * (kmz/麟游官坪.kmz):
     *   <wpml:actionGroup>
     *     <wpml:actionGroupId>{wpIdx}</wpml:actionGroupId>
     *     <wpml:actionGroupStartIndex>{wpIdx}</wpml:actionGroupStartIndex>
     *     <wpml:actionGroupEndIndex>{wpIdx}</wpml:actionGroupEndIndex>
     *     <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
     *     <wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
     *     <wpml:action>...</wpml:action> (按 wp.actions 顺序;actionId 从 0 递增)
     *   </wpml:actionGroup>
     */
    private void writeActionGroups(XMLStreamWriter w, PlannedWaypointDTO wp, int waypointIndex) throws XMLStreamException {
        List<WaypointActionDTO> actions = wp.getActions();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        w.writeStartElement(NS_WPML, "actionGroup");
        elem(w, "actionGroupId", String.valueOf(waypointIndex));
        elem(w, "actionGroupStartIndex", String.valueOf(waypointIndex));
        elem(w, "actionGroupEndIndex", String.valueOf(waypointIndex));
        elem(w, "actionGroupMode", "sequence");

        WaypointActionDTO firstAction = actions.get(0);
        String triggerType = firstAction.getActionTrigger() != null ? firstAction.getActionTrigger() : "reachPoint";
        w.writeStartElement(NS_WPML, "actionTrigger");
        elem(w, "actionTriggerType", triggerType);
        if ("multipleTiming".equals(triggerType) && firstAction.getActionTriggerParam() != null) {
            elem(w, "actionTriggerParam", formatNumeric(firstAction.getActionTriggerParam()));
        }
        w.writeEndElement(); // /actionTrigger

        int actionId = 0;
        for (WaypointActionDTO action : actions) {
            w.writeStartElement(NS_WPML, "action");
            elem(w, "actionId", String.valueOf(actionId++));
            elem(w, "actionActuatorFunc", action.getActuatorFunc());
            w.writeStartElement(NS_WPML, "actionActuatorFuncParam");
            if (action.getParams() != null) {
                for (java.util.Map.Entry<String, Object> entry : action.getParams().entrySet()) {
                    Object v = entry.getValue();
                    String s = (v instanceof Number) ? formatNumeric((Number) v) : String.valueOf(v);
                    elem(w, entry.getKey(), s);
                }
            }
            w.writeEndElement(); // /actionActuatorFuncParam
            w.writeEndElement(); // /action
        }
        w.writeEndElement(); // /actionGroup
    }

    private static int globalAvgHeight(List<PlannedWaypointDTO> wps) {
        if (wps == null || wps.isEmpty()) return 100;
        double sum = 0;
        int n = 0;
        for (PlannedWaypointDTO wp : wps) {
            if (wp.getHeight() != null) { sum += wp.getHeight(); n++; }
        }
        return n == 0 ? 100 : (int) (sum / n);
    }

    /** Haversine distance sum in meters between consecutive WGS84 waypoints. */
    private static double totalDistanceMeters(List<PlannedWaypointDTO> wps) {
        if (wps == null || wps.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < wps.size(); i++) {
            total += haversineMeters(
                    wps.get(i - 1).getWgsLat(), wps.get(i - 1).getWgsLng(),
                    wps.get(i).getWgsLat(), wps.get(i).getWgsLng());
        }
        return total;
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private void elem(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        elem(w, NS_WPML, name, value);
    }

    /** 数值序列化:整数值不带 .0(对齐 Pilot 2 真机 KMZ 风格);非整数保留小数位。 */
    private static String formatNumeric(Number value) {
        double d = value.doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private void elem(XMLStreamWriter w, String ns, String name, String value) throws XMLStreamException {
        w.writeStartElement(ns, name);
        w.writeCharacters(value);
        w.writeEndElement();
    }

    @FunctionalInterface
    private interface KmlBody {
        void write(XMLStreamWriter w) throws XMLStreamException;
    }

    private byte[] writeKml(KmlBody body) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(bos, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("kml");
            w.writeDefaultNamespace(NS_KML);
            w.writeNamespace("wpml", NS_WPML);
            w.writeStartElement("Document");
            body.write(w);
            w.writeEndElement(); // /Document
            w.writeEndElement(); // /kml
            w.writeEndDocument();
            w.flush();
            return bos.toByteArray();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to write WPML KMZ XML.", e);
        }
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
                .finishAction(param.getFinishAction())
                .exitOnRcLost(param.getExitOnRcLost())
                .rcLostAction(param.getRcLostAction())
                .takeoffSecurityHeight(param.getTakeoffSecurityHeight())
                .globalTransitionalSpeed(param.getGlobalTransitionalSpeed())
                .rthAltitude(param.getRthAltitude())
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
                .finishAction(dto.getFinishAction())
                .exitOnRcLost(dto.getExitOnRcLost())
                .rcLostAction(dto.getRcLostAction())
                .takeoffSecurityHeight(dto.getTakeoffSecurityHeight())
                .globalTransitionalSpeed(dto.getGlobalTransitionalSpeed())
                .rthAltitude(dto.getRthAltitude())
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
                .waylineMissionState(dto.getWaylineMissionState())
                .currentWaypointIndex(dto.getCurrentWaypointIndex())
                .totalWaypoints(dto.getTotalWaypoints())
                .mediaCount(dto.getMediaCount())
                .breakPointJson(dto.getBreakPointJson())
                .lastProgressTime(dto.getLastProgressTime())
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
                .finishAction(param.getFinishAction())
                .exitOnRcLost(param.getExitOnRcLost())
                .rcLostAction(param.getRcLostAction())
                .takeoffSecurityHeight(param.getTakeoffSecurityHeight())
                .globalTransitionalSpeed(param.getGlobalTransitionalSpeed())
                .rthAltitude(param.getRthAltitude())
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
                .finishAction(entity.getFinishAction())
                .exitOnRcLost(entity.getExitOnRcLost())
                .rcLostAction(entity.getRcLostAction())
                .takeoffSecurityHeight(entity.getTakeoffSecurityHeight())
                .globalTransitionalSpeed(entity.getGlobalTransitionalSpeed())
                .rthAltitude(entity.getRthAltitude())
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
                .waylineMissionState(entity.getWaylineMissionState())
                .currentWaypointIndex(entity.getCurrentWaypointIndex())
                .totalWaypoints(entity.getTotalWaypoints())
                .mediaCount(entity.getMediaCount())
                .breakPointJson(entity.getBreakPointJson())
                .lastProgressTime(entity.getLastProgressTime())
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
