package com.yx.uavfire.integration;

import com.yx.uavfire.manage.model.dto.DeviceDTO;
import com.dji.sdk.cloudapi.device.DeviceDomainEnum;
import com.dji.sdk.cloudapi.device.DeviceSubTypeEnum;
import com.dji.sdk.cloudapi.device.DeviceTypeEnum;
import com.dji.sdk.cloudapi.device.OsdDock;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRemoteControl;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CloudServerForwardService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String baseUrl;

    public CloudServerForwardService(
            OkHttpClient okHttpClient,
            ObjectMapper objectMapper,
            @Value("${cloud-server.enabled:true}") boolean enabled,
            @Value("${cloud-server.base-url:http://127.0.0.1:8200}") String baseUrl) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public void forwardDeviceOnline(DeviceDTO device, String status) {
        if (!enabled || device == null || !StringUtils.hasText(device.getDeviceSn())) {
            return;
        }
        log.info("准备转发设备状态到cloud_server, deviceSn={}, status={}", device.getDeviceSn(), status);
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", device.getDeviceSn());
        data.put("model", firstText(device.getDeviceName(), device.getNickname(), enumName(device.getSubType()), enumName(device.getType())));
        data.put("role", resolveRole(device));
        data.put("status", status);
        postJson("/cloudapi/devices", data, "device-status");
    }

    public void forwardRcDroneOsd(DeviceDTO device, String from, OsdRcDrone data) {
        if (!enabled || data == null) {
            return;
        }
        log.info("准备转发飞行器OSD到cloud_server, from={}, latitude={}, longitude={}", from, data.getLatitude(), data.getLongitude());
        Map<String, Object> payload = baseDevicePayload(device, from, "ACTIVE");
        payload.put("location", location(data.getLatitude(), data.getLongitude(), firstNumber(data.getElevation(), data.getHeight())));
        postJson("/cloudapi/devices", payload, "osd-rc-drone");
    }

    public void forwardRemoteControlOsd(DeviceDTO device, String from, OsdRemoteControl data) {
        if (!enabled || data == null) {
            return;
        }
        log.info("准备转发遥控器OSD到cloud_server, from={}, latitude={}, longitude={}", from, data.getLatitude(), data.getLongitude());
        Map<String, Object> payload = baseDevicePayload(device, from, "ACTIVE");
        payload.put("location", location(data.getLatitude(), data.getLongitude(), firstNumber(data.getHeight(), null)));
        postJson("/cloudapi/devices", payload, "osd-rc");
    }

    public void forwardDockDroneOsd(DeviceDTO device, String from, OsdDockDrone data) {
        if (!enabled || data == null) {
            return;
        }
        log.info("准备转发机库无人机OSD到cloud_server, from={}, latitude={}, longitude={}", from, data.getLatitude(), data.getLongitude());
        Map<String, Object> payload = baseDevicePayload(device, from, "ACTIVE");
        payload.put("location", location(data.getLatitude(), data.getLongitude(), firstNumber(data.getElevation(), data.getHeight())));
        postJson("/cloudapi/devices", payload, "osd-dock-drone");
    }

    public void forwardDockOsd(DeviceDTO device, String from, OsdDock data) {
        if (!enabled || data == null) {
            return;
        }
        log.info("准备转发机库OSD到cloud_server, from={}, latitude={}, longitude={}", from, data.getLatitude(), data.getLongitude());
        Map<String, Object> payload = baseDevicePayload(device, from, "ACTIVE");
        payload.put("location", location(data.getLatitude(), data.getLongitude(), null));
        postJson("/cloudapi/devices", payload, "osd-dock");
    }

    private Map<String, Object> baseDevicePayload(DeviceDTO device, String fallbackDeviceSn, String status) {
        Map<String, Object> payload = new HashMap<>();
        String deviceId = device != null && StringUtils.hasText(device.getDeviceSn()) ? device.getDeviceSn() : fallbackDeviceSn;
        payload.put("deviceId", deviceId);
        payload.put("model", device == null
                ? "DJI Device"
                : firstText(device.getDeviceName(), device.getNickname(), enumName(device.getSubType()), enumName(device.getType()), "DJI Device"));
        payload.put("role", resolveRole(device));
        payload.put("status", status);
        return payload;
    }

    private Map<String, Object> location(Number latitude, Number longitude, Number altitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        Map<String, Object> location = new HashMap<>();
        location.put("lat", latitude);
        location.put("lon", longitude);
        if (altitude != null) {
            location.put("alt", altitude);
        }
        return location;
    }

    private void postJson(String path, Map<String, Object> body, String tag) {
        try {
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(baseUrl + path)
                    .post(RequestBody.create(json, JSON))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Forward {} failed, code={}, path={}", tag, response.code(), path);
                    return;
                }
                log.info("Forward {} success, path={}, code={}", tag, path, response.code());
            }
        } catch (Exception ex) {
            log.warn("Forward {} exception: {}", tag, ex.getMessage());
        }
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "http://127.0.0.1:8200";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private Number firstNumber(Number first, Number second) {
        return first != null ? first : second;
    }

    private String resolveRole(DeviceDTO device) {
        if (device == null || device.getDomain() == null) {
            return "UNKNOWN";
        }
        if (DeviceDomainEnum.REMOTER_CONTROL == device.getDomain()) {
            return "REMOTE_CONTROL";
        }
        if (DeviceDomainEnum.DOCK == device.getDomain()) {
            return "DOCK";
        }
        if (DeviceDomainEnum.DRONE == device.getDomain()) {
            if (DeviceTypeEnum.M300 == device.getType()) {
                return "M300";
            }
            if (DeviceTypeEnum.M4_SERIES == device.getType()) {
                if (DeviceSubTypeEnum.ONE == device.getSubType()) {
                    return "M4T";
                }
                if (DeviceSubTypeEnum.ZERO == device.getSubType()) {
                    return "M4E";
                }
            }
            if (DeviceTypeEnum.M3D == device.getType() && DeviceSubTypeEnum.ONE == device.getSubType()) {
                return "M3TD";
            }
            return "DRONE";
        }
        return "UNKNOWN";
    }
}
