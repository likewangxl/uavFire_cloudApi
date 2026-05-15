package com.yx.uavfire.fc100.deliverysync.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncException;
import com.yx.uavfire.fc100.deliverysync.http.DeliverySyncHttpClient;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实 HTTPS 调用 ta-api.dji.com。
 *
 * TODO(上线前): 收到商务方 AK/SK 邮件附件后，按实际文档替换下列路径占位符：
 *   /v1/devices            → 实际 listDevices 路径
 *   /v1/devices/{sn}/properties → 实际 getDeviceProperties 路径
 *   /v1/tasks              → 实际 createTask 路径
 *   /v1/tasks/{id}/start   → 实际 startTask 路径
 *   /v1/tasks/{id}/status  → 实际 queryTaskStatus 路径
 * 同时按文档校正 DJI 响应字段名（当前为合理占位，实际可能不同）。
 */
@Service
@ConditionalOnProperty(prefix = "fc100.delivery-sync", name = "mode", havingValue = "http")
@Slf4j
public class HttpDeliverySyncAdapter implements DeliverySyncAdapter {

    private final DeliverySyncHttpClient httpClient;

    public HttpDeliverySyncAdapter(DeliverySyncHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // -------------------------------------------------------------------------
    // Interface methods
    // -------------------------------------------------------------------------

    @Override
    public List<DeliveryDeviceDTO> listDevices(String workspaceId) {
        // TODO(上线前): 替换为实际路径
        String path = "/v1/devices";
        Map<String, String> query = new HashMap<>();
        query.put("workspace_id", workspaceId);
        try {
            DjiDeviceListResponse resp = httpClient.get(path, query, DjiDeviceListResponse.class, null, null);
            return ResponseMapper.toDeviceList(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "listDevices");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryDeviceProperties getDeviceProperties(String deviceSn) {
        // TODO(上线前): 替换为实际路径
        String path = "/v1/devices/" + deviceSn + "/properties";
        try {
            DjiDevicePropertiesResponse resp = httpClient.get(path, null, DjiDevicePropertiesResponse.class, null, null);
            return ResponseMapper.toDeviceProperties(deviceSn, resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "getDeviceProperties");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryTaskRef createTask(CreateTaskRequest req) {
        // TODO(上线前): 替换为实际路径和请求字段
        String path = "/v1/tasks";
        String idemKey = UUID.randomUUID().toString();
        try {
            DjiCreateTaskResponse resp = httpClient.post(path, req, DjiCreateTaskResponse.class, idemKey, req.getMissionNo());
            return ResponseMapper.toTaskRef(resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "createTask");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public void startTask(String taskId) {
        // TODO(上线前): 替换为实际路径
        String path = "/v1/tasks/" + taskId + "/start";
        try {
            httpClient.post(path, null, DjiStartTaskResponse.class, null, null);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "startTask");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    @Override
    public DeliveryTaskStatus queryTaskStatus(String taskId) {
        // TODO(上线前): 替换为实际路径
        String path = "/v1/tasks/" + taskId + "/status";
        try {
            DjiTaskStatusResponse resp = httpClient.get(path, null, DjiTaskStatusResponse.class, null, null);
            return ResponseMapper.toTaskStatus(taskId, resp);
        } catch (DeliverySyncException e) {
            throw toBusinessException(e, "queryTaskStatus");
        } catch (IOException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_NETWORK, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Error mapping
    // -------------------------------------------------------------------------

    private Fc100BusinessException toBusinessException(DeliverySyncException e, String apiName) {
        log.warn("DeliverySync {} error: HTTP {} body={}", apiName, e.getHttpCode(), e.getBody());
        if (e.getHttpCode() == 401 || e.getHttpCode() == 403) {
            return new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_AUTH,
                apiName + " auth failed: " + e.getBody());
        }
        return new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_BUSINESS,
            apiName + " failed HTTP " + e.getHttpCode() + ": " + e.getBody());
    }

    // -------------------------------------------------------------------------
    // DJI response DTOs (snake_case — TODO: verify against actual API docs)
    // -------------------------------------------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiDeviceListResponse {
        // TODO(上线前): 确认 DJI 实际响应字段名
        @JsonProperty("data")
        private DjiDeviceItem[] data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiDeviceItem {
            @JsonProperty("device_sn")
            private String deviceSn;
            @JsonProperty("device_type")
            private String deviceType;
            @JsonProperty("online_status")
            private String onlineStatus;   // ONLINE / OFFLINE
            @JsonProperty("bind_status")
            private String bindStatus;     // BOUND / UNBOUND
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiDevicePropertiesResponse {
        // TODO(上线前): 确认 DJI 实际响应字段名
        @JsonProperty("data")
        private DjiDevicePropertiesData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiDevicePropertiesData {
            @JsonProperty("battery_percent")
            private Integer batteryPercent;
            @JsonProperty("rtk_status")
            private String rtkStatus;
            @JsonProperty("latitude")
            private Double latitude;
            @JsonProperty("longitude")
            private Double longitude;
            @JsonProperty("altitude")
            private Double altitude;
            @JsonProperty("osd_timestamp")
            private Long osdTimestamp;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiCreateTaskResponse {
        // TODO(上线前): 确认 DJI 实际响应字段名
        @JsonProperty("data")
        private DjiCreateTaskData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiCreateTaskData {
            @JsonProperty("task_id")
            private String taskId;
            @JsonProperty("status")
            private String status;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiStartTaskResponse {
        @JsonProperty("code")
        private Integer code;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DjiTaskStatusResponse {
        // TODO(上线前): 确认 DJI 实际响应字段名
        @JsonProperty("data")
        private DjiTaskStatusData data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DjiTaskStatusData {
            @JsonProperty("task_id")
            private String taskId;
            @JsonProperty("status")
            private String status;
            @JsonProperty("phase")
            private String phase;
            @JsonProperty("progress_percent")
            private Integer progressPercent;
            @JsonProperty("message")
            private String message;
            @JsonProperty("update_time")
            private Long updateTime;
        }
    }

    // -------------------------------------------------------------------------
    // Response mapper — DJI fields → internal DTOs
    // -------------------------------------------------------------------------

    static final class ResponseMapper {

        private ResponseMapper() {}

        static List<DeliveryDeviceDTO> toDeviceList(DjiDeviceListResponse resp) {
            if (resp == null || resp.getData() == null) return List.of();
            return Arrays.stream(resp.getData())
                .map(item -> new DeliveryDeviceDTO(
                    item.getDeviceSn(),
                    item.getDeviceType(),
                    item.getOnlineStatus(),
                    item.getBindStatus()))
                .collect(java.util.stream.Collectors.toList());
        }

        static DeliveryDeviceProperties toDeviceProperties(String deviceSn, DjiDevicePropertiesResponse resp) {
            DeliveryDeviceProperties p = new DeliveryDeviceProperties();
            p.setDeviceSn(deviceSn);
            if (resp != null && resp.getData() != null) {
                DjiDevicePropertiesResponse.DjiDevicePropertiesData d = resp.getData();
                p.setBatteryPercent(d.getBatteryPercent());
                p.setRtkStatus(d.getRtkStatus());
                p.setLatitude(d.getLatitude());
                p.setLongitude(d.getLongitude());
                p.setAltitude(d.getAltitude());
                p.setOsdTimestamp(d.getOsdTimestamp());
            }
            return p;
        }

        static DeliveryTaskRef toTaskRef(DjiCreateTaskResponse resp) {
            if (resp == null || resp.getData() == null) {
                return new DeliveryTaskRef(null, "UNKNOWN");
            }
            return new DeliveryTaskRef(resp.getData().getTaskId(), resp.getData().getStatus());
        }

        static DeliveryTaskStatus toTaskStatus(String taskId, DjiTaskStatusResponse resp) {
            DeliveryTaskStatus s = new DeliveryTaskStatus();
            s.setTaskId(taskId);
            if (resp != null && resp.getData() != null) {
                DjiTaskStatusResponse.DjiTaskStatusData d = resp.getData();
                s.setStatus(d.getStatus());
                s.setPhase(d.getPhase());
                s.setProgressPercent(d.getProgressPercent());
                s.setMessage(d.getMessage());
                s.setUpdateTime(d.getUpdateTime());
            }
            return s;
        }
    }
}
