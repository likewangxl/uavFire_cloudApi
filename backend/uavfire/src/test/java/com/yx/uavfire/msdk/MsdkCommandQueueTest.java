package com.yx.uavfire.msdk;

import com.yx.uavfire.msdk.model.MsdkCommandDTO;
import com.yx.uavfire.msdk.model.MsdkCommandParam;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsdkCommandQueueTest {

    @Test
    void enqueueAndPollPendingCommand() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkCommandParam param = new MsdkCommandParam();
        param.setCommand("return_home");
        param.setParams(Map.of());

        MsdkCommandDTO queued = service.enqueueCommand("AIRCRAFT-1", param);
        MsdkCommandDTO polled = service.pollCommand("AIRCRAFT-1").orElseThrow();

        assertEquals(queued.getCommandId(), polled.getCommandId());
        assertEquals("return_home", polled.getCommand());
        assertEquals("DISPATCHED", polled.getStatus());
        assertTrue(service.pollCommand("AIRCRAFT-1").isEmpty());
    }

    @Test
    void acknowledgeCommandUpdatesStatus() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkCommandParam param = new MsdkCommandParam();
        param.setCommand("focus_thermal");
        param.setParams(Map.of());

        MsdkCommandDTO queued = service.enqueueCommand("AIRCRAFT-1", param);
        service.acknowledgeCommand(queued.getCommandId(), "APPLIED", "ok");

        MsdkCommandDTO updated = service.getCommand(queued.getCommandId()).orElseThrow();
        assertEquals("APPLIED", updated.getStatus());
        assertEquals("ok", updated.getMessage());
    }

    @Test
    void enqueueRejectsUnsupportedCommandBeforeQueueing() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkCommandParam param = new MsdkCommandParam();
        param.setCommand("unsupported_payload_magic");
        param.setParams(Map.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.enqueueCommand("AIRCRAFT-1", param)
        );

        assertEquals("unsupported-msdk-command:unsupported_payload_magic", error.getMessage());
        assertTrue(service.pollCommand("AIRCRAFT-1").isEmpty());
    }

    @Test
    void pollCommandExpiresStalePendingFlightCommandBeforeDispatch() {
        AtomicLong now = new AtomicLong(1_000_000L);
        MsdkDeviceStateService service = new MsdkDeviceStateService(now::get);
        MsdkCommandParam param = new MsdkCommandParam();
        param.setCommand("takeoff");
        param.setParams(Map.of());

        MsdkCommandDTO queued = service.enqueueCommand("AIRCRAFT-1", param);
        now.addAndGet(16_000L);

        assertTrue(service.pollCommand("AIRCRAFT-1").isEmpty());

        MsdkCommandDTO expired = service.getCommand(queued.getCommandId()).orElseThrow();
        assertEquals("EXPIRED", expired.getStatus());
        assertEquals("command-expired-before-dispatch", expired.getMessage());
    }
}
