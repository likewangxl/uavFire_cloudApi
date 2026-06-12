package com.yx.uavfire.msdk;

import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsdkDeviceStateServiceTest {

    @Test
    void upsertAndListOnlineAircraftState() {
        MsdkDeviceStateService service = new MsdkDeviceStateService(() -> 1779380000000L);
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO();
        state.setGatewaySn("RC_PLUS_LOCAL");
        state.setAircraftSn("1581F7K3D249E00AM3Q3");
        state.setOnline(true);
        state.setConnectionState("CONNECTED");
        state.setDeviceName("DJI Matrice 4T");
        state.setModel("Matrice 4T");
        state.setLatitude(34.123456);
        state.setLongitude(108.123456);
        state.setBatteryPercent(82);
        state.setUpdatedAt(1779380000000L);

        service.upsert(state);

        assertEquals(1, service.listOnline().size());
        MsdkDeviceStateDTO restored = service.get("1581F7K3D249E00AM3Q3").orElseThrow();
        assertEquals("RC_PLUS_LOCAL", restored.getGatewaySn());
        assertEquals("DJI Matrice 4T", restored.getDeviceName());
        assertEquals("Matrice 4T", restored.getModel());
        assertEquals(34.123456, restored.getLatitude());
        assertEquals(108.123456, restored.getLongitude());
        assertEquals(82, restored.getBatteryPercent());
    }

    @Test
    void staleOrDisconnectedDeviceIsNotListedOnline() {
        MsdkDeviceStateService service = new MsdkDeviceStateService(() -> 1779380000000L);
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO();
        state.setAircraftSn("AIRCRAFT-1");
        state.setOnline(false);
        state.setConnectionState("DISCONNECTED");
        state.setUpdatedAt(1779380000000L);

        service.upsert(state);

        assertTrue(service.listOnline().isEmpty());
    }

    @Test
    void onlineListExcludesDevicesThatHaveNotReportedWithinTtl() {
        long[] now = {1_000_000L};
        MsdkDeviceStateService service = new MsdkDeviceStateService(() -> now[0]);
        MsdkDeviceStateDTO current = new MsdkDeviceStateDTO();
        current.setAircraftSn("AIRCRAFT-CURRENT");
        current.setOnline(true);
        current.setConnectionState("CAPABILITY_READY");
        current.setUpdatedAt(now[0]);
        MsdkDeviceStateDTO stale = new MsdkDeviceStateDTO();
        stale.setAircraftSn("AIRCRAFT-STALE");
        stale.setOnline(true);
        stale.setConnectionState("CAPABILITY_READY");
        stale.setUpdatedAt(now[0] - 15_001L);

        service.upsert(current);
        service.upsert(stale);

        assertEquals(1, service.listOnline().size());
        assertEquals("AIRCRAFT-CURRENT", service.listOnline().get(0).getAircraftSn());
    }
}
