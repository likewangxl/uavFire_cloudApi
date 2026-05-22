package com.yx.uavfire.msdk;

import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsdkDeviceStateServiceTest {

    @Test
    void upsertAndListOnlineAircraftState() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO();
        state.setGatewaySn("RC_PLUS_LOCAL");
        state.setAircraftSn("1581F7K3D249E00AM3Q3");
        state.setOnline(true);
        state.setConnectionState("CONNECTED");
        state.setLatitude(34.123456);
        state.setLongitude(108.123456);
        state.setBatteryPercent(82);
        state.setUpdatedAt(1779380000000L);

        service.upsert(state);

        assertEquals(1, service.listOnline().size());
        MsdkDeviceStateDTO restored = service.get("1581F7K3D249E00AM3Q3").orElseThrow();
        assertEquals("RC_PLUS_LOCAL", restored.getGatewaySn());
        assertEquals(34.123456, restored.getLatitude());
        assertEquals(108.123456, restored.getLongitude());
        assertEquals(82, restored.getBatteryPercent());
    }

    @Test
    void staleOrDisconnectedDeviceIsNotListedOnline() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO();
        state.setAircraftSn("AIRCRAFT-1");
        state.setOnline(false);
        state.setConnectionState("DISCONNECTED");
        state.setUpdatedAt(1779380000000L);

        service.upsert(state);

        assertTrue(service.listOnline().isEmpty());
    }
}
