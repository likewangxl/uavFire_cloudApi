package com.yx.uavfire.fc100.deliverysync.impl;

import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.service.DeliverySyncLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** mock 模式默认启用（matchIfMissing=true）。 */
@Service
@ConditionalOnProperty(prefix = "fc100.delivery-sync", name = "mode",
                       havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockDeliverySyncAdapter implements DeliverySyncAdapter {

    private final ConcurrentHashMap<String, DeliveryTaskStatus> tasks = new ConcurrentHashMap<>();
    private final DeliverySyncLogService logService;

    public MockDeliverySyncAdapter(DeliverySyncLogService l) {
        this.logService = l;
    }

    @Override
    public List<DeliveryDeviceDTO> listDevices(String workspaceId) {
        long t0 = System.currentTimeMillis();
        var devices = List.of(
            new DeliveryDeviceDTO("FC100_MOCK_001", "FC100", "ONLINE", "BOUND"),
            new DeliveryDeviceDTO("FC100_MOCK_002", "FC100", "OFFLINE", "BOUND"));
        logService.recordSuccess("listDevices", null, "GET",
            "/devices?workspaceId=" + workspaceId, "", 200,
            devices.toString(), null, (int) (System.currentTimeMillis() - t0));
        return devices;
    }

    @Override
    public DeliveryDeviceProperties getDeviceProperties(String deviceSn) {
        long t0 = System.currentTimeMillis();
        var p = new DeliveryDeviceProperties();
        p.setDeviceSn(deviceSn);
        p.setBatteryPercent(85);
        p.setRtkStatus("FIX");
        p.setLatitude(31.0);
        p.setLongitude(121.0);
        p.setAltitude(50.0);
        p.setOsdTimestamp(System.currentTimeMillis());
        logService.recordSuccess("getDeviceProperties", null, "GET",
            "/devices/" + deviceSn + "/properties", "", 200,
            p.toString(), null, (int) (System.currentTimeMillis() - t0));
        return p;
    }

    @Override
    public DeliveryTaskRef createTask(CreateTaskRequest req) {
        long t0 = System.currentTimeMillis();
        String taskId = "MOCK-TASK-" + UUID.randomUUID();
        var s = new DeliveryTaskStatus();
        s.setTaskId(taskId);
        s.setStatus("CREATED");
        s.setPhase("upload");
        s.setProgressPercent(0);
        s.setUpdateTime(System.currentTimeMillis());
        tasks.put(taskId, s);
        log.info("MOCK Delivery createTask: taskId={} mission={} aircraft={}",
            taskId, req.getMissionNo(), req.getDeviceSn());
        logService.recordSuccess("createTask", null, "POST", "/tasks",
            req.toString(), 200, taskId, null, (int) (System.currentTimeMillis() - t0));
        return new DeliveryTaskRef(taskId, "CREATED");
    }

    @Override
    public void startTask(String taskId) {
        long t0 = System.currentTimeMillis();
        var s = tasks.get(taskId);
        if (s != null) {
            s.setStatus("IN_PROGRESS");
            s.setPhase("flying");
            s.setUpdateTime(System.currentTimeMillis());
        }
        logService.recordSuccess("startTask", null, "POST",
            "/tasks/" + taskId + "/start", "", 200, "ok",
            null, (int) (System.currentTimeMillis() - t0));
    }

    @Override
    public DeliveryTaskStatus queryTaskStatus(String taskId) {
        long t0 = System.currentTimeMillis();
        var s = tasks.get(taskId);
        if (s == null) {
            s = new DeliveryTaskStatus();
            s.setTaskId(taskId);
            s.setStatus("NOT_FOUND");
        }
        logService.recordSuccess("queryTaskStatus", null, "GET",
            "/tasks/" + taskId, "", 200, s.toString(),
            null, (int) (System.currentTimeMillis() - t0));
        return s;
    }
}
