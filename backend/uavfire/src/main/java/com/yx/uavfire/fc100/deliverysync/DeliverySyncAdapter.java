package com.yx.uavfire.fc100.deliverysync;

import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryBypassStreamDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryDeviceProperties;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryCommandStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskOperationResult;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskRef;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryTaskStatus;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineDTO;
import com.yx.uavfire.fc100.deliverysync.model.dto.DeliveryWaylineImportResult;
import com.yx.uavfire.fc100.deliverysync.model.param.CreateTaskRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeliveryBypassStreamRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.deliverysync.model.param.WaylineImportRequest;

import java.util.List;

/**
 * spec §3.6 / §5.7 — Delivery Sync 接口抽象。MockAdapter / HttpAdapter 互斥注入。
 */
public interface DeliverySyncAdapter {
    List<DeliveryDeviceDTO> listDevices(String workspaceId);
    DeliveryDeviceProperties getDeviceProperties(String deviceSn);
    DeliveryBypassStreamDTO startBypassStream(DeliveryBypassStreamRequest req);
    List<DeliveryWaylineDTO> listWaylines(int page, int pageSize, String key);
    DeliveryWaylineImportResult importWayline(WaylineImportRequest req);
    DeliveryTaskRef createTask(CreateTaskRequest req);
    DeliveryTaskOperationResult startTask(String taskId);
    DeliveryTaskStatus queryTaskStatus(String taskId);
    DeliveryCommandRef sendDeviceCommand(DeviceCommandRequest req);
    DeliveryCommandStatus queryDeviceCommandStatus(String deviceSn);
}
