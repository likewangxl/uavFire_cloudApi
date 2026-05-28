package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryBypassStreamDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceLiveDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeliveryBypassStreamRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import com.yx.uavfire.fc100.event.dao.FireEventMapper;
import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import com.yx.uavfire.fc100.safety.model.dto.SafetyCheckResult;
import com.yx.uavfire.fc100.safety.model.enums.SafetyCheckPhase;
import com.yx.uavfire.fc100.safety.service.SafetyCheckService;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.model.param.WaypointGenerateParam;
import com.yx.uavfire.fc100.waypoint.service.WaypointPlannerService;
import com.yx.uavfire.wayline.model.dto.PlannedWaylineDTO;
import com.yx.uavfire.wayline.service.IPlannedWaylineService;
import com.yx.uavfire.wayline.service.IWaylineFileService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** spec §4.5 — 5 个 Delivery 相关端点（spec API 路径已扁平到 /api/fire/...） */
@RestController
@RequestMapping("/api/fire")
public class DeliveryController {
    private static final Logger log = LoggerFactory.getLogger(DeliveryController.class);
    private static final long FC100_BYPASS_CACHE_TTL_MS = 110L * 60L * 1000L;
    private static final double AUTO_RELEASE_DROP_RADIUS_M = 5.0;
    private static final double AUTO_RELEASE_HORIZONTAL_SPEED_MPS = 0.6;
    private static final double AUTO_RELEASE_VERTICAL_SPEED_MPS = 0.5;

    private final DeliverySyncAdapter adapter;
    private final DeliverySyncProperties props;
    private final FireMissionMapper missionMapper;
    private final RouteExportService routeService;
    private final MissionStateMachine sm;
    private final IPlannedWaylineService plannedWaylineService;
    private final IWaylineFileService waylineFileService;
    private final FireEventMapper fireEventMapper;
    private final WaypointPlannerService waypointPlannerService;
    private final SafetyCheckService safetyCheckService;
    private final ConcurrentHashMap<String, CachedDeviceLive> fc100BypassLiveCache = new ConcurrentHashMap<>();

    @Value("${livestream.url.rtmp.url:}")
    private String deliveryBypassRtmpUrl;

    @Value("${livestream.playback.webrtc-host:}")
    private String webrtcPlaybackHost;

    @Value("${livestream.playback.webrtc-port:#{null}}")
    private Integer webrtcPlaybackPort;

    public DeliveryController(DeliverySyncAdapter a, DeliverySyncProperties p,
                               FireMissionMapper m, RouteExportService r, MissionStateMachine sm) {
        this(a, p, m, r, sm, null, null, null, null, null);
    }

    public DeliveryController(DeliverySyncAdapter a, DeliverySyncProperties p,
                               FireMissionMapper m, RouteExportService r, MissionStateMachine sm,
                               IPlannedWaylineService plannedWaylineService,
                               IWaylineFileService waylineFileService) {
        this(a, p, m, r, sm, plannedWaylineService, waylineFileService, null, null, null);
    }

    @Autowired
    public DeliveryController(DeliverySyncAdapter a, DeliverySyncProperties p,
                               FireMissionMapper m, RouteExportService r, MissionStateMachine sm,
                               IPlannedWaylineService plannedWaylineService,
                               IWaylineFileService waylineFileService,
                               FireEventMapper fireEventMapper,
                               WaypointPlannerService waypointPlannerService,
                               SafetyCheckService safetyCheckService) {
        this.adapter = a; this.props = p; this.missionMapper = m;
        this.routeService = r; this.sm = sm;
        this.plannedWaylineService = plannedWaylineService;
        this.waylineFileService = waylineFileService;
        this.fireEventMapper = fireEventMapper;
        this.waypointPlannerService = waypointPlannerService;
        this.safetyCheckService = safetyCheckService;
    }

    @GetMapping("/delivery/devices")
    public ApiResult<List<DeliveryDeviceDTO>> devices(
            @RequestParam(value = "workspaceId", required = false) String ws) {
        return ApiResult.success(adapter.listDevices(ws != null ? ws : props.getWorkspaceId()));
    }

    @GetMapping("/delivery/devices/{sn}/properties")
    public ApiResult<DeliveryDeviceProperties> deviceProps(@PathVariable("sn") String sn) {
        return ApiResult.success(adapter.getDeviceProperties(sn));
    }

    @GetMapping("/delivery/devices/{sn}/live")
    public ApiResult<DeliveryDeviceLiveDTO> deviceLive(@PathVariable("sn") String sn) {
        String deviceSn = sn == null ? "" : sn.trim();
        if (deviceSn.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "deviceSn is required");
        }

        DeliveryDeviceProperties properties = adapter.getDeviceProperties(deviceSn);
        boolean online = properties != null && Boolean.TRUE.equals(properties.getOnlineStatus());
        if (!online) {
            fc100BypassLiveCache.remove(deviceSn);
            return ApiResult.success(new DeliveryDeviceLiveDTO(
                deviceSn,
                "offline",
                null,
                "delivery-platform",
                "FC100 设备离线，未拉起直播"));
        }

        CachedDeviceLive cached = fc100BypassLiveCache.get(deviceSn);
        if (cached != null && !cached.isExpired()) {
            return ApiResult.success(cached.toDto());
        }

        DeliveryBypassStreamDTO bypass = adapter.startBypassStream(DeliveryBypassStreamRequest.builder()
            .deviceSn(deviceSn)
            .region("cn")
            .rtmpUrl(normalizeRtmpBaseUrl(deliveryBypassRtmpUrl))
            .camera("39-0-7")
            .video("normal-0")
            .expireTs(7200L)
            .videoQuality(0)
            .build());
        String playUrl = buildFc100BypassPlaybackUrl(deviceSn, bypass);
        String status = playUrl == null ? "idle" : "running";
        String message = "FC100 司运旁路推流已启动"
            + "，状态 " + firstText(bypass == null ? null : bypass.getConverterState(), "--")
            + "，转换任务 " + firstText(bypass == null ? null : bypass.getConverterId(), "--");

        DeliveryDeviceLiveDTO live = new DeliveryDeviceLiveDTO(
            deviceSn,
            status,
            playUrl,
            "delivery-platform",
            message);
        if (playUrl != null) {
            fc100BypassLiveCache.put(deviceSn, new CachedDeviceLive(live, System.currentTimeMillis()));
        }
        return ApiResult.success(live);
    }

    @GetMapping("/delivery/waylines")
    public ApiResult<List<DeliveryWaylineDTO>> waylines(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "key", required = false) String key) {
        return ApiResult.success(adapter.listWaylines(page, pageSize, key));
    }

    private String normalizeRtmpBaseUrl(String rtmpUrl) {
        if (rtmpUrl == null || rtmpUrl.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "livestream.url.rtmp.url is required for FC100 bypass stream");
        }
        return rtmpUrl.replaceAll("/+$", "");
    }

    private String buildFc100BypassPlaybackUrl(String deviceSn, DeliveryBypassStreamDTO bypass) {
        if (webrtcPlaybackHost == null || webrtcPlaybackHost.isBlank()) {
            return null;
        }
        String streamPath = extractZlmStreamPath(bypass == null ? null : bypass.getPlayRtmpUrl());
        if (streamPath == null) {
            streamPath = fallbackBypassStreamPath(deviceSn);
        }
        StringBuilder playbackUrl = new StringBuilder("webrtc://").append(webrtcPlaybackHost);
        if (webrtcPlaybackPort != null && webrtcPlaybackPort > 0) {
            playbackUrl.append(":").append(webrtcPlaybackPort);
        }
        return playbackUrl.append("/").append(streamPath).toString();
    }

    private String extractZlmStreamPath(String playRtmpUrl) {
        if (playRtmpUrl == null || playRtmpUrl.isBlank()) {
            return null;
        }
        try {
            String path = URI.create(playRtmpUrl).getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            return path.replaceAll("^/+", "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String fallbackBypassStreamPath(String deviceSn) {
        String appPath = "live";
        if (deliveryBypassRtmpUrl != null && !deliveryBypassRtmpUrl.isBlank()) {
            try {
                String path = URI.create(normalizeRtmpBaseUrl(deliveryBypassRtmpUrl)).getPath();
                if (path != null && !path.isBlank()) {
                    appPath = path.replaceAll("^/+", "");
                }
            } catch (RuntimeException ignored) {
                appPath = "live";
            }
        }
        return appPath + "/" + deviceSn + "_39-0-7";
    }

    private static class CachedDeviceLive {
        private final DeliveryDeviceLiveDTO live;
        private final long createdAtMs;

        private CachedDeviceLive(DeliveryDeviceLiveDTO live, long createdAtMs) {
            this.live = live;
            this.createdAtMs = createdAtMs;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - createdAtMs >= FC100_BYPASS_CACHE_TTL_MS;
        }

        private DeliveryDeviceLiveDTO toDto() {
            return new DeliveryDeviceLiveDTO(
                live.getDeviceSn(),
                live.getStreamStatus(),
                live.getPlayUrl(),
                live.getSource(),
                live.getMessage());
        }
    }

    @Data
    public static class CreateExistingWaylineTaskParam {
        @NotBlank private String deviceSn;
        @NotBlank private String waylineId;
        private String taskName;
        private String operatorId;
        private String remark;
    }

    @PostMapping("/delivery/wayline-tasks/create-from-wayline")
    @Idempotent("delivery.direct-wayline.create-existing")
    public ApiResult<DeliveryTaskRef> createTaskFromExistingWayline(
            @Valid @RequestBody CreateExistingWaylineTaskParam p) {
        String waylineId = p.getWaylineId().trim();
        DeliveryTaskRef ref = adapter.createTask(CreateTaskRequest.builder()
            .workspaceId(props.getWorkspaceId())
            .deviceSn(p.getDeviceSn().trim())
            .missionNo(waylineId)
            .missionId(waylineId)
            .taskName(p.getTaskName() != null && !p.getTaskName().isBlank()
                ? p.getTaskName().trim()
                : "FC100航线-" + waylineId)
            .remark(p.getRemark())
            .build());
        return ApiResult.success(ref);
    }

    @Data
    public static class ImportGeneratedPlannedWaylineTaskParam {
        @NotBlank private String workspaceId;
        @NotBlank private String plannedWaylineId;
        @NotBlank private String deviceSn;
        private String taskName;
        private String operatorId;
        private String remark;
    }

    @PostMapping("/delivery/wayline-tasks/import-planned-create")
    @Idempotent("delivery.direct-wayline.import-planned-create")
    public ApiResult<DeliveryTaskRef> importCreateGeneratedPlannedWaylineTask(
            @Valid @RequestBody ImportGeneratedPlannedWaylineTaskParam p) {
        if (plannedWaylineService == null || waylineFileService == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "planned wayline services are not available");
        }
        String workspaceId = p.getWorkspaceId().trim();
        String plannedWaylineId = p.getPlannedWaylineId().trim();
        PlannedWaylineDTO planned = plannedWaylineService.getOne(workspaceId, plannedWaylineId)
            .orElseThrow(() -> new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "planned wayline not found: " + p.getPlannedWaylineId()));
        if (planned.getPublishedWaylineId() == null || planned.getPublishedWaylineId().isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "planned wayline KMZ has not been generated");
        }

        String routeName = sanitizeFilename(firstText(p.getTaskName(), planned.getName(), plannedWaylineId));
        byte[] kmzBytes;
        String publishedWaylineId = planned.getPublishedWaylineId().trim();
        try {
            kmzBytes = waylineFileService.downloadWaylineContent(workspaceId, publishedWaylineId);
        } catch (SQLException e) {
            DeliveryWaylineImportResult existingWayline = findExistingWaylineByNameOrNull(routeName);
            if (existingWayline != null) {
                return ApiResult.success(createDeliveryTaskForWayline(p, workspaceId, planned, existingWayline));
            }
            planned = regeneratePlannedWaylineFile(workspaceId, plannedWaylineId, p.getOperatorId(), e);
            if (planned.getPublishedWaylineId() == null || planned.getPublishedWaylineId().isBlank()) {
                throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                    "failed to regenerate generated KMZ: published wayline id is missing");
            }
            publishedWaylineId = planned.getPublishedWaylineId().trim();
            try {
                kmzBytes = waylineFileService.downloadWaylineContent(workspaceId, publishedWaylineId);
            } catch (SQLException retryError) {
                throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                    "failed to read regenerated KMZ: " + retryError.getMessage());
            }
        }

        String importRouteName = sanitizeFilename(routeName + "-" + plannedWaylineId);
        kmzBytes = normalizeGeneratedKmzForFc100Import(kmzBytes, planned, importRouteName);
        DeliveryWaylineImportResult importedWayline = importOrReuseWayline(
            new UploadedWaylineFile(kmzBytes, importRouteName + ".kmz", "application/vnd.google-earth.kmz", importRouteName),
            plannedWaylineId,
            null);
        DeliveryTaskRef ref = createDeliveryTaskForWayline(p, workspaceId, planned, importedWayline);
        return ApiResult.success(ref);
    }

    private byte[] normalizeGeneratedKmzForFc100Import(byte[] kmzBytes, PlannedWaylineDTO planned, String importRouteName) {
        try {
            boolean normalizeM30t = planned != null && "M30T".equalsIgnoreCase(firstText(planned.getAircraftModelKey()));
            return rewriteKmzXml(kmzBytes, xml -> {
                String normalized = normalizeM30t ? normalizeM30tWpmlToFc100M4t(xml) : xml;
                normalized = normalizeFc100FinishAction(normalized);
                return normalizeFc100WaylineName(normalized, importRouteName);
            });
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "failed to normalize generated KMZ for FC100: " + e.getMessage());
        }
    }

    private byte[] rewriteKmzXml(byte[] kmzBytes, java.util.function.Function<String, String> xmlMapper)
            throws IOException {
        boolean changed = false;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(kmzBytes), StandardCharsets.UTF_8);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                byte[] content = zipInputStream.readAllBytes();
                if ("wpmz/template.kml".equals(entry.getName()) || "wpmz/waylines.wpml".equals(entry.getName())) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    String normalized = xmlMapper.apply(xml);
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

    private String normalizeM30tWpmlToFc100M4t(String xml) {
        return xml
            .replace("<wpml:droneEnumValue>67</wpml:droneEnumValue>",
                "<wpml:droneEnumValue>100</wpml:droneEnumValue>")
            .replace("<wpml:payloadEnumValue>53</wpml:payloadEnumValue>",
                "<wpml:payloadEnumValue>99</wpml:payloadEnumValue>");
    }

    private String normalizeFc100FinishAction(String xml) {
        String normalized = xml
            .replaceAll("(?s)<wpml:finishAction>.*?</wpml:finishAction>",
                "<wpml:finishAction>noAction</wpml:finishAction>")
            .replaceAll("(?s)<finishAction>.*?</finishAction>",
                "<finishAction>noAction</finishAction>");
        if (!normalized.equals(xml)) {
            return normalized;
        }
        if (normalized.contains("<wpml:flyToWaylineMode>")) {
            return normalized.replaceFirst("(?s)(<wpml:flyToWaylineMode>.*?</wpml:flyToWaylineMode>)",
                "$1<wpml:finishAction>noAction</wpml:finishAction>");
        }
        if (normalized.contains("<flyToWaylineMode>")) {
            return normalized.replaceFirst("(?s)(<flyToWaylineMode>.*?</flyToWaylineMode>)",
                "$1<finishAction>noAction</finishAction>");
        }
        return normalized;
    }

    private String normalizeFc100WaylineName(String xml, String waylineName) {
        if (waylineName == null || waylineName.isBlank()) {
            return xml;
        }
        String replacement = java.util.regex.Matcher.quoteReplacement("<name>" + waylineName + "</name>");
        String normalized = xml.replaceFirst("(?s)<name>.*?</name>", replacement);
        if (!normalized.equals(xml)) {
            return normalized;
        }
        return normalized.replaceFirst("(?s)(<Document[^>]*>)",
            "$1" + java.util.regex.Matcher.quoteReplacement("<name>" + waylineName + "</name>"));
    }

    private byte[] normalizeUploadedKmzForFc100Import(byte[] kmzBytes, String importRouteName) {
        try {
            return rewriteKmzXml(kmzBytes, xml -> {
                String normalized = normalizeFc100FinishAction(xml);
                return normalizeFc100WaylineName(normalized, importRouteName);
            });
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "failed to normalize uploaded KMZ for FC100: " + e.getMessage());
        }
    }

    private DeliveryTaskRef createDeliveryTaskForWayline(ImportGeneratedPlannedWaylineTaskParam p,
                                                         String workspaceId,
                                                         PlannedWaylineDTO planned,
                                                         DeliveryWaylineImportResult wayline) {
        String actualMissionId = firstText(wayline == null ? null : wayline.getWaylineId(),
            planned.getPublishedWaylineId(), p.getPlannedWaylineId());
        String actualTaskName = firstText(p.getTaskName(), planned.getName(), "FC100航线-" + actualMissionId);
        return adapter.createTask(CreateTaskRequest.builder()
            .workspaceId(workspaceId)
            .deviceSn(p.getDeviceSn().trim())
            .missionNo(actualMissionId)
            .missionId(actualMissionId)
            .taskName(actualTaskName)
            .remark(p.getRemark())
            .build());
    }

    private PlannedWaylineDTO regeneratePlannedWaylineFile(String workspaceId,
                                                           String plannedWaylineId,
                                                           String operatorId,
                                                           SQLException originalError) {
        try {
            return plannedWaylineService.generateFile(workspaceId, plannedWaylineId,
                firstText(operatorId, "fc100"));
        } catch (RuntimeException regenerateError) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "failed to read generated KMZ: " + originalError.getMessage()
                    + "; regenerate failed: " + regenerateError.getMessage());
        }
    }

    @PostMapping(value = "/delivery/wayline-tasks/import-create",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Idempotent("delivery.direct-wayline.import-create")
    public ApiResult<DeliveryTaskRef> importCreateDirectWaylineTask(
            @RequestParam("file") MultipartFile file,
            @RequestParam("deviceSn") String deviceSn,
            @RequestParam(value = "taskName", required = false) String taskName,
            @RequestParam(value = "operatorId", required = false) String operatorId,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam(value = "waylineId", required = false) String waylineId) {
        if (deviceSn == null || deviceSn.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "deviceSn is required");
        }
        String importWaylineId = waylineId != null && !waylineId.isBlank() ? waylineId.trim() : null;
        String actualWaylineId = importWaylineId != null ? importWaylineId : UUID.randomUUID().toString();
        UploadedWaylineFile routeFile = readUploadedWaylineFile(file, actualWaylineId);

        DeliveryWaylineImportResult importedWayline = importOrReuseWayline(routeFile, actualWaylineId, importWaylineId);
        String actualMissionId = firstText(importedWayline == null ? null : importedWayline.getWaylineId(), actualWaylineId);
        String actualTaskName = taskName != null && !taskName.isBlank()
            ? taskName.trim()
            : "FC100航线-" + firstText(importedWayline == null ? null : importedWayline.getName(), routeFile.waylineName, actualMissionId);

        DeliveryTaskRef ref = adapter.createTask(CreateTaskRequest.builder()
            .workspaceId(props.getWorkspaceId())
            .deviceSn(deviceSn.trim())
            .missionNo(actualMissionId)
            .missionId(actualMissionId)
            .taskName(actualTaskName)
            .remark(remark)
            .build());
        return ApiResult.success(ref);
    }

    @PostMapping("/delivery/wayline-tasks/{taskId}/start")
    @Idempotent("delivery.direct-wayline.start")
    public ApiResult<DeliveryTaskOperationResult> startDirectWaylineTask(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "deviceSn", required = false) String deviceSn) {
        DeliveryTaskOperationResult preflight = checkStartPreflight(taskId, deviceSn);
        if (preflight != null) {
            return ApiResult.success(preflight);
        }
        return ApiResult.success(adapter.startTask(taskId));
    }

    @GetMapping("/delivery/wayline-tasks/{taskId}/status")
    public ApiResult<DeliveryTaskStatus> directWaylineTaskStatus(@PathVariable("taskId") String taskId) {
        return ApiResult.success(adapter.queryTaskStatus(taskId));
    }

    @Data
    public static class CreateTaskParam {
        @NotBlank private String operatorId;
        private String taskName;
        private String remark;
        private List<String> notifies;
    }

    @Data
    public static class PrepareFireMissionDeliveryTaskParam {
        @NotBlank private String operatorId;
        private String taskName;
        private String remark;
        private List<String> notifies;
        private Double cruiseAlt;
        private Double dropAltAgl;
        private Double approachDistance;
        private Double exitDistance;
        private Double speed;
    }

    @PostMapping("/missions/{no}/delivery/prepare-task")
    @Idempotent("delivery.prepare-fire-mission-task")
    public ApiResult<DeliveryTaskRef> prepareFireMissionDeliveryTask(
            @PathVariable("no") String no,
            @Valid @RequestBody PrepareFireMissionDeliveryTaskParam p,
            HttpServletRequest req) {
        requireFireMissionDeliveryAutomationDependencies();

        FireMissionEntity mission = findMission(no);
        FireMissionStatus status = parseMissionStatus(mission);
        if (status == FireMissionStatus.APPROVED) {
            generateWaypointsFromFireEvent(mission, p, req);
            mission = findMission(no);
            status = parseMissionStatus(mission);
        }
        if (status == FireMissionStatus.ROUTE_GENERATED) {
            routeService.exportKmz(no, p.getOperatorId(), req.getRemoteAddr(), req.getHeader("X-Request-Id"));
            mission = findMission(no);
            status = parseMissionStatus(mission);
        }
        if (status == FireMissionStatus.ROUTE_EXPORTED) {
            return ApiResult.success(createDeliveryTaskFromLatestRoute(
                no, p.getOperatorId(), p.getTaskName(), p.getRemark(), p.getNotifies(), req));
        }
        if (status == FireMissionStatus.SENT_TO_DELIVERY && mission.getDjiTaskId() != null) {
            return ApiResult.success(createDeliveryTaskFromLatestRoute(
                no, p.getOperatorId(), p.getTaskName(), p.getRemark(), p.getNotifies(), req));
        }
        if (status == FireMissionStatus.IN_PROGRESS && mission.getDjiTaskId() != null) {
            ensureInProgressMissionCanReprepareDeliveryTask(mission);
            return ApiResult.success(createDeliveryTaskFromLatestRoute(
                no, p.getOperatorId(), p.getTaskName(), p.getRemark(), p.getNotifies(), req));
        }
        if (status == FireMissionStatus.PAYLOAD_RELEASED && mission.getDjiTaskId() != null) {
            return ApiResult.success(createDeliveryTaskFromLatestRoute(
                no, p.getOperatorId(), p.getTaskName(), p.getRemark(), p.getNotifies(), req));
        }
        throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
            "mission status " + mission.getStatus() + " cannot prepare FC100 delivery task");
    }

    @PostMapping("/missions/{no}/delivery/create-task")
    @Idempotent("delivery.create-task")
    public ApiResult<DeliveryTaskRef> createTask(@PathVariable("no") String no,
                                                  @Valid @RequestBody CreateTaskParam p,
                                                  HttpServletRequest req) {
        return ApiResult.success(createDeliveryTaskFromLatestRoute(
            no, p.getOperatorId(), p.getTaskName(), p.getRemark(), p.getNotifies(), req));
    }

    private DeliveryTaskRef createDeliveryTaskFromLatestRoute(String no,
                                                              String operatorId,
                                                              String taskName,
                                                              String remark,
                                                              List<String> notifies,
                                                              HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        RouteFileDTO file = routeService.getLatest(no);
        if (file == null) throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
            "no route file exported");

        String actualWaylineId = UUID.randomUUID().toString();
        String routeName = sanitizeFilename(firstText(taskName, "火情任务-" + no));
        String importRouteName = sanitizeFilename(routeName + "-" + actualWaylineId);
        byte[] routeKmz = normalizeUploadedKmzForFc100Import(routeService.downloadById(file.getId()), importRouteName);
        DeliveryWaylineImportResult importedWayline = importOrReuseWayline(
            new UploadedWaylineFile(routeKmz, importRouteName + ".kmz", "application/vnd.google-earth.kmz", importRouteName),
            actualWaylineId,
            null);
        String actualMissionId = firstText(importedWayline == null ? null : importedWayline.getWaylineId(), actualWaylineId);

        DeliveryTaskRef ref = adapter.createTask(CreateTaskRequest.builder()
            .workspaceId(m.getWorkspaceId())
            .deviceSn(m.getAircraftSn())
            .missionNo(no)
            .taskName(taskName != null ? taskName : "火情任务-" + no)
            .missionId(actualMissionId)
            .remark(remark)
            .notifies(notifies)
            .waylineKmzObjectKey(file.getObjectKey())
            .waylineKmzSha256(file.getSign())
            .build());

        sm.transit(TransitCommand.builder()
            .missionNo(no).event(FireMissionEvent.CREATE_DELIVERY_TASK)
            .operatorId(operatorId)
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());

        missionMapper.update(null, new UpdateWrapper<FireMissionEntity>()
            .eq("id", m.getId()).set("dji_task_id", ref.getTaskId()));

        return ref;
    }

    private void requireFireMissionDeliveryAutomationDependencies() {
        if (fireEventMapper == null || waypointPlannerService == null || safetyCheckService == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INTERNAL_ERROR,
                "fire mission delivery automation dependencies are not configured");
        }
    }

    private FireMissionStatus parseMissionStatus(FireMissionEntity mission) {
        try {
            return FireMissionStatus.valueOf(mission.getStatus());
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "invalid mission status: " + mission.getStatus());
        }
    }

    private void ensureInProgressMissionCanReprepareDeliveryTask(FireMissionEntity mission) {
        DeliveryTaskStatus status = adapter.queryTaskStatus(mission.getDjiTaskId());
        boolean taskEndedAbnormally = status != null
            && ("abnormal".equalsIgnoreCase(status.getPhase())
                || (status.getEndTime() != null && status.getEndTime() > 0
                    && status.getTaskCode() != null && status.getTaskCode() != 0)
                || "NOT_FOUND".equalsIgnoreCase(status.getStatus()));
        if (!taskEndedAbnormally) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "current delivery task is still active; cannot reprepare");
        }
        DeliveryDeviceProperties properties = adapter.getDeviceProperties(mission.getAircraftSn());
        if (properties != null && Boolean.TRUE.equals(properties.getFlying())) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "aircraft is still flying; cannot reprepare delivery task");
        }
    }

    private void generateWaypointsFromFireEvent(FireMissionEntity mission,
                                                PrepareFireMissionDeliveryTaskParam p,
                                                HttpServletRequest req) {
        validateMissionRouteInputs(mission);
        FireEventEntity event = fireEventMapper.selectById(mission.getFireEventId());
        if (event == null || event.getLat() == null || event.getLng() == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "fire event coordinate is required");
        }

        SafetyCheckResult sc = safetyCheckService.check(SafetyCheckPhase.BEFORE_ROUTE, mission.getMissionNo());
        if (!sc.isPassed()) {
            throw new Fc100BusinessException(Fc100ErrorCode.SAFETY_CHECK_FAILED,
                "safety check failed: " + sc.getIssues());
        }

        WaypointGenerateParam param = new WaypointGenerateParam();
        param.setOperatorId(p.getOperatorId());
        param.setTakeoffLat(mission.getTakeoffLat());
        param.setTakeoffLng(mission.getTakeoffLng());
        param.setTakeoffAlt(mission.getTakeoffAlt());
        param.setFireLat(event.getLat());
        param.setFireLng(event.getLng());
        param.setFireAlt(event.getAlt() != null ? event.getAlt() : 0.0);
        param.setWindSpeed(mission.getWindSpeedAtApproval());
        param.setWindDirectionDeg(mission.getWindDirectionDeg());
        param.setCruiseAlt(p.getCruiseAlt());
        param.setDropAltAgl(p.getDropAltAgl());
        param.setApproachDistance(p.getApproachDistance());
        param.setExitDistance(p.getExitDistance());
        param.setSpeed(p.getSpeed());

        List<MissionWaypointDTO> waypoints = waypointPlannerService.plan(param);
        waypointPlannerService.persistForMission(mission.getId(), waypoints);
        sm.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.GEN_WP)
            .operatorId(p.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());
    }

    private void validateMissionRouteInputs(FireMissionEntity mission) {
        if (mission.getAircraftSn() == null || mission.getAircraftSn().isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "mission aircraftSn is required");
        }
        if (mission.getTakeoffLat() == null || mission.getTakeoffLng() == null || mission.getTakeoffAlt() == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "mission takeoff coordinate is required");
        }
        if (mission.getWindSpeedAtApproval() == null || mission.getWindDirectionDeg() == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "mission wind parameter is required");
        }
    }

    @PostMapping("/missions/{no}/delivery/start-task")
    @Idempotent("delivery.start-task")
    public ApiResult<DeliveryTaskOperationResult> startTask(@PathVariable("no") String no,
                                      @Valid @RequestBody CreateTaskParam p,
                                      HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        if (m.getDjiTaskId() == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "delivery task not created");
        }
        DeliveryTaskOperationResult preflight = checkStartPreflight(m.getDjiTaskId(), m.getAircraftSn());
        if (preflight != null) {
            return ApiResult.success(preflight);
        }
        DeliveryTaskOperationResult result = adapter.startTask(m.getDjiTaskId());
        if (Boolean.FALSE.equals(result.getAccepted())) {
            return ApiResult.success(result);
        }

        sm.transit(TransitCommand.builder()
            .missionNo(no).event(FireMissionEvent.START_DELIVERY)
            .operatorId(p.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());
        return ApiResult.success(result);
    }

    @GetMapping("/missions/{no}/delivery/status")
    public ApiResult<DeliveryTaskStatus> status(@PathVariable("no") String no) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        if (m.getDjiTaskId() == null) return ApiResult.success(null);
        DeliveryTaskStatus status = adapter.queryTaskStatus(m.getDjiTaskId());
        autoReleaseHookWhenDeliveryReady(m, status);
        return ApiResult.success(status);
    }

    @Scheduled(initialDelayString = "${fc100.delivery-sync.auto-release-initial-delay-ms:5000}",
               fixedDelayString = "${fc100.delivery-sync.auto-release-poll-ms:5000}")
    public void pollInProgressMissionsForAutoRelease() {
        List<FireMissionEntity> missions = missionMapper.selectList(
            new QueryWrapper<FireMissionEntity>()
                .eq("deleted", 0)
                .eq("status", FireMissionStatus.IN_PROGRESS.name())
                .isNotNull("dji_task_id")
                .last("limit 20"));
        for (FireMissionEntity mission : missions) {
            if (mission.getDjiTaskId() == null || mission.getDjiTaskId().isBlank()) {
                continue;
            }
            try {
                DeliveryTaskStatus status = adapter.queryTaskStatus(mission.getDjiTaskId());
                autoReleaseHookWhenDeliveryReady(mission, status);
            } catch (Exception e) {
                log.warn("FC100 auto release polling failed mission={} task={}: {}",
                    mission.getMissionNo(), mission.getDjiTaskId(), e.getMessage());
            }
        }
    }

    private void autoReleaseHookWhenDeliveryReady(FireMissionEntity mission, DeliveryTaskStatus status) {
        if (mission == null || status == null) return;
        if (!FireMissionStatus.IN_PROGRESS.name().equals(mission.getStatus())) return;
        if (Boolean.FALSE.equals(status.getAccepted())) return;
        if (!"completed".equalsIgnoreCase(status.getPhase()) && !isAtDropPointAndHovering(mission)) return;

        DeviceCommandParam command = new DeviceCommandParam();
        command.setOperatorId("system-auto-release");
        command.setData(Map.of("mode", 1));
        sendMissionCommand(mission.getMissionNo(), "hoist_hook_control", command);

        sm.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.MARK_RELEASE_PENDING)
            .operatorId("system-auto-release")
            .build());
        sm.transit(TransitCommand.builder()
            .missionNo(mission.getMissionNo())
            .event(FireMissionEvent.CONFIRM_RELEASE)
            .operatorId("system-auto-release")
            .build());
    }

    private boolean isAtDropPointAndHovering(FireMissionEntity mission) {
        if (mission == null || waypointPlannerService == null || mission.getAircraftSn() == null
            || mission.getAircraftSn().isBlank()) {
            return false;
        }
        MissionWaypointDTO drop = waypointPlannerService.listLatest(mission.getId()).stream()
            .filter(wp -> "DROP".equals(wp.getWaypointType()))
            .findFirst()
            .orElse(null);
        if (drop == null || drop.getLat() == null || drop.getLng() == null) {
            return false;
        }
        DeliveryDeviceProperties properties = adapter.getDeviceProperties(mission.getAircraftSn());
        if (properties == null || properties.getLatitude() == null || properties.getLongitude() == null) {
            return false;
        }
        double distance = distanceMeters(properties.getLatitude(), properties.getLongitude(), drop.getLat(), drop.getLng());
        double horizontalSpeed = properties.getHorizontalSpeed() == null ? 0.0 : Math.abs(properties.getHorizontalSpeed());
        double verticalSpeed = properties.getVerticalSpeed() == null ? 0.0 : Math.abs(properties.getVerticalSpeed());
        boolean hovering = horizontalSpeed <= AUTO_RELEASE_HORIZONTAL_SPEED_MPS
            && verticalSpeed <= AUTO_RELEASE_VERTICAL_SPEED_MPS;
        if (distance <= AUTO_RELEASE_DROP_RADIUS_M && hovering) {
            log.info("FC100 auto release at DROP waypoint mission={} distance={}m hSpeed={} vSpeed={}",
                mission.getMissionNo(), distance, horizontalSpeed, verticalSpeed);
            return true;
        }
        return false;
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Data
    public static class DeviceCommandParam {
        @NotBlank private String operatorId;
        private Map<String, Object> data;
    }

    @PostMapping("/missions/{no}/delivery/emergency-stop")
    @Idempotent("delivery.command.emergency-stop")
    public ApiResult<DeliveryCommandRef> emergencyStop(@PathVariable("no") String no,
                                                        @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendMissionCommand(no, "drone_emergency_stop", p));
    }

    @PostMapping("/missions/{no}/delivery/return-home")
    @Idempotent("delivery.command.return-home")
    public ApiResult<DeliveryCommandRef> returnHome(@PathVariable("no") String no,
                                                     @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendMissionCommand(no, "return_home", p));
    }

    @PostMapping("/missions/{no}/delivery/land")
    @Idempotent("delivery.command.land")
    public ApiResult<DeliveryCommandRef> land(@PathVariable("no") String no,
                                               @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendMissionCommand(no, "drone_landing", p));
    }

    @PostMapping("/missions/{no}/delivery/release-hook")
    @Idempotent("delivery.command.release-hook")
    public ApiResult<DeliveryCommandRef> releaseHook(@PathVariable("no") String no,
                                                      @Valid @RequestBody DeviceCommandParam p) {
        DeviceCommandParam command = new DeviceCommandParam();
        command.setOperatorId(p.getOperatorId());
        command.setData(Map.of("mode", 1));
        return ApiResult.success(sendMissionCommand(no, "hoist_hook_control", command));
    }

    @PostMapping("/missions/{no}/delivery/commands/{method}")
    @Idempotent("delivery.command.custom")
    public ApiResult<DeliveryCommandRef> command(@PathVariable("no") String no,
                                                  @PathVariable("method") String method,
                                                  @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendMissionCommand(no, method, p));
    }

    @GetMapping("/missions/{no}/delivery/commands/status")
    public ApiResult<DeliveryCommandStatus> commandStatus(@PathVariable("no") String no) {
        FireMissionEntity m = findMission(no);
        return ApiResult.success(adapter.queryDeviceCommandStatus(m.getAircraftSn()));
    }

    @PostMapping("/delivery/devices/{sn}/emergency-stop")
    @Idempotent("delivery.device-command.emergency-stop")
    public ApiResult<DeliveryCommandRef> directEmergencyStop(@PathVariable("sn") String sn,
                                                              @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendDeviceCommand(sn, "drone_emergency_stop", p));
    }

    @PostMapping("/delivery/devices/{sn}/return-home")
    @Idempotent("delivery.device-command.return-home")
    public ApiResult<DeliveryCommandRef> directReturnHome(@PathVariable("sn") String sn,
                                                           @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendDeviceCommand(sn, "return_home", p));
    }

    @PostMapping("/delivery/devices/{sn}/land")
    @Idempotent("delivery.device-command.land")
    public ApiResult<DeliveryCommandRef> directLand(@PathVariable("sn") String sn,
                                                     @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendDeviceCommand(sn, "drone_landing", p));
    }

    @PostMapping("/delivery/devices/{sn}/commands/{method}")
    @Idempotent("delivery.device-command.custom")
    public ApiResult<DeliveryCommandRef> directCommand(@PathVariable("sn") String sn,
                                                        @PathVariable("method") String method,
                                                        @Valid @RequestBody DeviceCommandParam p) {
        return ApiResult.success(sendDeviceCommand(sn, method, p));
    }

    @GetMapping("/delivery/devices/{sn}/commands/status")
    public ApiResult<DeliveryCommandStatus> directCommandStatus(@PathVariable("sn") String sn) {
        return ApiResult.success(adapter.queryDeviceCommandStatus(sn));
    }

    private DeliveryCommandRef sendMissionCommand(String no, String method, DeviceCommandParam p) {
        FireMissionEntity m = findMission(no);
        return sendDeviceCommand(m.getAircraftSn(), method, p, no);
    }

    private DeliveryCommandRef sendDeviceCommand(String deviceSn, String method, DeviceCommandParam p) {
        return sendDeviceCommand(deviceSn, method, p, "direct-" + deviceSn);
    }

    private DeliveryCommandRef sendDeviceCommand(String deviceSn, String method, DeviceCommandParam p, String missionNo) {
        return adapter.sendDeviceCommand(DeviceCommandRequest.builder()
            .missionNo(missionNo)
            .deviceSn(deviceSn)
            .deviceCmdMethod(method)
            .deviceCmdData(p.getData() != null ? p.getData() : Map.of())
            .build());
    }

    private DeliveryTaskOperationResult checkStartPreflight(String taskId, String deviceSn) {
        if (deviceSn == null || deviceSn.isBlank()) {
            return null;
        }
        DeliveryDeviceProperties properties = adapter.getDeviceProperties(deviceSn.trim());
        List<String> failures = buildStartPreflightFailures(properties);
        if (failures.isEmpty()) {
            return null;
        }
        return DeliveryTaskOperationResult.builder()
            .operation("preflight")
            .taskId(taskId)
            .deviceSn(deviceSn.trim())
            .accepted(false)
            .status("preflight")
            .apiCode(0)
            .apiMessage("PRE_FLIGHT_CHECK_FAILED")
            .displayMessage("FC100 开始执行航线前检查未通过：" + String.join("；", failures))
            .build();
    }

    private List<String> buildStartPreflightFailures(DeliveryDeviceProperties properties) {
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        if (properties == null) {
            failures.add("未获取到飞行器状态");
            return failures;
        }
        if (Boolean.FALSE.equals(properties.getOnlineStatus())) {
            failures.add("飞行器离线");
        }
        if (properties.getBatteryPercent() != null && properties.getBatteryPercent() < 30) {
            failures.add("电量低于 30%");
        }
        if (properties.getRtkStatus() == null || properties.getRtkStatus().isBlank()) {
            failures.add("RTK/GPS 状态未知");
        }
        if (properties.getLatitude() == null || properties.getLongitude() == null) {
            failures.add("未获取到经纬度");
        }
        return failures;
    }

    private FireMissionEntity findMission(String no) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        return m;
    }

    private String extractTemplateKml(byte[] kmz) {
        return new String(extractTemplateKmlBytes(kmz), StandardCharsets.UTF_8);
    }

    private byte[] extractTemplateKmlBytes(byte[] kmz) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("wpmz/template.kml".equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) >= 0) {
                        out.write(buf, 0, len);
                    }
                    return out.toByteArray();
                }
            }
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "template.kml not found in KMZ");
        } catch (Fc100BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "invalid KMZ: " + e.getMessage());
        }
    }

    private UploadedWaylineFile readUploadedWaylineFile(MultipartFile file, String actualWaylineId) {
        if (file == null || file.isEmpty()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "wayline file is required");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "wayline.kmz";
        String lowerName = filename.toLowerCase();
        String waylineName = filenameWithoutExtension(filename);
        String importRouteName = sanitizeFilename(waylineName + "-" + actualWaylineId);
        try {
            byte[] bytes = file.getBytes();
            if (lowerName.endsWith(".kmz")) {
                bytes = normalizeUploadedKmzForFc100Import(bytes, importRouteName);
                String contentType = file.getContentType() != null ? file.getContentType() : "application/vnd.google-earth.kmz";
                return new UploadedWaylineFile(bytes, importRouteName + ".kmz", contentType, importRouteName);
            }
            if (lowerName.endsWith(".kml")) {
                String kml = new String(bytes, StandardCharsets.UTF_8);
                if (!kml.contains("<kml")) {
                    throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "invalid KML file");
                }
                String normalized = normalizeFc100FinishAction(kml);
                normalized = normalizeFc100WaylineName(normalized, importRouteName);
                bytes = normalized.getBytes(StandardCharsets.UTF_8);
                String contentType = file.getContentType() != null ? file.getContentType() : "application/vnd.google-earth.kml+xml";
                return new UploadedWaylineFile(bytes, importRouteName + ".kml", contentType, importRouteName);
            }
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "only KMZ/KML wayline files are supported");
        } catch (Fc100BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "invalid wayline file: " + e.getMessage());
        }
    }

    private DeliveryWaylineImportResult importOrReuseWayline(UploadedWaylineFile routeFile,
                                                             String actualWaylineId,
                                                             String importWaylineId) {
        try {
            return adapter.importWayline(WaylineImportRequest.builder()
                .missionNo(actualWaylineId)
                .waylineId(importWaylineId)
                .fileBytes(routeFile.bytes)
                .filename(routeFile.filename)
                .contentType(routeFile.contentType)
                .build());
        } catch (Fc100BusinessException e) {
            if (!isDuplicateWaylineNameError(e)) {
                throw e;
            }
            return findExistingWaylineByName(routeFile.waylineName);
        }
    }

    private DeliveryWaylineImportResult findExistingWaylineByName(String waylineName) {
        DeliveryWaylineDTO matched = findExistingWaylineDtoByName(waylineName)
            .orElseThrow(() -> new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_BUSINESS,
                "航线名称重复，但未能从司运航线列表找到同名航线：" + waylineName + "；请从 FC100 已有航线中选择后创建任务"));
        return DeliveryWaylineImportResult.builder()
            .waylineId(matched.getWaylineId())
            .name(matched.getName())
            .build();
    }

    private DeliveryWaylineImportResult findExistingWaylineByNameOrNull(String waylineName) {
        try {
            return findExistingWaylineDtoByName(waylineName)
                .map(matched -> DeliveryWaylineImportResult.builder()
                    .waylineId(matched.getWaylineId())
                    .name(matched.getName())
                    .build())
                .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private java.util.Optional<DeliveryWaylineDTO> findExistingWaylineDtoByName(String waylineName) {
        List<DeliveryWaylineDTO> waylines = adapter.listWaylines(1, 100, waylineName);
        return waylines.stream()
            .filter(item -> waylineName.equals(item.getName()))
            .findFirst();
    }

    private boolean isDuplicateWaylineNameError(Fc100BusinessException e) {
        String message = e.getMessage();
        return message != null && (message.contains("203541") || message.contains("航线名称重复"));
    }

    private String filenameWithoutExtension(String filename) {
        String safe = filename == null || filename.isBlank() ? "wayline" : filename.replace('\\', '/');
        int slash = safe.lastIndexOf('/');
        String base = slash >= 0 ? safe.substring(slash + 1) : safe;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private String sanitizeFilename(String filename) {
        return firstText(filename, "wayline")
            .replaceAll("[<>:\"/\\\\|?*]+", "-")
            .replaceAll("\\s+", " ")
            .replaceAll("^-+|-+$", "")
            .trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static class UploadedWaylineFile {
        private final byte[] bytes;
        private final String filename;
        private final String contentType;
        private final String waylineName;

        private UploadedWaylineFile(byte[] bytes, String filename, String contentType, String waylineName) {
            this.bytes = bytes;
            this.filename = filename;
            this.contentType = contentType;
            this.waylineName = waylineName;
        }
    }
}
