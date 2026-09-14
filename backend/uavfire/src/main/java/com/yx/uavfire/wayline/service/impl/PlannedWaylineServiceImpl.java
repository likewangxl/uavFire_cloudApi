package com.yx.uavfire.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.component.oss.model.OssConfiguration;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import com.yx.uavfire.wayline.dao.IPlannedWaylineMapper;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineCreateDTO;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineFileDTO;
import com.yx.uavfire.wayline.model.dto.PlannedAreaVertexDTO;
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
import org.springframework.util.DigestUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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

    /**
     * 巡检航点默认必须真实到点。平滑通过会按 waypointTurnDampingDist 提前切弯，
     * 只能由用户在单个航点上显式选择，不能作为未配置时的隐式默认值。
     */
    private static final String STRICT_WAYPOINT_TURN_MODE =
            "toPointAndStopWithDiscontinuityCurvature";

    private static final String ROUTE_KIND_WAYPOINT = "waypoint";
    private static final String ROUTE_KIND_PATROL = "patrol";
    private static final String ROUTE_KIND_AREA = "area";
    private static final String DEFAULT_AREA_CAMERA_KEY = "H20T";
    private static final int DEFAULT_AREA_FRONT_OVERLAP = 80;
    private static final int DEFAULT_AREA_SIDE_OVERLAP = 70;
    private static final long AGENT_COMMAND_POLL_MAX_AGE_MS = 15_000L;

    private final IPlannedWaylineMapper mapper;

    private final ObjectMapper objectMapper;

    private final IWaylineFileService waylineFileService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yx.uavfire.wayline.agent.service.IWaylineAgentService waylineAgentService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MsdkDeviceStateService msdkDeviceStateService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SDKWaylineService sdkWaylineService;

    @org.springframework.beans.factory.annotation.Value("${wayline-agent.server-url:http://localhost:6789}")
    private String waylineAgentServerUrl;

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
        param.setAircraftModelKey(normalizeAircraftModelKey(param.getAircraftModelKey()));
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
    public PlannedWaylineDTO importKmzFile(String workspaceId, String username, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("KMZ file is required.");
        }

        String plannedWaylineId = UUID.randomUUID().toString();
        String filename = normalizeImportedKmzFilename(file.getOriginalFilename(), plannedWaylineId);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read KMZ file.", e);
        }

        PublishedWaylineFileDTO publishedWayline = waylineFileService.createPublishedWayline(
                workspaceId,
                PublishedWaylineCreateDTO.builder()
                        .filename(filename)
                        .objectKey(buildImportedKmzObjectKey(plannedWaylineId))
                        .username(username)
                        .content(content)
                        .build());

        com.dji.sdk.cloudapi.wayline.GetWaylineListResponse importedFile = waylineFileService
                .getWaylineByWaylineId(workspaceId, publishedWayline.getWaylineId())
                .orElseThrow(() -> new IllegalStateException("Imported KMZ metadata was not found."));

        String kmzUrl;
        try {
            kmzUrl = waylineFileService.getObjectUrl(workspaceId, publishedWayline.getWaylineId()).toString();
        } catch (SQLException e) {
            rollbackPublishedWayline(workspaceId, publishedWayline.getWaylineId());
            throw new IllegalStateException("Failed to get imported KMZ file URL.", e);
        }

        ImportedKmzPlan importedPlan = extractImportedKmzPlan(content);
        long now = System.currentTimeMillis();
        PlannedWaylineEntity entity = PlannedWaylineEntity.builder()
                .plannedWaylineId(plannedWaylineId)
                .workspaceId(workspaceId)
                .name(sanitizeDjiWaylineName(importedFile.getName(), plannedWaylineId))
                .aircraftModelKey(importedFile.getDroneModelKey() != null
                        ? importedFile.getDroneModelKey().name()
                        : "M30T")
                .gatewaySn("")
                .aircraftSn("")
                .defaultHeight(importedPlan.defaultHeight)
                .maxSpeed(importedPlan.maxSpeed)
                .waypointsJson(writeWaypoints(importedPlan.waypoints))
                .status(STATUS_FILE_GENERATED)
                .publishedWaylineId(publishedWayline.getWaylineId())
                .kmzUrl(kmzUrl)
                .kmzMd5(importedFile.getSign())
                .kmzObjectKey(importedFile.getObjectKey())
                .fileGeneratedTime(now)
                .taskStatus(STATUS_FILE_GENERATED)
                .creator(username)
                .publisher(username)
                .publishTime(now)
                .createTime(now)
                .updateTime(now)
                .build();

        int inserted;
        try {
            inserted = mapper.insert(entity);
        } catch (RuntimeException e) {
            rollbackPublishedWayline(workspaceId, publishedWayline.getWaylineId());
            throw e;
        }
        if (inserted <= 0) {
            rollbackPublishedWayline(workspaceId, publishedWayline.getWaylineId());
            throw new IllegalArgumentException("Failed to import KMZ as planned wayline.");
        }
        return entity2Dto(entity);
    }

    private ImportedKmzPlan extractImportedKmzPlan(byte[] content) {
        String xml = readKmzXmlEntry(content, "wpmz/waylines.wpml")
                .or(() -> readKmzXmlEntry(content, "wpmz/template.kml"))
                .orElse("");
        double maxSpeed = parsePositiveDouble(tagText(xml, "autoFlightSpeed")).orElse(5.0);
        List<PlannedWaypointDTO> waypoints = parseImportedKmzWaypoints(xml, 30.0, maxSpeed);
        double defaultHeight = waypoints.stream()
                .map(PlannedWaypointDTO::getHeight)
                .filter(value -> value != null && value > 0)
                .findFirst()
                .orElse(30.0);
        return new ImportedKmzPlan(waypoints, defaultHeight, maxSpeed);
    }

    private Optional<String> readKmzXmlEntry(byte[] content, String entryName) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (entryName.equals(entry.getName())) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    zipInputStream.transferTo(outputStream);
                    return Optional.of(outputStream.toString(StandardCharsets.UTF_8));
                }
                entry = zipInputStream.getNextEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect imported KMZ.", e);
        }
        return Optional.empty();
    }

    private List<PlannedWaypointDTO> parseImportedKmzWaypoints(String xml, double fallbackHeight, double fallbackSpeed) {
        List<PlannedWaypointDTO> waypoints = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?s)<Placemark\\b[^>]*>(.*?)</Placemark>").matcher(xml);
        int sequence = 1;
        while (matcher.find()) {
            String body = matcher.group(1);
            Matcher coordinateMatcher = Pattern.compile("(?s)<coordinates>\\s*([-+0-9.Ee]+),([-+0-9.Ee]+)(?:,[^<]*)?\\s*</coordinates>")
                    .matcher(body);
            if (!coordinateMatcher.find()) {
                continue;
            }
            try {
                double wgsLng = Double.parseDouble(coordinateMatcher.group(1));
                double wgsLat = Double.parseDouble(coordinateMatcher.group(2));
                double[] gcj = wgs84ToGcj02(wgsLng, wgsLat);
                double height = parsePositiveDouble(tagText(body, "executeHeight"))
                        .or(() -> parsePositiveDouble(tagText(body, "height")))
                        .orElse(fallbackHeight);
                double speed = parsePositiveDouble(tagText(body, "waypointSpeed")).orElse(fallbackSpeed);
                int order = parsePositiveInt(tagText(body, "index")).map(index -> index + 1).orElse(sequence);
                PlannedWaypointDTO waypoint = new PlannedWaypointDTO()
                        .setOrder(order)
                        .setWgsLng(wgsLng)
                        .setWgsLat(wgsLat)
                        .setGcjLng(gcj[0])
                        .setGcjLat(gcj[1])
                        .setHeight(height)
                        .setSpeed(speed);
                tagText(body, "waypointHeadingMode").ifPresent(waypoint::setHeadingMode);
                parseFiniteDouble(tagText(body, "waypointHeadingAngle")).ifPresent(waypoint::setHeadingAngle);
                tagText(body, "waypointTurnMode").ifPresent(waypoint::setTurnMode);
                parseFiniteDouble(tagText(body, "waypointTurnDampingDist")).ifPresent(waypoint::setTurnDamping);
                parseFiniteDouble(tagText(body, "waypointGimbalPitchAngle")).ifPresent(waypoint::setGimbalPitch);
                parseFiniteDouble(tagText(body, "waypointGimbalYawAngle")).ifPresent(waypoint::setGimbalYaw);
                waypoints.add(waypoint);
                sequence++;
            } catch (NumberFormatException ignored) {
                // Skip malformed points; the original KMZ remains stored for download.
            }
        }
        return waypoints;
    }

    private Optional<String> tagText(String xml, String localName) {
        Matcher matcher = Pattern.compile("(?s)<(?:[A-Za-z0-9_]+:)?" + Pattern.quote(localName)
                + ">\\s*([^<]+?)\\s*</(?:[A-Za-z0-9_]+:)?" + Pattern.quote(localName) + ">")
                .matcher(xml);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    private Optional<Integer> parsePositiveInt(Optional<String> value) {
        return value.flatMap(text -> {
            try {
                int parsed = Integer.parseInt(text);
                return parsed >= 0 ? Optional.of(parsed) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private Optional<Double> parsePositiveDouble(Optional<String> value) {
        return parseFiniteDouble(value).filter(parsed -> parsed > 0);
    }

    private Optional<Double> parseFiniteDouble(Optional<String> value) {
        return value.flatMap(text -> {
            try {
                double parsed = Double.parseDouble(text);
                return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dlat = transformLat(lng - 105.0, lat - 35.0);
        double dlng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dlat = (dlat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * Math.PI);
        dlng = (dlng * 180.0) / (6378245.0 / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dlng, lat + dlat};
    }

    private boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private double transformLat(double lng, double lat) {
        double ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * Math.PI) + 20.0 * Math.sin(2.0 * lng * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lat * Math.PI) + 40.0 * Math.sin(lat / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(lat / 12.0 * Math.PI) + 320 * Math.sin(lat * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private double transformLng(double lng, double lat) {
        double ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * Math.PI) + 20.0 * Math.sin(2.0 * lng * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lng * Math.PI) + 40.0 * Math.sin(lng / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(lng / 12.0 * Math.PI) + 300.0 * Math.sin(lng / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private static class ImportedKmzPlan {
        private final List<PlannedWaypointDTO> waypoints;
        private final double defaultHeight;
        private final double maxSpeed;

        private ImportedKmzPlan(List<PlannedWaypointDTO> waypoints, double defaultHeight, double maxSpeed) {
            this.waypoints = waypoints;
            this.defaultHeight = defaultHeight;
            this.maxSpeed = maxSpeed;
        }
    }

    @Override
    public PlannedWaylineDTO update(String workspaceId, String id, UpdatePlannedWaylineParam param) {
        param.setAircraftModelKey(normalizeAircraftModelKey(param.getAircraftModelKey()));
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
        validateParam(param);

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
        // 历史面状航线及未显式设置转弯方式的航点航线可能仍引用旧 KMZ。
        // “再次执行”必须先重生当前安全格式，不能仅因已有 publishedWaylineId 就复用。
        if ((isAreaRoute(existing) || requiredStrictWaypointCount(existing) > 0)
                && !isGeneratedWaylineSafe(workspaceId, existing)) {
            generateFile(workspaceId, id, username);
            existing = getExisting(workspaceId, id);
        }

        long now = System.currentTimeMillis();
        resetExecutionRuntimeState(existing);
        existing.setStatus(STATUS_PUBLISHING);
        existing.setTaskStatus(STATUS_PUBLISHING);
        existing.setTaskStatusReason(null);
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
        clearNullableExecutionRuntimeFields(existing);
        return entity2Dto(existing);
    }

    private void resetExecutionRuntimeState(PlannedWaylineEntity existing) {
        existing.setTaskProgress(0);
        existing.setWaylineMissionState(null);
        existing.setCurrentWaypointIndex(null);
        existing.setTotalWaypoints(null);
        existing.setMediaCount(0);
        existing.setBreakPointJson(null);
        existing.setLastProgressTime(null);
        existing.setExecutedTime(null);
    }

    private void clearNullableExecutionRuntimeFields(PlannedWaylineEntity existing) {
        mapper.update(null, new LambdaUpdateWrapper<PlannedWaylineEntity>()
                .eq(PlannedWaylineEntity::getId, existing.getId())
                .set(PlannedWaylineEntity::getWaylineMissionState, null)
                .set(PlannedWaylineEntity::getCurrentWaypointIndex, null)
                .set(PlannedWaylineEntity::getTotalWaypoints, null)
                .set(PlannedWaylineEntity::getBreakPointJson, null)
                .set(PlannedWaylineEntity::getLastProgressTime, null)
                .set(PlannedWaylineEntity::getExecutedTime, null));
    }

    @Override
    public PlannedWaylineDTO executeTask(String workspaceId, String id) {
        return executeTask(workspaceId, id, null);
    }

    @Override
    public PlannedWaylineDTO executeTask(String workspaceId, String id, PreparePlannedWaylineTaskParam param) {
        PlannedWaylineEntity existing = getExisting(workspaceId, id);
        if (!StringUtils.hasText(existing.getFlightId())) {
            throw new IllegalArgumentException("Prepare the planned wayline task before executing it.");
        }
        applyExecutionTarget(existing, param);
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

    private void applyExecutionTarget(PlannedWaylineEntity existing, PreparePlannedWaylineTaskParam param) {
        if (param == null) {
            return;
        }
        if (StringUtils.hasText(param.getDroneSn())) {
            existing.setDroneSn(param.getDroneSn());
            existing.setAircraftSn(param.getDroneSn());
        }
        if (StringUtils.hasText(param.getDockSn())) {
            existing.setDockSn(param.getDockSn());
        }
    }

    private String waylineDroneSn(PlannedWaylineEntity existing) {
        if (StringUtils.hasText(existing.getDroneSn())) return existing.getDroneSn();
        if (StringUtils.hasText(existing.getAircraftSn())) return existing.getAircraftSn();
        return null;
    }

    private String resolveAgentAircraftTarget(PlannedWaylineEntity existing) {
        String droneSn = waylineDroneSn(existing);
        if (StringUtils.hasText(droneSn)) {
            return droneSn;
        }
        if (msdkDeviceStateService == null) {
            return null;
        }
        List<MsdkDeviceStateDTO> onlineAircrafts = msdkDeviceStateService.listOnline().stream()
                .filter(state -> state != null && StringUtils.hasText(state.getAircraftSn()))
                .collect(Collectors.toList());
        if (onlineAircrafts.size() != 1) {
            return null;
        }
        String aircraftSn = onlineAircrafts.get(0).getAircraftSn();
        existing.setDroneSn(aircraftSn);
        existing.setAircraftSn(aircraftSn);
        return aircraftSn;
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
        existing.setTaskStatusReason(null);
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
            throw new IllegalStateException("KMZ 地址缺失，请重新生成航线文件。");
        }
        String droneSn = resolveAgentAircraftTarget(entity);
        if (!StringUtils.hasText(droneSn)) {
            throw new IllegalStateException("执行航线前需要选择在线飞行器。");
        }

        validateAgentPayloadMatch(entity, droneSn);

        // MSDK 状态上报在线不等于航线执行器在线。旧版/卡住的遥控器 App 仍会持续
        // 上报位置，却不会轮询 WAYLINE_DISPATCH；过去这里仍把任务标成 executing。
        // 必须看到本后端实例上的近期航线取令心跳，才允许真正下发。
        if (!waylineAgentService.hasRecentCommandPoll(droneSn, AGENT_COMMAND_POLL_MAX_AGE_MS)) {
            throw new IllegalStateException(
                    "遥控器航线执行服务未连接，请安装并启动最新 Agent 后重试。");
        }

        // Load KMZ into memory so the agent can download it via the HTTP KMZ endpoint.
        byte[] kmzBytes;
        try {
            try (InputStream is = new URL(entity.getKmzUrl()).openStream()) {
                kmzBytes = is.readAllBytes();
            }
            kmzBytes = normalizeAgentRuntimeKmz(kmzBytes);
            waylineAgentService.prepareKmz(droneSn, entity.getFlightId(), kmzBytes);
        } catch (Exception e) {
            throw new IllegalStateException("缓存 KMZ 失败，无法下发航线。", e);
        }

        String httpKmzUrl = waylineAgentServerUrl + "/wayline-agent/api/v1/agents/" + droneSn
                + "/missions/" + entity.getFlightId() + "/kmz";
        com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO data =
                new com.yx.uavfire.wayline.agent.model.dto.WaylineDispatchDataDTO()
                        .setMissionId(entity.getFlightId())
                        .setKmzUrl(httpKmzUrl)
                        .setKmzFilename(entity.getFlightId() + ".kmz")
                        .setKmzMd5(DigestUtils.md5DigestAsHex(kmzBytes));
        waylineAgentService.dispatchWayline(droneSn, data);
        log.info("Dispatched wayline to agent {} flight {} kmzUrl={}", droneSn, entity.getFlightId(), httpKmzUrl);
    }

    private void validateAgentPayloadMatch(PlannedWaylineEntity entity, String droneSn) {
        String plannedModel = normalizeAircraftModelKey(entity.getAircraftModelKey());
        if (!usesZenmusePayloadSelection(plannedModel)) {
            return;
        }
        if (msdkDeviceStateService == null) {
            throw new IllegalStateException("Aircraft Agent state service is unavailable; dispatch blocked.");
        }
        MsdkDeviceStateDTO state = msdkDeviceStateService.listOnline().stream()
                .filter(item -> droneSn.equals(item.getAircraftSn()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Aircraft Agent is offline or stale; dispatch blocked."));
        String onlineModel = StringUtils.hasText(state.getAircraftModelKey())
                ? state.getAircraftModelKey() : state.getModel();
        if (!Objects.equals(plannedModel, normalizeAircraftModelKey(onlineModel))) {
            throw new IllegalStateException("Online aircraft model does not match the wayline; dispatch blocked.");
        }
        if (state.getSelectedPayloadPositionIndex() == null
                || !state.getSelectedPayloadPositionIndex().equals(entity.getPayloadPositionIndex())) {
            throw new IllegalStateException("Online payload position does not match the wayline; dispatch blocked.");
        }
        String selectedPayload = state.getPayloads() == null ? null : state.getPayloads().stream()
                .filter(payload -> state.getSelectedPayloadPositionIndex().equals(payload.getPayloadPositionIndex()))
                .map(com.yx.uavfire.msdk.model.PayloadCapabilityDTO::getPayloadModelKey)
                .findFirst()
                .orElse(null);
        if (!Objects.equals(
                normalizePayloadModelKey(entity.getPayloadModelKey()),
                normalizePayloadModelKey(selectedPayload))) {
            throw new IllegalStateException("Online payload model does not match the wayline; dispatch blocked.");
        }
        if (!Boolean.TRUE.equals(state.getWaylineCommandSupported())) {
            String version = StringUtils.hasText(state.getAgentVersionName())
                    ? "（当前 " + state.getAgentVersionName() + "）"
                    : "";
            throw new IllegalStateException(
                    "遥控器 Agent" + version + "不支持当前航线下发，请安装 0.1.17 或更高版本。");
        }
    }

    private byte[] normalizeAgentRuntimeKmz(byte[] kmzBytes) throws IOException {
        boolean changed = false;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(kmzBytes), StandardCharsets.UTF_8);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (entry.isDirectory()) {
                    zipOutputStream.putNextEntry(new ZipEntry(entry.getName()));
                    zipOutputStream.closeEntry();
                    entry = zipInputStream.getNextEntry();
                    continue;
                }
                ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                zipInputStream.transferTo(entryBytes);
                byte[] content = entryBytes.toByteArray();
                if ("wpmz/template.kml".equals(entry.getName()) || "wpmz/waylines.wpml".equals(entry.getName())) {
                    String xml = entryBytes.toString(StandardCharsets.UTF_8);
                    String normalized = normalizeM4tRuntimeWpml(xml);
                    if (!normalized.equals(xml)) {
                        changed = true;
                        content = normalized.getBytes(StandardCharsets.UTF_8);
                    }
                }
                zipOutputStream.putNextEntry(new ZipEntry(entry.getName()));
                zipOutputStream.write(content);
                zipOutputStream.closeEntry();
                entry = zipInputStream.getNextEntry();
            }
        }
        return changed ? outputStream.toByteArray() : kmzBytes;
    }

    private String normalizeM4tRuntimeWpml(String xml) {
        boolean m4tLike = xml.contains("<wpml:droneEnumValue>99</wpml:droneEnumValue>")
                && xml.contains("<wpml:droneSubEnumValue>1</wpml:droneSubEnumValue>")
                && xml.contains("<wpml:payloadEnumValue>89</wpml:payloadEnumValue>");
        if (!m4tLike) {
            return xml;
        }
        String normalized = xml;
        normalized = normalized.replace("<wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>",
                "<wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>");
        normalized = normalized.replaceAll("<wpml:globalTransitionalSpeed>[^<]+</wpml:globalTransitionalSpeed>",
                "<wpml:globalTransitionalSpeed>5</wpml:globalTransitionalSpeed>");
        normalized = normalized.replaceAll("(?s)\\s*<wpml:payloadParam>.*?</wpml:payloadParam>", "");
        normalized = normalized.replaceAll("(?s)\\s*<wpml:realTimeFollowSurfaceByFov>.*?</wpml:realTimeFollowSurfaceByFov>", "");
        normalized = normalized.replaceAll("<wpml:globalWaypointTurnMode>[^<]+</wpml:globalWaypointTurnMode>",
                "<wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode>");
        normalized = normalized.replaceAll("(?s)\\s*<wpml:useGlobalHeight>.*?</wpml:useGlobalHeight>", "");
        normalized = normalized.replaceAll("(?s)\\s*<wpml:useGlobalTurnParam>.*?</wpml:useGlobalTurnParam>", "");
        normalized = addM4tTemplateTurnParams(normalized);
        // 保留航线原本的转弯语义。旧逻辑在 Agent 下发前会把 DJI Pilot 导出的
        // “到点停”强制改成“连续曲率通过”，并注入正的 damping，导致飞行器提前切弯。
        // 严格过点必须保持 stop + damping=0；显式选择平滑通过的航点则不做改写。
        normalized = normalized.replaceAll(
                "<wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>\\s*"
                        + "<wpml:waypointTurnDampingDist>[^<]+</wpml:waypointTurnDampingDist>",
                "<wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>"
                        + "<wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>");
        return normalized;
    }

    private String addM4tTemplateTurnParams(String xml) {
        Matcher matcher = Pattern.compile("(?s)<Placemark>(.*?)</Placemark>").matcher(xml);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String placemarkBody = matcher.group(1);
            if (!placemarkBody.contains("<wpml:waypointTurnParam>")
                    && placemarkBody.contains("</wpml:height>")) {
                String turnParam = "<wpml:waypointTurnParam>"
                        + "<wpml:waypointTurnMode>" + STRICT_WAYPOINT_TURN_MODE + "</wpml:waypointTurnMode>"
                        + "<wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>"
                        + "</wpml:waypointTurnParam>";
                String replacement = "<Placemark>"
                        + placemarkBody.replaceFirst("</wpml:height>", "</wpml:height>" + turnParam)
                        + "</Placemark>";
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private void invokeAgentControl(PlannedWaylineEntity entity, ControlOp op) {
        if (waylineAgentService == null) {
            throw new IllegalStateException("Agent service unavailable; cannot route control command.");
        }
        String droneSn = resolveAgentAircraftTarget(entity);
        if (!StringUtils.hasText(droneSn)) {
            throw new IllegalStateException("控制航线前需要选择在线飞行器。");
        }
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
                .aircraftSn(existing.getAircraftSn())
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
        if (existing.getTaskStatusReason() == null) {
            mapper.update(null, new LambdaUpdateWrapper<PlannedWaylineEntity>()
                    .eq(PlannedWaylineEntity::getId, existing.getId())
                    .set(PlannedWaylineEntity::getTaskStatusReason, null));
        }
        if (updated <= 0) {
            throw new IllegalArgumentException("Planned wayline doesn't exist.");
        }
    }

    private void validateParam(CreatePlannedWaylineParam param) {
        if (Objects.isNull(param)) {
            throw new IllegalArgumentException("Planned wayline param is required.");
        }
        validateEditableFields(param.getName(), param.getAircraftModelKey(), param.getGatewaySn(),
                param.getAircraftSn(), param.getPayloadModelKey(), param.getPayloadPositionIndex(),
                param.getDefaultHeight(), param.getMaxSpeed(), param.getWaypoints(),
                param.getRouteKind(), param.getAreaPolygon(), param.getAreaFrontOverlap(),
                param.getAreaSideOverlap(), param.getAreaHeadingDeg());
    }

    private void validateParam(UpdatePlannedWaylineParam param) {
        if (Objects.isNull(param)) {
            throw new IllegalArgumentException("Planned wayline param is required.");
        }
        validateEditableFields(param.getName(), param.getAircraftModelKey(), param.getGatewaySn(),
                param.getAircraftSn(), param.getPayloadModelKey(), param.getPayloadPositionIndex(),
                param.getDefaultHeight(), param.getMaxSpeed(), param.getWaypoints(),
                param.getRouteKind(), param.getAreaPolygon(), param.getAreaFrontOverlap(),
                param.getAreaSideOverlap(), param.getAreaHeadingDeg());
    }

    private void validateEditableFields(String name,
                                        String aircraftModelKey,
                                        String gatewaySn,
                                        String aircraftSn,
                                        String payloadModelKey,
                                        Integer payloadPositionIndex,
                                        Double defaultHeight,
                                        Double maxSpeed,
                                        List<PlannedWaypointDTO> waypoints,
                                        String routeKind,
                                        List<PlannedAreaVertexDTO> areaPolygon,
                                        Integer areaFrontOverlap,
                                        Integer areaSideOverlap,
                                        Double areaHeadingDeg) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Planned wayline name is required.");
        }
        if (!StringUtils.hasText(aircraftModelKey)) {
            throw new IllegalArgumentException("Planned wayline aircraft model key is required.");
        }
        if (usesZenmusePayloadSelection(aircraftModelKey)) {
            if (!isSupportedZenmusePayload(payloadModelKey)) {
                throw new IllegalArgumentException("M300/M350 planned wayline requires payload model H20, H20T, H30, or H30T.");
            }
            if (payloadPositionIndex == null || payloadPositionIndex < 0 || payloadPositionIndex > 2) {
                throw new IllegalArgumentException("M300/M350 planned wayline requires payload position index 0, 1, or 2.");
            }
        }
        if (!isFinite(defaultHeight)) {
            throw new IllegalArgumentException("Planned wayline default height is required.");
        }
        if (!isFinite(maxSpeed)) {
            throw new IllegalArgumentException("Planned wayline max speed is required.");
        }
        validateAreaFields(routeKind, areaPolygon, areaFrontOverlap, areaSideOverlap, areaHeadingDeg);
        validateWaypoints(waypoints);
    }

    private void validateAreaFields(String routeKind,
                                    List<PlannedAreaVertexDTO> areaPolygon,
                                    Integer areaFrontOverlap,
                                    Integer areaSideOverlap,
                                    Double areaHeadingDeg) {
        String normalizedKind = normalizeRouteKind(routeKind);
        if (!ROUTE_KIND_AREA.equals(normalizedKind)) {
            return;
        }
        if (areaPolygon == null || areaPolygon.size() < 3) {
            throw new IllegalArgumentException("Area planned wayline requires at least 3 polygon vertices.");
        }
        for (int i = 0; i < areaPolygon.size(); i++) {
            PlannedAreaVertexDTO vertex = areaPolygon.get(i);
            if (vertex == null
                    || !isLegalLongitude(vertex.getGcjLng())
                    || !isLegalLatitude(vertex.getGcjLat())
                    || !isLegalLongitude(vertex.getWgsLng())
                    || !isLegalLatitude(vertex.getWgsLat())) {
                throw new IllegalArgumentException("Area polygon vertex[" + i + "] coordinates are invalid.");
            }
        }
        validateOverlap("front", areaFrontOverlap);
        validateOverlap("side", areaSideOverlap);
        if (areaHeadingDeg != null && (!Double.isFinite(areaHeadingDeg)
                || areaHeadingDeg < 0 || areaHeadingDeg >= 360)) {
            throw new IllegalArgumentException("Area heading must be in [0, 360).");
        }
    }

    private void validateOverlap(String label, Integer value) {
        if (value != null && (value < 0 || value > 95)) {
            throw new IllegalArgumentException("Area " + label + " overlap must be in [0, 95].");
        }
    }

    private String normalizeRouteKind(String raw) {
        String normalized = StringUtils.hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : ROUTE_KIND_WAYPOINT;
        if (!ROUTE_KIND_WAYPOINT.equals(normalized)
                && !ROUTE_KIND_PATROL.equals(normalized)
                && !ROUTE_KIND_AREA.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported planned wayline route kind: " + raw);
        }
        return normalized;
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

    private String normalizeAircraftModelKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "_");
        if ("M300RTK".equals(normalized) || "MATRICE300RTK".equals(normalized)
                || "MATRICE_300_RTK".equals(normalized)) {
            return "M300";
        }
        if ("M350RTK".equals(normalized) || "MATRICE350RTK".equals(normalized)
                || "MATRICE_350_RTK".equals(normalized)) {
            return "M350";
        }
        return normalized;
    }

    private String normalizePayloadModelKey(String raw) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean isSupportedZenmusePayload(String payloadModelKey) {
        String normalized = normalizePayloadModelKey(payloadModelKey);
        return "H20".equals(normalized) || "H20T".equals(normalized)
                || "H30".equals(normalized) || "H30T".equals(normalized);
    }

    private boolean usesZenmusePayloadSelection(String aircraftModelKey) {
        String normalized = normalizeAircraftModelKey(aircraftModelKey);
        return "M300".equals(normalized) || "M350".equals(normalized);
    }

    private boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private void validatePublishableRecord(PlannedWaylineEntity entity) {
        if (usesZenmusePayloadSelection(entity.getAircraftModelKey())) {
            if (!isSupportedZenmusePayload(entity.getPayloadModelKey())
                    || entity.getPayloadPositionIndex() == null
                    || entity.getPayloadPositionIndex() < 0
                    || entity.getPayloadPositionIndex() > 2) {
                throw new IllegalArgumentException(
                        "M300/M350 wayline must confirm payload H20/H20T/H30/H30T and position 0/1/2 before publishing.");
            }
        }
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
        if (isAreaRoute(entity)) {
            validateAreaFields(entity.getRouteKind(), readAreaPolygon(entity.getAreaPolygonJson()),
                    entity.getAreaFrontOverlap(), entity.getAreaSideOverlap(), entity.getAreaHeadingDeg());
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
        DeviceEnum payloadDevice = resolvePayloadDevice(droneDevice, entity.getPayloadModelKey());
        List<PlannedWaypointDTO> waypoints = readWaypoints(entity.getWaypointsJson());
        List<PlannedAreaVertexDTO> areaPolygon = readAreaPolygon(entity.getAreaPolygonJson());

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
                zipOutputStream.write(isAreaRoute(entity)
                        ? buildAreaTemplateKml(entity, droneDevice, payloadDevice, areaPolygon, waypoints)
                        : buildTemplateKml(publishedName, entity, droneDevice, payloadDevice, waypoints));
                zipOutputStream.closeEntry();
                zipOutputStream.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
                zipOutputStream.write(isAreaRoute(entity)
                        ? buildAreaWaylinesWpml(entity, droneDevice, payloadDevice, waypoints)
                        : buildWaylinesWpml(publishedName, entity, droneDevice, payloadDevice, waypoints));
                zipOutputStream.closeEntry();
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate published KMZ.", e);
        }
    }

    private boolean isAreaRoute(PlannedWaylineEntity entity) {
        return entity != null && ROUTE_KIND_AREA.equalsIgnoreCase(entity.getRouteKind());
    }

    /**
     * DJI Pilot 2 面状航线的 template.kml 是 mapping2d 多边形模板，不是展开后的 waypoint 模板。
     * 展开的蛇形航点仅写入 waylines.wpml，二者共用同一 WGS84 测区和测绘参数。
     */
    private byte[] buildAreaTemplateKml(PlannedWaylineEntity entity,
                                        DeviceEnum droneDevice,
                                        DeviceEnum payloadDevice,
                                        List<PlannedAreaVertexDTO> areaPolygon,
                                        List<PlannedWaypointDTO> waypoints) {
        return writeKml(w -> {
            long now = System.currentTimeMillis();
            elem(w, "createTime", String.valueOf(now));
            elem(w, "updateTime", String.valueOf(now));
            writeAreaMissionConfig(w, entity, droneDevice, payloadDevice, true);

            w.writeStartElement("Folder");
            elem(w, "templateType", "mapping2d");
            elem(w, "templateId", "0");

            double height = globalAvgHeightValue(waypoints, entity.getDefaultHeight());
            w.writeStartElement(NS_WPML, "waylineCoordinateSysParam");
            elem(w, "coordinateMode", "WGS84");
            elem(w, "heightMode", "relativeToStartPoint");
            elem(w, "globalShootHeight", formatNumeric(height));
            w.writeEndElement();
            elem(w, "autoFlightSpeed", formatNumeric(generatedFlightSpeed(entity)));

            w.writeStartElement("Placemark");
            elem(w, "caliFlightEnable", "0");
            // Pilot 样例开启高程优化后会额外生成一段不可见于当前规划绿线的校准航段。
            // 系统必须让执行线与界面规划线一致，因此关闭该附加航段。
            elem(w, "elevationOptimizeEnable", "0");
            elem(w, "smartObliqueEnable", "0");
            elem(w, "facadeWaylineEnable", "0");
            elem(w, "isLookAtSceneSet", "0");
            elem(w, "smartObliqueGimbalPitch", "-45");
            elem(w, "shootType", "time");
            elem(w, "direction", formatNumeric(defaultAreaHeading(entity.getAreaHeadingDeg())));
            elem(w, "margin", "0");
            elem(w, "efficiencyFlightModeEnable", "0");

            int frontOverlap = defaultAreaOverlap(entity.getAreaFrontOverlap(), DEFAULT_AREA_FRONT_OVERLAP);
            int sideOverlap = defaultAreaOverlap(entity.getAreaSideOverlap(), DEFAULT_AREA_SIDE_OVERLAP);
            w.writeStartElement(NS_WPML, "overlap");
            elem(w, "orthoLidarOverlapH", String.valueOf(frontOverlap));
            elem(w, "orthoLidarOverlapW", String.valueOf(sideOverlap));
            elem(w, "orthoCameraOverlapH", String.valueOf(frontOverlap));
            elem(w, "orthoCameraOverlapW", String.valueOf(sideOverlap));
            w.writeEndElement();

            w.writeStartElement("Polygon");
            w.writeStartElement("outerBoundaryIs");
            w.writeStartElement("LinearRing");
            w.writeStartElement("coordinates");
            String polygonCoordinates = areaPolygon.stream()
                    .map(vertex -> vertex.getWgsLng() + "," + vertex.getWgsLat() + ",0")
                    .collect(Collectors.joining(" "));
            w.writeCharacters(polygonCoordinates);
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndElement();
            elem(w, "ellipsoidHeight", formatNumeric(height));
            elem(w, "height", formatNumeric(height));
            w.writeEndElement(); // /Placemark

            w.writeStartElement(NS_WPML, "payloadParam");
            elem(w, "payloadPositionIndex", String.valueOf(payloadPositionIndex(entity)));
            elem(w, "dewarpingEnable", "0");
            elem(w, "returnMode", "singleReturnFirst");
            elem(w, "samplingRate", "240000");
            elem(w, "scanningMode", "nonRepetitive");
            elem(w, "modelColoringEnable", "0");
            elem(w, "imageFormat", "wide");
            w.writeEndElement();
            w.writeEndElement(); // /Folder
        });
    }

    private byte[] buildAreaWaylinesWpml(PlannedWaylineEntity entity,
                                         DeviceEnum droneDevice,
                                         DeviceEnum payloadDevice,
                                         List<PlannedWaypointDTO> waypoints) {
        return writeKml(w -> {
            writeAreaMissionConfig(w, entity, droneDevice, payloadDevice, false);
            w.writeStartElement("Folder");
            elem(w, "templateId", "0");
            elem(w, "executeHeightMode", "relativeToStartPoint");
            elem(w, "waylineId", "0");

            double flightSpeed = generatedFlightSpeed(entity);
            double distance = totalDistanceMeters(waypoints);
            elem(w, "distance", formatNumeric(distance));
            elem(w, "duration", formatNumeric(distance / Math.max(flightSpeed, 1)));
            elem(w, "autoFlightSpeed", formatNumeric(flightSpeed));

            for (int index = 0; index < waypoints.size(); index++) {
                writeAreaWaylinePlacemark(w, waypoints, index, flightSpeed);
            }
            w.writeEndElement(); // /Folder
        });
    }

    private DeviceEnum resolveDroneDevice(String aircraftModelKey) {
        try {
            return DeviceEnum.valueOf(normalizeAircraftModelKey(aircraftModelKey));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported aircraft model for planned-wayline publish: " + aircraftModelKey);
        }
    }

    private DeviceEnum resolvePayloadDevice(DeviceEnum droneDevice, String payloadModelKey) {
        if (droneDevice == DeviceEnum.M300 || droneDevice == DeviceEnum.M350) {
            String normalizedPayload = StringUtils.hasText(payloadModelKey)
                    ? payloadModelKey.trim().toUpperCase(Locale.ROOT)
                    : "H20T";
            switch (normalizedPayload) {
                case "H20": return DeviceEnum.H20;
                case "H20T": return DeviceEnum.H20T;
                case "H30": return DeviceEnum.H30;
                case "H30T": return DeviceEnum.H30T;
                default:
                    throw new IllegalArgumentException("Unsupported M300/M350 payload: " + payloadModelKey);
            }
        }
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

            double flightSpeed = generatedFlightSpeed(entity);
            elem(w, "autoFlightSpeed", formatNumeric(flightSpeed));
            elem(w, "globalHeight", String.valueOf(globalAvgHeight(waypoints)));
            elem(w, "caliFlightEnable", "0");
            elem(w, "gimbalPitchMode", "usePointSetting");

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

            // 不要在 waylines.wpml 写 waylineCoordinateSysParam。
            // 旧注释说 Pilot 2 真机导出包含此节点 — 但用 m4t_probe.kmz (来自 Pilot 2) 对比，
            // probe.waylines.wpml 实际**没有**这个节点；写了反而让 MSDK pushKMZFileToAircraft
            // 报 GENERATE_MISSION_FILE_FAILED。template.kml 仍保留该节点（line 895）。

            double flightSpeed = generatedFlightSpeed(entity);
            double distance = totalDistanceMeters(waypoints);
            elem(w, "distance", String.valueOf(distance));
            elem(w, "duration", String.valueOf(distance / Math.max(flightSpeed, 1)));
            elem(w, "autoFlightSpeed", formatNumeric(flightSpeed));

            int index = 0;
            for (PlannedWaypointDTO wp : waypoints) {
                writeWaylinePlacemark(w, wp, index, flightSpeed, maxTurnDampingAt(waypoints, index));
                index++;
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
        writePayloadInfo(w, payloadDevice, entity.getPayloadPositionIndex());
        w.writeEndElement(); // /missionConfig
    }

    private void writeAreaMissionConfig(XMLStreamWriter w,
                                        PlannedWaylineEntity entity,
                                        DeviceEnum droneDevice,
                                        DeviceEnum payloadDevice,
                                        boolean includeTakeOffRefPoint) throws XMLStreamException {
        w.writeStartElement(NS_WPML, "missionConfig");
        elem(w, "flyToWaylineMode", "safely");
        elem(w, "finishAction",
                entity.getFinishAction() != null ? entity.getFinishAction() : FINISH_ACTION);
        elem(w, "exitOnRCLost",
                entity.getExitOnRcLost() != null ? entity.getExitOnRcLost() : "executeLostAction");
        elem(w, "executeRCLostAction",
                entity.getRcLostAction() != null ? entity.getRcLostAction() : EXECUTE_RC_LOST_ACTION);
        elem(w, "takeOffSecurityHeight", formatNumeric(
                entity.getTakeoffSecurityHeight() != null ? entity.getTakeoffSecurityHeight() : 60));
        if (includeTakeOffRefPoint) {
            takeOffRefPoint(entity).ifPresent(value -> {
                try {
                    elem(w, "takeOffRefPoint", value);
                } catch (XMLStreamException e) {
                    throw new IllegalStateException("Failed to write area takeoff reference point.", e);
                }
            });
        }
        elem(w, "globalTransitionalSpeed", formatNumeric(
                entity.getGlobalTransitionalSpeed() != null ? entity.getGlobalTransitionalSpeed() : 15.0));
        writeDroneInfo(w, droneDevice);
        writePayloadInfo(w, payloadDevice, entity.getPayloadPositionIndex());
        w.writeEndElement();
    }

    private Optional<String> takeOffRefPoint(PlannedWaylineEntity entity) {
        if (msdkDeviceStateService == null) {
            return Optional.empty();
        }
        String aircraftSn = StringUtils.hasText(entity.getDroneSn()) ? entity.getDroneSn() : entity.getAircraftSn();
        if (!StringUtils.hasText(aircraftSn)) {
            return Optional.empty();
        }
        return msdkDeviceStateService.get(aircraftSn)
                .filter(state -> isLegalLatitude(state.getLatitude()) && isLegalLongitude(state.getLongitude())
                        && !(state.getLatitude() == 0.0 && state.getLongitude() == 0.0))
                // DJI Pilot 2 的 takeOffRefPoint 顺序是 lat,lng,alt，与 KML coordinates 相反。
                .map(state -> state.getLatitude() + "," + state.getLongitude() + ",0.000000");
    }

    private int payloadPositionIndex(PlannedWaylineEntity entity) {
        return entity.getPayloadPositionIndex() == null ? 0 : entity.getPayloadPositionIndex();
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

    private void writePayloadInfo(XMLStreamWriter w, DeviceEnum payloadDevice, Integer payloadPositionIndex) throws XMLStreamException {
        w.writeStartElement(NS_WPML, "payloadInfo");
        elem(w, "payloadEnumValue", String.valueOf(payloadDevice.getType().getType()));
        elem(w, "payloadSubEnumValue", String.valueOf(payloadDevice.getSubType().getSubType()));
        elem(w, "payloadPositionIndex", String.valueOf(payloadPositionIndex == null ? 0 : payloadPositionIndex));
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
        elem(w, "gimbalPitchAngle", formatNumeric(
                wp.getGimbalPitch() != null ? wp.getGimbalPitch() : -45.0));
        w.writeStartElement(NS_WPML, "waypointTurnParam");
        elem(w, "waypointTurnMode",
                wp.getTurnMode() != null ? wp.getTurnMode() : STRICT_WAYPOINT_TURN_MODE);
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

    private void writeWaylinePlacemark(XMLStreamWriter w, PlannedWaypointDTO wp, int index, double defaultSpeed, double maxTurnDamping) throws XMLStreamException {
        w.writeStartElement("Placemark");
        w.writeStartElement("Point");
        elem(w, NS_KML, "coordinates", wp.getWgsLng() + "," + wp.getWgsLat());
        w.writeEndElement();
        elem(w, "index", String.valueOf(index));
        elem(w, "executeHeight", String.valueOf(wp.getHeight()));
        elem(w, "waypointSpeed", formatNumeric(
                wp.getSpeed() != null ? wp.getSpeed() : defaultSpeed));
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
        String turnMode = wp.getTurnMode() != null ? wp.getTurnMode() : STRICT_WAYPOINT_TURN_MODE;
        elem(w, "waypointTurnMode", turnMode);
        double defaultTurnDamping = STRICT_WAYPOINT_TURN_MODE.equals(turnMode) ? 0.0 : 10.0;
        elem(w, "waypointTurnDampingDist", formatNumeric(Math.min(
                wp.getTurnDamping() != null ? wp.getTurnDamping() : defaultTurnDamping, maxTurnDamping)));
        w.writeEndElement();
        elem(w, "useStraightLine", "1");
        w.writeStartElement(NS_WPML, "waypointGimbalHeadingParam");
        // 火情巡航默认固定前下视 -45°，与前端及 template.kml 的航点设置一致。
        elem(w, "waypointGimbalPitchAngle", formatNumeric(
                wp.getGimbalPitch() != null ? wp.getGimbalPitch() : -45.0));
        elem(w, "waypointGimbalYawAngle", formatNumeric(
                wp.getGimbalYaw() != null ? wp.getGimbalYaw() : 0.0));
        w.writeEndElement();
        writeActionGroups(w, wp, index);
        elem(w, "isRisky", "0");
        elem(w, "waypointWorkType", "0");
        w.writeEndElement();
    }

    private void writeAreaWaylinePlacemark(XMLStreamWriter w,
                                           List<PlannedWaypointDTO> waypoints,
                                           int index,
                                           double defaultSpeed) throws XMLStreamException {
        PlannedWaypointDTO wp = waypoints.get(index);
        boolean last = index == waypoints.size() - 1;
        w.writeStartElement("Placemark");
        w.writeStartElement("Point");
        elem(w, NS_KML, "coordinates", wp.getWgsLng() + "," + wp.getWgsLat());
        w.writeEndElement();
        elem(w, "index", String.valueOf(index));
        elem(w, "executeHeight", formatNumeric(wp.getHeight()));
        elem(w, "waypointSpeed", formatNumeric(wp.getSpeed() != null ? wp.getSpeed() : defaultSpeed));

        w.writeStartElement(NS_WPML, "waypointHeadingParam");
        elem(w, "waypointHeadingMode", "followWayline");
        elem(w, "waypointHeadingAngle", formatNumeric(last ? 0.0 : bearingDegrees(wp, waypoints.get(index + 1))));
        elem(w, "waypointPoiPoint", "0.000000,0.000000,0.000000");
        elem(w, "waypointHeadingAngleEnable", last ? "0" : "1");
        elem(w, "waypointHeadingPathMode", "followBadArc");
        elem(w, "waypointHeadingPoiIndex", "0");
        w.writeEndElement();

        w.writeStartElement(NS_WPML, "waypointTurnParam");
        // 面状巡逻以“实际经过每个规划转折点”为安全边界。coordinateTurn 会在到点前
        // 按阻尼距离切弯，真机轨迹因此会偏离绿线；逐点停车转向则不会切角。
        elem(w, "waypointTurnMode", "toPointAndStopWithDiscontinuityCurvature");
        elem(w, "waypointTurnDampingDist", "0");
        w.writeEndElement();
        elem(w, "useStraightLine", "1");

        w.writeStartElement(NS_WPML, "waypointGimbalHeadingParam");
        elem(w, "waypointGimbalPitchAngle", "-45");
        elem(w, "waypointGimbalYawAngle", "0");
        w.writeEndElement();
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

    private static double globalAvgHeightValue(List<PlannedWaypointDTO> wps, Double fallback) {
        // mapping2d has a single global shoot height; keep the user-entered value exact instead of
        // accumulating binary floating-point noise while averaging identical waypoint heights.
        if (fallback != null && fallback > 0) {
            return fallback;
        }
        if (wps == null || wps.isEmpty()) {
            return 100.0;
        }
        double sum = 0;
        int count = 0;
        for (PlannedWaypointDTO wp : wps) {
            if (wp != null && wp.getHeight() != null && wp.getHeight() > 0) {
                sum += wp.getHeight();
                count++;
            }
        }
        return count == 0 ? 100.0 : sum / count;
    }

    private static double generatedFlightSpeed(PlannedWaylineEntity entity) {
        if (entity != null && entity.getMaxSpeed() != null && entity.getMaxSpeed() > 0) {
            return entity.getMaxSpeed();
        }
        return AUTO_FLIGHT_SPEED_MPS;
    }

    /** Haversine distance sum in meters between consecutive WGS84 waypoints. */
    /**
     * DJI 固件要求转弯截距小于相邻航段长度的一半，超限拒飞
     * (TRAJ_DAMP_DIS_OUT_OF_RANGE)。按航点取前后最短航段套用
     * resolveM4tRuntimeTurnDamping 同款公式 min(10, max(0.5, 段长/4))。
     */
    private static double maxTurnDampingAt(List<PlannedWaypointDTO> wps, int index) {
        double minSegment = Double.POSITIVE_INFINITY;
        if (index > 0) {
            minSegment = haversineMeters(
                    wps.get(index - 1).getWgsLat(), wps.get(index - 1).getWgsLng(),
                    wps.get(index).getWgsLat(), wps.get(index).getWgsLng());
        }
        if (index < wps.size() - 1) {
            minSegment = Math.min(minSegment, haversineMeters(
                    wps.get(index).getWgsLat(), wps.get(index).getWgsLng(),
                    wps.get(index + 1).getWgsLat(), wps.get(index + 1).getWgsLng()));
        }
        if (!Double.isFinite(minSegment)) {
            return 10.0;
        }
        return Math.min(10.0, Math.max(0.5, minSegment / 4.0));
    }

    /** Initial great-circle bearing normalized to Pilot 2's [-180, 180) representation. */
    private static double bearingDegrees(PlannedWaypointDTO from, PlannedWaypointDTO to) {
        double lat1 = Math.toRadians(from.getWgsLat());
        double lat2 = Math.toRadians(to.getWgsLat());
        double dLng = Math.toRadians(to.getWgsLng() - from.getWgsLng());
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return bearing >= 180.0 ? bearing - 360.0 : bearing;
    }

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

    private String buildImportedKmzObjectKey(String plannedWaylineId) {
        String filename = plannedWaylineId + ".kmz";
        if (!StringUtils.hasText(OssConfiguration.objectDirPrefix)) {
            return filename;
        }
        return trimTrailingSlash(OssConfiguration.objectDirPrefix) + "/" + filename;
    }

    private String normalizeImportedKmzFilename(String originalFilename, String fallbackId) {
        String fallback = StringUtils.hasText(fallbackId) ? fallbackId : "imported-wayline";
        String filename = StringUtils.hasText(originalFilename) ? originalFilename.trim() : fallback + ".kmz";
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".kmz")) {
            filename = filename + ".kmz";
        }
        String basename = filename.substring(0, filename.length() - 4);
        return sanitizeDjiWaylineName(basename, fallback) + ".kmz";
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
        boolean metadataSafe = waylineFileService.getWaylineByWaylineId(workspaceId, entity.getPublishedWaylineId())
                .map(file -> isDjiSafeWaylineName(file.getName()))
                .orElse(false);
        if (!metadataSafe) {
            return false;
        }
        try {
            byte[] content = waylineFileService.downloadWaylineContent(workspaceId, entity.getPublishedWaylineId());
            if (content == null || content.length == 0) {
                return false;
            }
            if (isAreaRoute(entity)) {
                return isSafeAreaExecutionKmz(content);
            }
            int requiredStrictWaypoints = requiredStrictWaypointCount(entity);
            return requiredStrictWaypoints == 0
                    || isSafeStrictWaypointExecutionKmz(content, requiredStrictWaypoints);
        } catch (SQLException | IOException e) {
            return false;
        }
    }

    private int requiredStrictWaypointCount(PlannedWaylineEntity entity) {
        if (entity == null || isAreaRoute(entity)) {
            return 0;
        }
        int count = 0;
        for (PlannedWaypointDTO waypoint : readWaypoints(entity.getWaypointsJson())) {
            if (!StringUtils.hasText(waypoint.getTurnMode())
                    || STRICT_WAYPOINT_TURN_MODE.equals(waypoint.getTurnMode())) {
                count++;
            }
        }
        return count;
    }

    private boolean isSafeStrictWaypointExecutionKmz(byte[] content, int requiredStrictWaypoints) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if ("wpmz/waylines.wpml".equals(entry.getName())) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    zipInputStream.transferTo(outputStream);
                    String wpml = outputStream.toString(StandardCharsets.UTF_8);
                    int stopTurnCount = countOccurrences(wpml,
                            "<wpml:waypointTurnMode>" + STRICT_WAYPOINT_TURN_MODE + "</wpml:waypointTurnMode>");
                    int zeroDampingCount = countOccurrences(wpml,
                            "<wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>");
                    return stopTurnCount >= requiredStrictWaypoints
                            && zeroDampingCount >= requiredStrictWaypoints;
                }
                entry = zipInputStream.getNextEntry();
            }
        }
        return false;
    }

    private boolean isSafeAreaExecutionKmz(byte[] content) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if ("wpmz/waylines.wpml".equals(entry.getName())) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    zipInputStream.transferTo(outputStream);
                    String wpml = outputStream.toString(StandardCharsets.UTF_8);
                    int placemarkCount = countOccurrences(wpml, "<Placemark>");
                    int stopTurnCount = countOccurrences(wpml,
                            "<wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>");
                    return placemarkCount > 0
                            && stopTurnCount == placemarkCount
                            && !wpml.contains("<wpml:waypointTurnMode>coordinateTurn</wpml:waypointTurnMode>")
                            && !wpml.contains("<wpml:actionGroup>")
                            && !wpml.contains("<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>")
                            && !wpml.contains("<wpml:actionTriggerType>multipleTiming</wpml:actionTriggerType>");
                }
                entry = zipInputStream.getNextEntry();
            }
        }
        return false;
    }

    private static int countOccurrences(String value, String token) {
        if (!StringUtils.hasLength(value) || !StringUtils.hasLength(token)) {
            return 0;
        }
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
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
        target.setAircraftModelKey(normalizeAircraftModelKey(param.getAircraftModelKey()));
        target.setPayloadModelKey(normalizePayloadModelKey(param.getPayloadModelKey()));
        target.setPayloadPositionIndex(param.getPayloadPositionIndex());
        target.setGatewaySn(param.getGatewaySn());
        target.setAircraftSn(param.getAircraftSn());
        target.setDefaultHeight(param.getDefaultHeight());
        target.setMaxSpeed(param.getMaxSpeed());
        target.setRouteKind(normalizeRouteKind(param.getRouteKind()));
        target.setAreaPolygonJson(writeAreaPolygon(param.getAreaPolygon()));
        target.setAreaCameraKey(normalizeAreaCameraKey(param.getAreaCameraKey(), param.getPayloadModelKey()));
        target.setAreaFrontOverlap(defaultAreaOverlap(param.getAreaFrontOverlap(), DEFAULT_AREA_FRONT_OVERLAP));
        target.setAreaSideOverlap(defaultAreaOverlap(param.getAreaSideOverlap(), DEFAULT_AREA_SIDE_OVERLAP));
        target.setAreaHeadingDeg(defaultAreaHeading(param.getAreaHeadingDeg()));
        target.setFinishAction(param.getFinishAction());
        target.setExitOnRcLost(param.getExitOnRcLost());
        target.setRcLostAction(param.getRcLostAction());
        target.setTakeoffSecurityHeight(param.getTakeoffSecurityHeight());
        target.setGlobalTransitionalSpeed(param.getGlobalTransitionalSpeed());
        target.setRthAltitude(param.getRthAltitude());
        target.setWaypointsJson(writeWaypoints(param.getWaypoints()));
    }

    private PlannedWaylineEntity dto2Entity(CreatePlannedWaylineParam param) {
        if (param == null) {
            return new PlannedWaylineEntity();
        }
        return PlannedWaylineEntity.builder()
                .name(sanitizeDjiWaylineName(param.getName(), null))
                .aircraftModelKey(normalizeAircraftModelKey(param.getAircraftModelKey()))
                .payloadModelKey(normalizePayloadModelKey(param.getPayloadModelKey()))
                .payloadPositionIndex(param.getPayloadPositionIndex())
                .gatewaySn(param.getGatewaySn())
                .aircraftSn(param.getAircraftSn())
                .defaultHeight(param.getDefaultHeight())
                .maxSpeed(param.getMaxSpeed())
                .routeKind(normalizeRouteKind(param.getRouteKind()))
                .areaPolygonJson(writeAreaPolygon(param.getAreaPolygon()))
                .areaCameraKey(normalizeAreaCameraKey(param.getAreaCameraKey(), param.getPayloadModelKey()))
                .areaFrontOverlap(defaultAreaOverlap(param.getAreaFrontOverlap(), DEFAULT_AREA_FRONT_OVERLAP))
                .areaSideOverlap(defaultAreaOverlap(param.getAreaSideOverlap(), DEFAULT_AREA_SIDE_OVERLAP))
                .areaHeadingDeg(defaultAreaHeading(param.getAreaHeadingDeg()))
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
                .payloadModelKey(dto.getPayloadModelKey())
                .payloadPositionIndex(dto.getPayloadPositionIndex())
                .gatewaySn(dto.getGatewaySn())
                .aircraftSn(dto.getAircraftSn())
                .defaultHeight(dto.getDefaultHeight())
                .maxSpeed(dto.getMaxSpeed())
                .routeKind(normalizeRouteKind(dto.getRouteKind()))
                .areaPolygonJson(writeAreaPolygon(dto.getAreaPolygon()))
                .areaCameraKey(normalizeAreaCameraKey(dto.getAreaCameraKey(), dto.getPayloadModelKey()))
                .areaFrontOverlap(defaultAreaOverlap(dto.getAreaFrontOverlap(), DEFAULT_AREA_FRONT_OVERLAP))
                .areaSideOverlap(defaultAreaOverlap(dto.getAreaSideOverlap(), DEFAULT_AREA_SIDE_OVERLAP))
                .areaHeadingDeg(defaultAreaHeading(dto.getAreaHeadingDeg()))
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
                .aircraftModelKey(normalizeAircraftModelKey(param.getAircraftModelKey()))
                .payloadModelKey(normalizePayloadModelKey(param.getPayloadModelKey()))
                .payloadPositionIndex(param.getPayloadPositionIndex())
                .gatewaySn(param.getGatewaySn())
                .aircraftSn(param.getAircraftSn())
                .defaultHeight(param.getDefaultHeight())
                .maxSpeed(param.getMaxSpeed())
                .routeKind(normalizeRouteKind(param.getRouteKind()))
                .areaPolygonJson(writeAreaPolygon(param.getAreaPolygon()))
                .areaCameraKey(normalizeAreaCameraKey(param.getAreaCameraKey(), param.getPayloadModelKey()))
                .areaFrontOverlap(defaultAreaOverlap(param.getAreaFrontOverlap(), DEFAULT_AREA_FRONT_OVERLAP))
                .areaSideOverlap(defaultAreaOverlap(param.getAreaSideOverlap(), DEFAULT_AREA_SIDE_OVERLAP))
                .areaHeadingDeg(defaultAreaHeading(param.getAreaHeadingDeg()))
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
        PlannedWaylineDTO dto = PlannedWaylineDTO.builder()
                .plannedWaylineId(entity.getPlannedWaylineId())
                .workspaceId(entity.getWorkspaceId())
                .name(entity.getName())
                .aircraftModelKey(entity.getAircraftModelKey())
                .payloadModelKey(
                        usesZenmusePayloadSelection(entity.getAircraftModelKey())
                                && !StringUtils.hasText(entity.getPayloadModelKey())
                                ? "H20T" : entity.getPayloadModelKey())
                .payloadPositionIndex(
                        usesZenmusePayloadSelection(entity.getAircraftModelKey())
                                && entity.getPayloadPositionIndex() == null
                                ? Integer.valueOf(0) : entity.getPayloadPositionIndex())
                .gatewaySn(entity.getGatewaySn())
                .aircraftSn(entity.getAircraftSn())
                .defaultHeight(entity.getDefaultHeight())
                .maxSpeed(entity.getMaxSpeed())
                .routeKind(StringUtils.hasText(entity.getRouteKind()) ? entity.getRouteKind() : ROUTE_KIND_WAYPOINT)
                .areaPolygon(readAreaPolygon(entity.getAreaPolygonJson()))
                .areaCameraKey(normalizeAreaCameraKey(entity.getAreaCameraKey(), entity.getPayloadModelKey()))
                .areaFrontOverlap(defaultAreaOverlap(entity.getAreaFrontOverlap(), DEFAULT_AREA_FRONT_OVERLAP))
                .areaSideOverlap(defaultAreaOverlap(entity.getAreaSideOverlap(), DEFAULT_AREA_SIDE_OVERLAP))
                .areaHeadingDeg(defaultAreaHeading(entity.getAreaHeadingDeg()))
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
        enrichAircraftPosition(dto);
        return dto;
    }

    private void enrichAircraftPosition(PlannedWaylineDTO dto) {
        if (dto == null || msdkDeviceStateService == null) {
            return;
        }
        String aircraftSn = StringUtils.hasText(dto.getDroneSn()) ? dto.getDroneSn() : dto.getAircraftSn();
        if (!StringUtils.hasText(aircraftSn)) {
            return;
        }
        msdkDeviceStateService.get(aircraftSn).ifPresent(state -> {
            Double lat = state.getLatitude();
            Double lng = state.getLongitude();
            if (!isFinite(lat) || !isFinite(lng) || (lat == 0.0 && lng == 0.0)) {
                return;
            }
            double[] gcj = wgs84ToGcj02(lng, lat);
            dto.setAircraftLng(lng)
                    .setAircraftLat(lat)
                    .setAircraftGcjLng(gcj[0])
                    .setAircraftGcjLat(gcj[1])
                    .setAircraftHeight(state.getHeight())
                    .setAircraftUpdatedAt(state.getUpdatedAt());
        });
    }

    private String writeWaypoints(List<PlannedWaypointDTO> waypoints) {
        try {
            return objectMapper.writeValueAsString(waypoints);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize planned waypoints.", e);
        }
    }

    private String writeAreaPolygon(List<PlannedAreaVertexDTO> areaPolygon) {
        if (areaPolygon == null || areaPolygon.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(areaPolygon);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize planned area polygon.", e);
        }
    }

    private List<PlannedAreaVertexDTO> readAreaPolygon(String areaPolygonJson) {
        if (!StringUtils.hasText(areaPolygonJson)) {
            return new ArrayList<>();
        }
        try {
            List<PlannedAreaVertexDTO> vertices = objectMapper.readValue(
                    areaPolygonJson, new TypeReference<List<PlannedAreaVertexDTO>>() {
                    });
            return vertices == null ? new ArrayList<>() : vertices;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize planned area polygon.", e);
        }
    }

    private String normalizeAreaCameraKey(String raw, String payloadModelKey) {
        if (StringUtils.hasText(raw)) {
            return raw.trim().toUpperCase(Locale.ROOT);
        }
        if (StringUtils.hasText(payloadModelKey)) {
            return payloadModelKey.trim().toUpperCase(Locale.ROOT);
        }
        return DEFAULT_AREA_CAMERA_KEY;
    }

    private int defaultAreaOverlap(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private double defaultAreaHeading(Double value) {
        return value == null ? 0.0 : value;
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
