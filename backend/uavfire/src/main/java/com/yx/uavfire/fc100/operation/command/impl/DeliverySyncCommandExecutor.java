package com.yx.uavfire.fc100.operation.command.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.operation.command.CommandExecutor;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class DeliverySyncCommandExecutor implements CommandExecutor {

    private static final Set<String> NON_DELIVERY_TYPES = Set.of("MOCK");
    private static final Set<String> DANGEROUS_METHODS = Set.of(
        "hoist_hook_control",
        "drone_emergency_stop",
        "return_home",
        "drone_landing",
        "flight_authority_grab",
        "flight_authority_release",
        "control_authority_grab",
        "control_authority_release"
    );

    private final DeliverySyncAdapter adapter;
    private final ObjectMapper objectMapper;

    public DeliverySyncCommandExecutor(DeliverySyncAdapter adapter, ObjectMapper objectMapper) {
        this.adapter = adapter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String commandType) {
        return commandType != null && !NON_DELIVERY_TYPES.contains(commandType);
    }

    @Override
    public void execute(OperationCommandEventEntity command) {
        try {
            JsonNode payload = objectMapper.readTree(command.getPayloadJson());
            String missionNo = text(payload, "missionNo");
            String deviceSn = text(payload, "deviceSn");
            String method = text(payload, "deviceCmdMethod");
            JsonNode dataNode = payload.get("deviceCmdData");
            Map<String, Object> data = dataNode == null || dataNode.isNull()
                ? Map.of()
                : objectMapper.convertValue(dataNode, Map.class);
            adapter.sendDeviceCommand(DeviceCommandRequest.builder()
                .missionNo(missionNo)
                .deviceSn(deviceSn)
                .deviceCmdMethod(method)
                .deviceCmdData(data)
                .build());
        } catch (Fc100BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.DELIVERY_SYNC_BUSINESS,
                "delivery sync command failed: " + e.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.asText().isBlank() ? null : value.asText();
    }
}
