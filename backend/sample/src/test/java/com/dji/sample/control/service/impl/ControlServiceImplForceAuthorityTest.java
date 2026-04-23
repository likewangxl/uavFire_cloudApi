package com.dji.sample.control.service.impl;

import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sample.manage.service.IDeviceService;
import com.dji.sdk.cloudapi.device.DroneModeCodeEnum;
import com.dji.sdk.common.HttpResultResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlServiceImplForceAuthorityTest {

    @Test
    void checkFlyToConditionDoesNotForceGrabFlightAuthority() {
        RecordingControlServiceImpl service = new RecordingControlServiceImpl();
        IDeviceRedisService deviceRedisService = mock(IDeviceRedisService.class);
        IDeviceService deviceService = mock(IDeviceService.class);

        DeviceDTO gateway = new DeviceDTO();
        gateway.setChildDeviceSn("AIRCRAFT-1");

        when(deviceRedisService.getDeviceOnline("GATEWAY-1")).thenReturn(Optional.of(gateway));
        when(deviceService.getDeviceMode("AIRCRAFT-1")).thenReturn(DroneModeCodeEnum.MANUAL);

        ReflectionTestUtils.setField(service, "deviceRedisService", deviceRedisService);
        ReflectionTestUtils.setField(service, "deviceService", deviceService);

        ReflectionTestUtils.invokeMethod(service, "checkFlyToCondition", "GATEWAY-1");

        assertTrue(!service.forceUsed, "fly_to_point precheck should avoid force-grabbing flight authority in the stable handoff window");
    }

    private static class RecordingControlServiceImpl extends ControlServiceImpl {

        private boolean forceUsed;

        @Override
        public HttpResultResponse seizeAuthority(String sn, com.dji.sample.control.model.enums.DroneAuthorityEnum authority,
                                                 com.dji.sample.control.model.param.DronePayloadParam param, boolean force) {
            this.forceUsed = force;
            return HttpResultResponse.success();
        }
    }
}
