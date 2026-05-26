package com.yx.uavfire.fc100.deliverysync.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import lombok.Data;
import org.springframework.http.MediaType;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** spec §4.5 — 5 个 Delivery 相关端点（spec API 路径已扁平到 /api/fire/...） */
@RestController
@RequestMapping("/api/fire")
public class DeliveryController {

    private final DeliverySyncAdapter adapter;
    private final DeliverySyncProperties props;
    private final FireMissionMapper missionMapper;
    private final RouteExportService routeService;
    private final MissionStateMachine sm;

    public DeliveryController(DeliverySyncAdapter a, DeliverySyncProperties p,
                               FireMissionMapper m, RouteExportService r, MissionStateMachine sm) {
        this.adapter = a; this.props = p; this.missionMapper = m;
        this.routeService = r; this.sm = sm;
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

    @GetMapping("/delivery/waylines")
    public ApiResult<List<DeliveryWaylineDTO>> waylines(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "key", required = false) String key) {
        return ApiResult.success(adapter.listWaylines(page, pageSize, key));
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
        UploadedWaylineFile routeFile = readUploadedWaylineFile(file);

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

    @PostMapping("/missions/{no}/delivery/create-task")
    @Idempotent("delivery.create-task")
    public ApiResult<DeliveryTaskRef> createTask(@PathVariable("no") String no,
                                                  @Valid @RequestBody CreateTaskParam p,
                                                  HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        RouteFileDTO file = routeService.getLatest(no);
        if (file == null) throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
            "no route file exported");

        byte[] routeKml = extractTemplateKmlBytes(routeService.downloadById(file.getId()));
        DeliveryWaylineImportResult importedWayline = adapter.importWayline(WaylineImportRequest.builder()
            .missionNo(no)
            .waylineId(no)
            .fileBytes(routeKml)
            .filename("template.kml")
                .contentType("application/vnd.google-earth.kml+xml")
                .build());
        String actualMissionId = firstText(importedWayline == null ? null : importedWayline.getWaylineId(), no);

        DeliveryTaskRef ref = adapter.createTask(CreateTaskRequest.builder()
            .workspaceId(m.getWorkspaceId())
            .deviceSn(m.getAircraftSn())
            .missionNo(no)
            .taskName(p.getTaskName() != null ? p.getTaskName() : "火情任务-" + no)
            .missionId(actualMissionId)
            .remark(p.getRemark())
            .notifies(p.getNotifies())
            .waylineKmzObjectKey(file.getObjectKey())
            .waylineKmzSha256(file.getSign())
            .build());

        sm.transit(TransitCommand.builder()
            .missionNo(no).event(FireMissionEvent.CREATE_DELIVERY_TASK)
            .operatorId(p.getOperatorId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());

        missionMapper.update(null, new UpdateWrapper<FireMissionEntity>()
            .eq("id", m.getId()).set("dji_task_id", ref.getTaskId()));

        return ApiResult.success(ref);
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
        return ApiResult.success(adapter.queryTaskStatus(m.getDjiTaskId()));
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

    private UploadedWaylineFile readUploadedWaylineFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "wayline file is required");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "wayline.kmz";
        String lowerName = filename.toLowerCase();
        String waylineName = filenameWithoutExtension(filename);
        try {
            byte[] bytes = file.getBytes();
            if (lowerName.endsWith(".kmz")) {
                String contentType = file.getContentType() != null ? file.getContentType() : "application/vnd.google-earth.kmz";
                return new UploadedWaylineFile(bytes, filename, contentType, waylineName);
            }
            if (lowerName.endsWith(".kml")) {
                String kml = new String(bytes, StandardCharsets.UTF_8);
                if (!kml.contains("<kml")) {
                    throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "invalid KML file");
                }
                String contentType = file.getContentType() != null ? file.getContentType() : "application/vnd.google-earth.kml+xml";
                return new UploadedWaylineFile(bytes, filename, contentType, waylineName);
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
        List<DeliveryWaylineDTO> waylines = adapter.listWaylines(1, 100, waylineName);
        DeliveryWaylineDTO matched = waylines.stream()
            .filter(item -> waylineName.equals(item.getName()))
            .findFirst()
            .orElseThrow(() -> new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_BUSINESS,
                "航线名称重复，但未能从司运航线列表找到同名航线：" + waylineName + "；请从 FC100 已有航线中选择后创建任务"));
        return DeliveryWaylineImportResult.builder()
            .waylineId(matched.getWaylineId())
            .name(matched.getName())
            .build();
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
