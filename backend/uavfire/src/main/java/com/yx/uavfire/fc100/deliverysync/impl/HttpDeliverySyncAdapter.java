package com.yx.uavfire.fc100.deliverysync.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncException;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.http.DeliverySyncHttpClient;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
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
    public void importWayline(WaylineImportRequest req) {
        String idemKey = UUID.randomUUID().toString();
        try {
            httpClient.postForm(groupPath("/map/sdk/v1/groups/%s/waylines/kml/import"),
                Map.of("file", req.getKml(), "wayline_id", req.getWaylineId()),
                DjiStandardResponse.class, idemKey, req.getMissionNo());
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
    public void startTask(String taskId) {
        try {
            httpClient.post(groupPath("/task/sdk/v1/groups/%s/tasks/" + taskId + "/start"),
                Map.of("ignore_radar_detection", "false"), null, DjiStandardResponse.class, null, null);
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiStandardResponse {
        @JsonProperty("code")
        private Integer code;
        @JsonProperty("message")
        private String message;
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
    static class DjiCreateTaskResponse {
        @JsonProperty("data")
        private DjiCreateTaskData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiCreateTaskData {
            @JsonProperty("id")
            private String id;
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
            @JsonProperty("status")
            private Integer status;
            @JsonProperty("code")
            private Integer code;
            @JsonProperty("reason")
            private String reason;
            @JsonProperty("update_time")
            private Long updateTime;
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

    static final class ResponseMapper {

        private ResponseMapper() {}

        static List<DeliveryDeviceDTO> toDeviceList(DjiDeviceListResponse resp) {
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) return List.of();
            return Arrays.stream(resp.getData().getList())
                .map(item -> new DeliveryDeviceDTO(
                    item.getSn(),
                    item.getDeviceModelKey(),
                    null,
                    item.getDeviceModelClass()))
                .collect(java.util.stream.Collectors.toList());
        }

        static DeliveryDeviceProperties toDeviceProperties(String deviceSn, DjiDevicePropertiesResponse resp) {
            DeliveryDeviceProperties p = new DeliveryDeviceProperties();
            p.setDeviceSn(deviceSn);
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) return p;
            Arrays.stream(resp.getData().getList())
                .filter(item -> deviceSn.equals(item.getSn()))
                .findFirst()
                .ifPresent(item -> {
                    p.setOsdTimestamp(item.getUpdatedTime());
                    Map<String, Object> properties = item.getProperties();
                    p.setBatteryPercent(asInt(first(properties, "battery_percent", "battery", "capacity_percent")));
                    p.setRtkStatus(asString(first(properties, "rtk_status", "rtk")));
                    p.setLatitude(asDouble(first(properties, "latitude", "lat")));
                    p.setLongitude(asDouble(first(properties, "longitude", "lng")));
                    p.setAltitude(asDouble(first(properties, "altitude", "height")));
                });
            return p;
        }

        static DeliveryTaskRef toTaskRef(DjiCreateTaskResponse resp) {
            if (resp == null || resp.getData() == null) {
                return new DeliveryTaskRef(null, "UNKNOWN");
            }
            DjiCreateTaskResponse.DjiCreateTaskData data = resp.getData();
            return new DeliveryTaskRef(data.getId(), data.getStatus() == null ? null : String.valueOf(data.getStatus()));
        }

        static DeliveryTaskStatus toTaskStatus(String taskId, DjiTaskListResponse resp) {
            DeliveryTaskStatus s = new DeliveryTaskStatus();
            s.setTaskId(taskId);
            if (resp == null || resp.getData() == null || resp.getData().getList() == null) {
                s.setStatus("UNKNOWN");
                return s;
            }
            Arrays.stream(resp.getData().getList())
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .ifPresentOrElse(item -> {
                    s.setStatus(item.getStatus() == null ? null : String.valueOf(item.getStatus()));
                    s.setMessage(item.getReason());
                    s.setUpdateTime(item.getUpdateTime());
                    s.setPhase(item.getCode() == null || item.getCode() == 0 ? "normal" : "abnormal");
                }, () -> s.setStatus("NOT_FOUND"));
            return s;
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

        private static Integer asInt(Object value) {
            return value instanceof Number ? ((Number) value).intValue() : null;
        }

        private static Double asDouble(Object value) {
            return value instanceof Number ? ((Number) value).doubleValue() : null;
        }

        private static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }
    }
}
