package com.yx.uavfire.fc100.deliverysync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.controller.DeliveryController;
import com.yx.uavfire.fc100.deliverysync.model.param.DeviceCommandRequest;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.operation.command.impl.DeliverySyncCommandExecutor;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import com.yx.uavfire.fc100.route.service.RouteExportService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HookCommandGuardTest {

    @Test
    void customMethodCannotReleaseTheHook() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliveryController controller = new DeliveryController(
                adapter, new DeliverySyncProperties(), mock(FireMissionMapper.class),
                mock(RouteExportService.class), mock(MissionStateMachine.class));
        DeliveryController.DeviceCommandParam param = new DeliveryController.DeviceCommandParam();
        param.setOperatorId("commander-1");

        Fc100BusinessException error = assertThrows(Fc100BusinessException.class,
                () -> controller.command("M-1", HookCommandGuard.HOIST_HOOK, param, null));

        assertEquals(Fc100ErrorCode.RELEASE_CAPABILITY_UNCONFIRMED, error.getErrorCode());
        verify(adapter, never()).sendDeviceCommand(any());
    }

    @Test
    void executorRejectsHookPayloadWithoutReleaseAuthorization() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncCommandExecutor executor = new DeliverySyncCommandExecutor(adapter, new ObjectMapper());
        OperationCommandEventEntity command = new OperationCommandEventEntity();
        command.setCommandType(HookCommandGuard.HOIST_HOOK);
        command.setPayloadJson("{\"missionNo\":\"M-1\",\"deviceSn\":\"SN-1\",\"deviceCmdMethod\":\"hoist_hook_control\"}");

        assertThrows(Fc100BusinessException.class, () -> executor.execute(command));
        verify(adapter, never()).sendDeviceCommand(any(DeviceCommandRequest.class));
    }

    @Test
    void executorSendsHookWhenReleaseWasAuthorized() {
        DeliverySyncAdapter adapter = mock(DeliverySyncAdapter.class);
        DeliverySyncCommandExecutor executor = new DeliverySyncCommandExecutor(adapter, new ObjectMapper());
        OperationCommandEventEntity command = new OperationCommandEventEntity();
        command.setCommandType("DELIVERY_SYNC");
        command.setPayloadJson("{\"missionNo\":\"M-1\",\"deviceSn\":\"SN-1\","
                + "\"deviceCmdMethod\":\"hoist_hook_control\",\"releaseAuthorized\":true}");

        executor.execute(command);

        verify(adapter).sendDeviceCommand(any(DeviceCommandRequest.class));
    }
}
