package com.yx.uavfire.fc100.deliverysync.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncException;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.http.DeliverySyncHttpClient;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryBypassStreamDTO;
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
import com.yx.uavfire.fc100.deliverysync.model.param.DeliveryBypassStreamRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "fc100.delivery-sync", name = "mode", havingValue = "http")
@Slf4j
public class HttpDeliverySyncAdapter implements DeliverySyncAdapter {

    private final DeliverySyncHttpClient httpClient;
    private final DeliverySyncProperties props;

    public HttpDeliverySyncAdapter(DeliverySyncHttpClient httpClient, DeliverySyncProperties props) {
        this.httpClient = httpClient;
        this.props = props;
    }

    @Override
    public List<DeliveryDeviceDTO> listDevices(String workspaceId) {
        try {
            DjiDeviceListResponse resp = httpClient.get(groupPath("/manage/sdk/v1/groups/%s/devices"),
                null, DjiDeviceListResponse.class, null, null);
            return ResponseMapper.toDeviceList(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "listDevices");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryDeviceProperties getDeviceProperties(String deviceSn) {
        try {
            DjiDevicePropertiesResponse resp = httpClient.get(
                groupPath("/manage/sdk/v1/groups/%s/devices/properties"),
                Map.of("sn", deviceSn), DjiDevicePropertiesResponse.class, null, null);
            return ResponseMapper.toDeviceProperties(deviceSn, resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "getDeviceProperties");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryBypassStreamDTO startBypassStream(DeliveryBypassStreamRequest req) {
        String idemKey = UUID.randomUUID().toString();
        DjiCreatePassStreamRequest body = new DjiCreatePassStreamRequest();
        body.setRegion(req.getRegion());
        body.setRtmpUrl(req.getRtmpUrl());
        body.setSn(req.getDeviceSn());
        body.setCamera(req.getCamera());
        body.setVideo(req.getVideo());
        body.setExpireTs(req.getExpireTs());
        body.setVideoQuality(req.getVideoQuality());
        try {
            DjiPassStreamResponse resp = httpClient.post(groupPath("/manage/sdk/v1/groups/%s/bypass/streams/start"),
                body, DjiPassStreamResponse.class, idemKey, req.getDeviceSn());
            return ResponseMapper.toBypassStream(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "startBypassStream");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public List<DeliveryWaylineDTO> listWaylines(int page, int pageSize, String key) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("page_size", String.valueOf(Math.max(1, pageSize)));
        if (key != null && !key.isBlank()) {
            query.put("key", key.trim());
        }
        try {
            DjiWaylineListResponse resp = httpClient.get(groupPath("/map/sdk/v1/groups/%s/waylines"),
                query, DjiWaylineListResponse.class, null, null);
            return ResponseMapper.toWaylineList(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "listWaylines");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryWaylineImportResult importWayline(WaylineImportRequest req) {
        String idemKey = UUID.randomUUID().toString();
        Map<String, String> form = new LinkedHashMap<>();
        if (req.getWaylineId() != null && !req.getWaylineId().isBlank()) {
            form.put("wayline_id", req.getWaylineId());
        }
        try {
            DjiImportWaylineResponse resp = httpClient.postMultipartFile(groupPath("/map/sdk/v1/groups/%s/waylines/kml/import"),
                "file", req.getFilename(), req.getFileBytes(), req.getContentType(), form,
                DjiImportWaylineResponse.class, idemKey, req.getMissionNo());
            return ResponseMapper.toWaylineImportResult(resp, req.getWaylineId(), req.getMissionNo());
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "importWayline");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryTaskRef createTask(CreateTaskRequest req) {
        String idemKey = UUID.randomUUID().toString();
        DjiCreateTaskRequest body = new DjiCreateTaskRequest();
        body.setTaskName(req.getTaskName() != null ? req.getTaskName() : "火情任务-" + req.getMissionNo());
        body.setDeviceSn(req.getDeviceSn());
        body.setMissionId(req.getMissionId() != null ? req.getMissionId() : req.getMissionNo());
        body.setRemark(req.getRemark());
        body.setNotifies(req.getNotifies() != null ? req.getNotifies() : List.of());
        try {
            DjiCreateTaskResponse resp = httpClient.post(groupPath("/task/sdk/v1/groups/%s/tasks"),
                body, DjiCreateTaskResponse.class, idemKey, req.getMissionNo());
            return ResponseMapper.toTaskRef(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "createTask");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryTaskOperationResult startTask(String taskId) {
        try {
            DjiStandardResponse resp = httpClient.post(groupPath("/task/sdk/v1/groups/%s/tasks/" + taskId + "/start"),
                Map.of("ignore_radar_detection", "false"), null, DjiStandardResponse.class, null, null);
            return ResponseMapper.toStartTaskResult(taskId, resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "startTask");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryTaskStatus queryTaskStatus(String taskId) {
        long now = System.currentTimeMillis();
        Map<String, String> query = Map.of(
            "start_time", String.valueOf(now - 7L * 24 * 60 * 60 * 1000),
            "end_time", String.valueOf(now + 60_000),
            "page", "1",
            "page_size", "100");
        try {
            DjiTaskListResponse resp = httpClient.get(groupPath("/task/sdk/v1/groups/%s/tasks"),
                query, DjiTaskListResponse.class, null, null);
            return ResponseMapper.toTaskStatus(taskId, resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "queryTaskStatus");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryCommandRef sendDeviceCommand(DeviceCommandRequest req) {
        String idemKey = UUID.randomUUID().toString();
        DjiCreateCmdRequest body = new DjiCreateCmdRequest();
        body.setDeviceSn(req.getDeviceSn());
        body.setDeviceCmdMethod(req.getDeviceCmdMethod());
        body.setDeviceCmdData(req.getDeviceCmdData() != null ? req.getDeviceCmdData() : Map.of());
        try {
            DjiCreateCmdResponse resp = httpClient.post(groupPath("/task/sdk/v1/groups/%s/cmds"),
                body, DjiCreateCmdResponse.class, idemKey, req.getMissionNo());
            return ResponseMapper.toCommandRef(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "sendDeviceCommand");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryCommandStatus queryDeviceCommandStatus(String deviceSn) {
        try {
            DjiCommandStatusResponse resp = httpClient.get(groupPath("/task/sdk/v1/groups/%s/cmds"),
                Map.of("device_sn", deviceSn), DjiCommandStatusResponse.class, null, null);
            return ResponseMapper.toCommandStatus(deviceSn, resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "queryDeviceCommandStatus");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    private String groupPath(String template) {
        if (props.getGroupId() == null || props.getGroupId().isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "fc100.delivery-sync.group-id is required");
        }
        return String.format(template, props.getGroupId());
    }

    private Fc100BusinessException toBusinessException(DeliverySyncException e, String apiName) {
        log.warn("DeliverySync {} error: HTTP {} body={}", apiName, e.getHttpCode(), e.getBody());
        if (e.getHttpCode() == 401 || e.getHttpCode() == 403) {
            return new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_AUTH,
                apiName + " auth failed: " + e.getBody());
        }
        return new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_BUSINESS,
            apiName + " failed HTTP " + e.getHttpCode() + ": " + e.getBody());
    }

    @Data
    static class DjiCreateTaskRequest {
        @JsonProperty("task_name")
        private String taskName;
        @JsonProperty("device_sn")
        private String deviceSn;
        @JsonProperty("mission_id")
        private String missionId;
        @JsonProperty("remark")
        private String remark;
        @JsonProperty("notifies")
        private List<String> notifies;
    }

    @Data
    static class DjiCreateCmdRequest {
        @JsonProperty("device_sn")
        private String deviceSn;
        @JsonProperty("device_cmd_method")
        private String deviceCmdMethod;
        @JsonProperty("device_cmd_data")
        private Map<String, Object> deviceCmdData;
    }

    @Data
    static class DjiCreatePassStreamRequest {
        @JsonProperty("region")
        private String region;
        @JsonProperty("rtmp_url")
        private String rtmpUrl;
        @JsonProperty("sn")
        private String sn;
        @JsonProperty("camera")
        private String camera;
        @JsonProperty("video")
        private String video;
        @JsonProperty("expire_ts")
        private Long expireTs;
        @JsonProperty("video_quality")
        private Integer videoQuality;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiStandardResponse {
        @JsonProperty("code")
        private Integer code;
        @JsonProperty("message")
        private String message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiImportWaylineResponse {
        @JsonProperty("code")
        private Integer code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private DjiImportWaylineData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiImportWaylineData {
            @JsonProperty("key")
            private String key;
            @JsonProperty("name")
            private String name;
            @JsonProperty("uuid")
            private String uuid;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiDeviceListResponse {
        @JsonProperty("data")
        private DjiDeviceListData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiDeviceListData {
            @JsonProperty("list")
            private DjiDeviceItem[] list;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiDeviceItem {
            @JsonProperty("sn")
            private String sn;
            @JsonProperty("device_model_key")
            private String deviceModelKey;
            @JsonProperty("device_model_class")
            private String deviceModelClass;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiDevicePropertiesResponse {
        @JsonProperty("data")
        private DjiDevicePropertiesData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiDevicePropertiesData {
            @JsonProperty("list")
            private DjiDevicePropertiesItem[] list;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiDevicePropertiesItem {
            @JsonProperty("sn")
            private String sn;
            @JsonProperty("updated_time")
            private Long updatedTime;
            @JsonProperty("online_status")
            private Boolean onlineStatus;
            @JsonProperty("properties")
            private Map<String, Object> properties;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiWaylineListResponse {
        @JsonProperty("data")
        private DjiWaylineListData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiWaylineListData {
            @JsonProperty("list")
            private DjiWaylineItem[] list;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiWaylineItem {
            @JsonProperty("wayline_id")
            private String waylineId;
            @JsonProperty("wayline_type")
            private String waylineType;
            @JsonProperty("name")
            private String name;
            @JsonProperty("distance")
            private Double distance;
            @JsonProperty("duration")
            private Integer duration;
            @JsonProperty("fly_to_wayline_mode")
            private String flyToWaylineMode;
            @JsonProperty("finish_action")
            private String finishAction;
            @JsonProperty("rc_lost_action")
            private String rcLostAction;
            @JsonProperty("turn_mode")
            private String turnMode;
            @JsonProperty("fingerprint")
            private String fingerprint;
            @JsonProperty("create_time")
            private Long createTime;
            @JsonProperty("update_time")
            private Long updateTime;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiCreateTaskResponse {
        @JsonProperty("code")
        private Integer code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private DjiCreateTaskData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiCreateTaskData {
            @JsonProperty("id")
            private String id;
            @JsonProperty("device_sn")
            private String deviceSn;
            @JsonProperty("mission_id")
            private String missionId;
            @JsonProperty("task_name")
            private String taskName;
            @JsonProperty("status")
            private Integer status;
            @JsonProperty("reason")
            private String reason;
            @JsonProperty("update_time")
            private Long updateTime;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiTaskListResponse {
        @JsonProperty("code")
        private Integer code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private DjiTaskListData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiTaskListData {
            @JsonProperty("list")
            private DjiTaskItem[] list;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiTaskItem {
            @JsonProperty("id")
            private String id;
            @JsonProperty("device_sn")
            private String deviceSn;
            @JsonProperty("mission_id")
            private String missionId;
            @JsonProperty("task_name")
            private String taskName;
            @JsonProperty("status")
            private Integer status;
            @JsonProperty("code")
            private Integer code;
            @JsonProperty("reason")
            private String reason;
            @JsonProperty("update_time")
            private Long updateTime;
            @JsonProperty("start_time")
            private Long startTime;
            @JsonProperty("end_time")
            private Long endTime;
            @JsonProperty("estimate_time")
            private Integer estimateTime;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiCreateCmdResponse {
        @JsonProperty("data")
        private DjiCreateCmdData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiCreateCmdData {
            @JsonProperty("code")
            private Integer code;
            @JsonProperty("bid")
            private String bid;
            @JsonProperty("gateway_sn")
            private String gatewaySn;
            @JsonProperty("device_sn")
            private String deviceSn;
            @JsonProperty("device_cmd_method")
            private String deviceCmdMethod;
            @JsonProperty("status")
            private String status;
            @JsonProperty("device_cmd_data")
            private Map<String, Object> deviceCmdData;
            @JsonProperty("create_time")
            private Long createTime;
            @JsonProperty("update_time")
            private Long updateTime;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiCommandStatusResponse {
        @JsonProperty("data")
        private DjiCommandStatusData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiCommandStatusData {
            @JsonProperty("device_sn")
            private String deviceSn;
            @JsonProperty("services")
            private Map<String, Object> services;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiPassStreamResponse {
        @JsonProperty("code")
        private Integer code;
        @JsonProperty("message")
        private String message;
        @JsonProperty("data")
        private DjiPassStreamData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiPassStreamData {
            @JsonProperty("converter_id")
            private String converterId;
            @JsonProperty("play_rtmp_url")
            private String playRtmpUrl;
            @JsonProperty("create_ts")
            private Long createTs;
            @JsonProperty("update_ts")
            private Long updateTs;
            @JsonProperty("converter_state")
            private String converterState;
        }
    }

    static final class ResponseMapper {

        private ResponseMapper() {}

        static List<DeliveryDeviceDTO> toDeviceList(DjiDeviceListResponse resp) {
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) return List.of();
            return Arrays.stream(resp.getData().getList())
                .map(item -> {
                    String model = displayDeviceModel(item.getDeviceModelKey(), item.getDeviceModelClass());
                    return new DeliveryDeviceDTO(
                        item.getSn(),
                        item.getDeviceModelKey(),
                        null,
                        item.getDeviceModelClass(),
                        model,
                        model,
                        item.getDeviceModelKey(),
                        item.getDeviceModelClass());
                })
                .collect(java.util.stream.Collectors.toList());
        }

        private static String displayDeviceModel(String modelKey, String modelClass) {
            String raw = ((modelKey == null ? "" : modelKey) + " " + (modelClass == null ? "" : modelClass)).toLowerCase();
            if (raw.contains("0-122-0") || raw.contains("fc100") || raw.contains("flycart")) {
                return "DJI Flycart100";
            }
            if (raw.contains("fc30")) {
                return "DJI FlyCart 30";
            }
            String fallback = modelKey != null && !modelKey.isBlank() ? modelKey : modelClass;
            return fallback != null && !fallback.isBlank() ? fallback : "DJI Flycart100";
        }

        static DeliveryDeviceProperties toDeviceProperties(String deviceSn, DjiDevicePropertiesResponse resp) {
            DeliveryDeviceProperties p = new DeliveryDeviceProperties();
            p.setDeviceSn(deviceSn);
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) return p;
            Arrays.stream(resp.getData().getList())
                .filter(item -> deviceSn.equals(item.getSn()))
                .findFirst()
                .ifPresent(item -> {
                    p.setOnlineStatus(item.getOnlineStatus());
                    p.setOsdTimestamp(item.getUpdatedTime());
                    Map<String, Object> properties = item.getProperties();
                    Map<String, Object> battery = asMap(propertyValue(first(properties, "battery")));
                    p.setBatteryPercent(asInt(first(battery, "capacity_percent", "battery_percent", "capacity")));
                    if (p.getBatteryPercent() == null) {
                        p.setBatteryPercent(asInt(propertyValue(first(properties, "battery_percent", "capacity_percent"))));
                    }
                    p.setRtkStatus(formatPositionState(first(properties, "position_state", "rtk_status", "rtk")));
                    p.setLatitude(asDouble(propertyValue(first(properties, "latitude", "lat"))));
                    p.setLongitude(asDouble(propertyValue(first(properties, "longitude", "lng"))));
                    p.setAltitude(asDouble(propertyValue(first(properties, "altitude", "height"))));
                    p.setAircraftMode(asInt(propertyValue(first(properties, "aircraft_mode"))));
                    p.setFlying(asBoolean(propertyValue(first(properties, "is_flying"))));
                    p.setHorizontalSpeed(asDouble(propertyValue(first(properties, "horizontal_speed"))));
                    p.setVerticalSpeed(asDouble(propertyValue(first(properties, "vertical_speed"))));
                    p.setHomeDistance(asDouble(propertyValue(first(properties, "home_distance"))));
                    p.setWindSpeed(asDouble(propertyValue(first(properties, "wind_speed"))));
                });
            return p;
        }

        static DeliveryBypassStreamDTO toBypassStream(DjiPassStreamResponse resp) {
            if (resp == null || resp.getData() == null) {
                return DeliveryBypassStreamDTO.builder().build();
            }
            DjiPassStreamResponse.DjiPassStreamData data = resp.getData();
            return DeliveryBypassStreamDTO.builder()
                .converterId(data.getConverterId())
                .playRtmpUrl(data.getPlayRtmpUrl())
                .converterState(data.getConverterState())
                .createTs(data.getCreateTs())
                .updateTs(data.getUpdateTs())
                .build();
        }

        static List<DeliveryWaylineDTO> toWaylineList(DjiWaylineListResponse resp) {
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) return List.of();
            return Arrays.stream(resp.getData().getList())
                .map(item -> DeliveryWaylineDTO.builder()
                    .waylineId(item.getWaylineId())
                    .waylineType(item.getWaylineType())
                    .name(item.getName())
                    .distance(item.getDistance())
                    .duration(item.getDuration())
                    .flyToWaylineMode(item.getFlyToWaylineMode())
                    .finishAction(item.getFinishAction())
                    .rcLostAction(item.getRcLostAction())
                    .turnMode(item.getTurnMode())
                    .fingerprint(item.getFingerprint())
                    .createTime(item.getCreateTime())
                    .updateTime(item.getUpdateTime())
                    .build())
                .collect(java.util.stream.Collectors.toList());
        }

        static DeliveryWaylineImportResult toWaylineImportResult(DjiImportWaylineResponse resp,
                                                                 String fallbackWaylineId,
                                                                 String fallbackName) {
            if (resp == null || resp.getData() == null) {
                return DeliveryWaylineImportResult.builder()
                    .waylineId(textOr(fallbackWaylineId, fallbackName))
                    .name(fallbackName)
                    .build();
            }
            DjiImportWaylineResponse.DjiImportWaylineData data = resp.getData();
            return DeliveryWaylineImportResult.builder()
                .waylineId(textOr(data.getUuid(), textOr(fallbackWaylineId, fallbackName)))
                .name(textOr(data.getName(), fallbackName))
                .key(data.getKey())
                .build();
        }

        static DeliveryTaskRef toTaskRef(DjiCreateTaskResponse resp) {
            Integer apiCode = resp == null ? null : resp.getCode();
            String apiMessage = resp == null ? null : resp.getMessage();
            if (resp == null || resp.getData() == null) {
                DeliveryTaskRef ref = new DeliveryTaskRef(null, "UNKNOWN");
                ref.setAccepted(false);
                ref.setApiCode(apiCode);
                ref.setApiMessage(apiMessage);
                ref.setDisplayMessage(textOr(apiMessage, "FC100 创建任务未返回任务数据"));
                return ref;
            }
            DjiCreateTaskResponse.DjiCreateTaskData data = resp.getData();
            DeliveryTaskRef ref = new DeliveryTaskRef(data.getId(), data.getStatus() == null ? null : String.valueOf(data.getStatus()));
            ref.setAccepted(apiCode == null || apiCode == 0);
            ref.setApiCode(apiCode);
            ref.setApiMessage(apiMessage);
            ref.setDisplayMessage((apiCode != null && apiCode != 0)
                ? textOr(apiMessage, "FC100 创建任务失败")
                : "FC100 创建任务成功：任务 " + textOr(data.getId(), "--"));
            ref.setDeviceSn(data.getDeviceSn());
            ref.setMissionId(data.getMissionId());
            ref.setTaskName(data.getTaskName());
            ref.setReason(data.getReason());
            ref.setUpdateTime(data.getUpdateTime());
            return ref;
        }

        static DeliveryTaskOperationResult toStartTaskResult(String taskId, DjiStandardResponse resp) {
            Integer apiCode = resp == null ? null : resp.getCode();
            String apiMessage = resp == null ? null : resp.getMessage();
            boolean accepted = apiCode == null || apiCode == 0;
            return DeliveryTaskOperationResult.builder()
                .operation("startTask")
                .taskId(taskId)
                .accepted(accepted)
                .apiCode(apiCode)
                .apiMessage(apiMessage)
                .displayMessage(accepted
                    ? "FC100 开始执行航线已受理：任务 " + textOr(taskId, "--")
                    : textOr(apiMessage, "FC100 开始执行航线失败"))
                .build();
        }

        static DeliveryTaskStatus toTaskStatus(String taskId, DjiTaskListResponse resp) {
            DeliveryTaskStatus s = new DeliveryTaskStatus();
            s.setTaskId(taskId);
            s.setAccepted(resp == null || resp.getCode() == null || resp.getCode() == 0);
            s.setApiCode(resp == null ? null : resp.getCode());
            s.setApiMessage(resp == null ? null : resp.getMessage());
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) {
                s.setStatus("UNKNOWN");
                s.setDisplayMessage("FC100 任务状态未知：未返回任务列表");
                return s;
            }
            Arrays.stream(resp.getData().getList())
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .ifPresentOrElse(item -> {
                    s.setStatus(item.getStatus() == null ? null : String.valueOf(item.getStatus()));
                    s.setMessage(item.getReason());
                    s.setReason(item.getReason());
                    s.setTaskCode(item.getCode());
                    s.setUpdateTime(item.getUpdateTime());
                    s.setStartTime(item.getStartTime());
                    s.setEndTime(item.getEndTime());
                    s.setEstimateTime(item.getEstimateTime());
                    s.setDeviceSn(item.getDeviceSn());
                    s.setMissionId(item.getMissionId());
                    s.setTaskName(item.getTaskName());
                    s.setPhase(toTaskPhase(item));
                    String taskHint = textOr(item.getReason(), textOr(taskCodeMessage(item.getCode()), textOr(resp.getMessage(), "OK")));
                    s.setDisplayMessage("FC100 航线任务" + toTaskStatusLabel(item)
                        + "：任务 " + textOr(item.getId(), "--")
                        + "，状态 " + textOr(s.getStatus(), "--")
                        + "，结果码 " + textOr(item.getCode(), "--")
                        + "，提示 " + taskHint);
                }, () -> {
                    s.setStatus("NOT_FOUND");
                    s.setAccepted(false);
                    s.setDisplayMessage("FC100 未查询到任务：" + taskId);
                });
            return s;
        }

        private static String toTaskPhase(DjiTaskListResponse.DjiTaskItem item) {
            if (hasEnded(item.getEndTime())) {
                return item.getCode() != null && item.getCode() != 0 ? "abnormal" : "completed";
            }
            Integer status = item.getStatus();
            if (status != null && status == 2) {
                return "pending";
            }
            return item.getCode() != null && item.getCode() != 0 ? "abnormal" : "normal";
        }

        private static String toTaskStatusLabel(DjiTaskListResponse.DjiTaskItem item) {
            if (hasEnded(item.getEndTime())) {
                return item.getCode() != null && item.getCode() != 0 ? "异常结束" : "已结束";
            }
            Integer status = item.getStatus();
            if (status != null && status == 2) {
                return "已创建/待执行";
            }
            return item.getCode() != null && item.getCode() != 0 ? "异常" : "状态";
        }

        private static boolean hasEnded(Long endTime) {
            return endTime != null && endTime > 0;
        }

        private static String taskCodeMessage(Integer code) {
            if (code == null) return null;
            if (code == 620179) {
                return "检测到右前机臂没有在位";
            }
            return null;
        }

        static DeliveryCommandRef toCommandRef(DjiCreateCmdResponse resp) {
            DeliveryCommandRef ref = new DeliveryCommandRef();
            if (resp == null || resp.getData() == null) return ref;
            DjiCreateCmdResponse.DjiCreateCmdData data = resp.getData();
            ref.setCode(data.getCode());
            ref.setBid(data.getBid());
            ref.setGatewaySn(data.getGatewaySn());
            ref.setDeviceSn(data.getDeviceSn());
            ref.setDeviceCmdMethod(data.getDeviceCmdMethod());
            ref.setStatus(data.getStatus());
            ref.setDeviceCmdData(data.getDeviceCmdData());
            ref.setCreateTime(data.getCreateTime());
            ref.setUpdateTime(data.getUpdateTime());
            return ref;
        }

        static DeliveryCommandStatus toCommandStatus(String deviceSn, DjiCommandStatusResponse resp) {
            DeliveryCommandStatus status = new DeliveryCommandStatus();
            status.setDeviceSn(deviceSn);
            if (resp != null && resp.getData() != null) {
                status.setDeviceSn(resp.getData().getDeviceSn());
                status.setServices(resp.getData().getServices());
            }
            return status;
        }

        private static Object first(Map<String, Object> map, String... keys) {
            if (map == null) return null;
            for (String key : keys) {
                if (map.containsKey(key)) return map.get(key);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object value) {
            return value instanceof Map ? (Map<String, Object>) value : null;
        }

        private static Object propertyValue(Object value) {
            Map<String, Object> map = asMap(value);
            if (map != null && map.containsKey("value")) {
                return map.get("value");
            }
            return value;
        }

        private static Integer asInt(Object value) {
            return value instanceof Number ? ((Number) value).intValue() : null;
        }

        private static Double asDouble(Object value) {
            return value instanceof Number ? ((Number) value).doubleValue() : null;
        }

        private static Boolean asBoolean(Object value) {
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() != 0;
            return null;
        }

        private static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String textOr(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        private static String textOr(Object value, String fallback) {
            return value == null ? fallback : String.valueOf(value);
        }

        private static String formatPositionState(Object value) {
            Object actual = propertyValue(value);
            Map<String, Object> state = asMap(actual);
            if (state == null) return asString(actual);
            Integer rtkNumber = asInt(first(state, "rtk_number"));
            Integer gpsNumber = asInt(first(state, "gps_number"));
            Integer quality = asInt(first(state, "quality"));
            StringBuilder text = new StringBuilder();
            if (rtkNumber != null) text.append("RTK ").append(rtkNumber);
            if (gpsNumber != null) {
                if (text.length() > 0) text.append(" / ");
                text.append("GPS ").append(gpsNumber);
            }
            if (quality != null) {
                if (text.length() > 0) text.append(" / ");
                text.append("Q").append(quality);
            }
            return text.length() == 0 ? null : text.toString();
        }
    }
}
