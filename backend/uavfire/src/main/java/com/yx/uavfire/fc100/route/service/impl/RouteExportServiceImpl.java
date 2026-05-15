package com.yx.uavfire.fc100.route.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.route.builder.Fc100KmzPackager;
import com.yx.uavfire.fc100.route.builder.Fc100WpmlBuilder;
import com.yx.uavfire.fc100.route.builder.WpmlBuildContext;
import com.yx.uavfire.fc100.route.config.Fc100RouteProperties;
import com.yx.uavfire.fc100.route.dao.RouteFileMapper;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.model.entity.RouteFileEntity;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import com.yx.uavfire.fc100.route.storage.MinioRouteStorage;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.service.WaypointPlannerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class RouteExportServiceImpl implements RouteExportService {

    public static final String GENERATOR_VERSION = "fc100-wpml-builder/0.1.0";

    private final FireMissionMapper missionMapper;
    private final WaypointPlannerService planner;
    private final Fc100WpmlBuilder builder;
    private final Fc100KmzPackager packager;
    private final RouteFileMapper fileMapper;
    private final MissionStateMachine sm;
    private final Clock clock;
    private final Fc100RouteProperties routeProps;
    private final Optional<MinioRouteStorage> minioStorage;

    public RouteExportServiceImpl(FireMissionMapper m,
                                   WaypointPlannerService p,
                                   Fc100WpmlBuilder b,
                                   Fc100KmzPackager pk,
                                   RouteFileMapper f,
                                   MissionStateMachine sm,
                                   Clock c,
                                   Fc100RouteProperties r,
                                   Optional<MinioRouteStorage> minioStorage) {
        this.missionMapper = m;
        this.planner = p;
        this.builder = b;
        this.packager = pk;
        this.fileMapper = f;
        this.sm = sm;
        this.clock = c;
        this.routeProps = r;
        this.minioStorage = minioStorage;
    }

    @Override
    @Transactional
    public RouteFileDTO exportKmz(String missionNo, String operatorId, String clientIp, String requestId) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", missionNo).eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, missionNo);
        }
        List<MissionWaypointDTO> waypoints = planner.listLatest(m.getId());
        if (waypoints.isEmpty()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "no waypoints generated for mission " + missionNo);
        }

        WpmlBuildContext ctx = WpmlBuildContext.builder()
            .missionNo(missionNo)
            .aircraftSn(m.getAircraftSn())
            .takeOffLat(m.getTakeoffLat())
            .takeOffLng(m.getTakeoffLng())
            .takeOffAlt(m.getTakeoffAlt())
            .waypoints(waypoints)
            .createTimeMs(clock.now())
            .build();

        byte[] template = builder.buildTemplateKml(ctx);
        byte[] waylines = builder.buildWaylinesWpml(ctx);
        var kmz = packager.pack(template, waylines);

        long now = clock.now();
        String objectKey = "fc100-routes/" + missionNo + "-" + now + ".kmz";

        // 写本地文件（routeProps.fileStorage=local）
        if ("local".equals(routeProps.getFileStorage())) {
            try {
                Path dir = Paths.get(routeProps.getLocalDir());
                Files.createDirectories(dir);
                Path file = dir.resolve(missionNo + "-" + now + ".kmz");
                Files.write(file, kmz.getBytes());
                objectKey = file.toString();
                log.info("KMZ saved locally size={} sha256={} path={}",
                    kmz.getBytes().length, kmz.getSha256(), file);
            } catch (IOException e) {
                throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, e.getMessage());
            }
        } else if ("minio".equals(routeProps.getFileStorage())) {
            MinioRouteStorage storage = minioStorage.orElseThrow(() ->
                new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, "MinioRouteStorage not initialized"));
            storage.upload(objectKey, kmz.getBytes(), "application/zip");
            log.info("KMZ uploaded to MinIO size={} sha256={} objectKey={}",
                kmz.getBytes().length, kmz.getSha256(), objectKey);
        }

        // 旧 latest 改 0
        fileMapper.update(null, new UpdateWrapper<RouteFileEntity>()
            .eq("mission_id", m.getId()).set("is_latest", 0));

        RouteFileEntity e = new RouteFileEntity();
        e.setMissionId(m.getId());
        e.setFileType("KMZ");
        e.setSchemaVersion("WPML_" + routeProps.getWpmlVersion());
        e.setFileName(missionNo + ".kmz");
        e.setObjectKey(objectKey);
        e.setSign(kmz.getSha256());
        e.setSize((long) kmz.getBytes().length);
        e.setGeneratorVersion(GENERATOR_VERSION);
        e.setIsLatest(1);
        e.setCreatedBy(operatorId);
        e.setCreateTime(now);
        e.setUpdateTime(now);
        fileMapper.insert(e);

        sm.transit(TransitCommand.builder()
            .missionNo(missionNo)
            .event(FireMissionEvent.EXP_KMZ)
            .operatorId(operatorId)
            .clientIp(clientIp)
            .requestId(requestId)
            .build());

        missionMapper.update(null, new UpdateWrapper<FireMissionEntity>()
            .eq("id", m.getId()).set("latest_route_file_id", e.getId()));

        return toDto(e, missionNo);
    }

    @Override
    public RouteFileDTO getLatest(String missionNo) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", missionNo).eq("deleted", 0));
        if (m == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, missionNo);
        }
        RouteFileEntity f = fileMapper.selectOne(new QueryWrapper<RouteFileEntity>()
            .eq("mission_id", m.getId()).eq("is_latest", 1).orderByDesc("create_time")
            .last("LIMIT 1"));
        return f == null ? null : toDto(f, missionNo);
    }

    @Override
    public byte[] downloadById(Long fileId) {
        RouteFileEntity f = fileMapper.selectById(fileId);
        if (f == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, "file " + fileId);
        }
        if ("local".equals(routeProps.getFileStorage())) {
            try {
                return Files.readAllBytes(Paths.get(f.getObjectKey()));
            } catch (IOException e) {
                throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, e.getMessage());
            }
        } else if ("minio".equals(routeProps.getFileStorage())) {
            MinioRouteStorage storage = minioStorage.orElseThrow(() ->
                new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, "MinioRouteStorage not initialized"));
            try (InputStream is = storage.downloadStream(f.getObjectKey())) {
                return is.readAllBytes();
            } catch (IOException e) {
                throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, e.getMessage());
            }
        }
        throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR,
            "unsupported storage mode: " + routeProps.getFileStorage());
    }

    @Override
    public RouteExportService.DownloadUrlResult getDownloadUrl(Long fileId, String contextPath, String missionNo) {
        RouteFileEntity f = fileMapper.selectById(fileId);
        if (f == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, "file " + fileId);
        }
        if ("minio".equals(routeProps.getFileStorage())) {
            MinioRouteStorage storage = minioStorage.orElseThrow(() ->
                new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, "MinioRouteStorage not initialized"));
            String url = storage.presignedDownloadUrl(f.getObjectKey(), 900);
            return new RouteExportService.DownloadUrlResult(url, 900);
        }
        // local mode: relative URL, never expires
        String url = contextPath + "/api/fire/missions/" + missionNo + "/route/files/" + fileId + "/download";
        return new RouteExportService.DownloadUrlResult(url, -1);
    }

    private RouteFileDTO toDto(RouteFileEntity e, String missionNo) {
        RouteFileDTO d = new RouteFileDTO();
        BeanUtils.copyProperties(e, d);
        d.setMissionNo(missionNo);
        return d;
    }
}
