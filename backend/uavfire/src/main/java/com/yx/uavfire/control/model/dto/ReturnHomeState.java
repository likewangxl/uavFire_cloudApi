package com.yx.uavfire.control.model.dto;

import com.yx.uavfire.common.util.SpringBeanUtilsTest;
import com.yx.uavfire.control.service.impl.RemoteDebugHandler;
import com.yx.uavfire.manage.model.dto.DeviceDTO;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.yx.uavfire.manage.service.IDeviceService;
import com.dji.sdk.cloudapi.device.DroneModeCodeEnum;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author sean
 * @version 1.4
 * @date 2023/4/19
 */

@Slf4j
public class ReturnHomeState extends RemoteDebugHandler {

    @Override
    public boolean canPublish(String sn) {
        IDeviceRedisService deviceRedisService = SpringBeanUtilsTest.getBean(IDeviceRedisService.class);
        IDeviceService deviceService = SpringBeanUtilsTest.getBean(IDeviceService.class);
        boolean canPublish = canPublishForSn(deviceRedisService, deviceService, sn);
        log.info("return_home canPublish check. sn={}, result={}", sn, canPublish);
        return canPublish;
    }

    private boolean canPublishForSn(IDeviceRedisService deviceRedisService, IDeviceService deviceService, String sn) {
        Set<String> candidateSns = new LinkedHashSet<>();
        candidateSns.add(sn);
        deviceRedisService.getDeviceOnline(sn).ifPresent(device -> {
            if (device.getChildDeviceSn() != null && !device.getChildDeviceSn().isEmpty()) {
                candidateSns.add(device.getChildDeviceSn());
            }
        });
        deviceService.getDeviceBySn(sn).ifPresent(device -> {
            if (device.getChildDeviceSn() != null && !device.getChildDeviceSn().isEmpty()) {
                candidateSns.add(device.getChildDeviceSn());
            }
            if (device.getParentSn() != null && !device.getParentSn().isEmpty()) {
                candidateSns.add(device.getParentSn());
            }
        });

        log.info("return_home candidate sns. sn={}, candidates={}", sn, candidateSns);
        for (String candidateSn : candidateSns) {
            if (canPublishWithRcOsd(deviceRedisService, candidateSn) || canPublishWithDockOsd(deviceRedisService, candidateSn)) {
                log.info("return_home canPublish matched. sn={}, candidateSn={}", sn, candidateSn);
                return true;
            }
        }
        return false;
    }

    private boolean canPublishWithRcOsd(IDeviceRedisService deviceRedisService, String deviceSn) {
        return deviceRedisService.getDeviceOsd(deviceSn, OsdRcDrone.class)
                .map(osd -> {
                    boolean airborne = isAirborne(osd.getElevation(), osd.getHeight());
                    boolean modeOk = modeCodeCanReturnHome(osd.getModeCode());
                    log.info("return_home rc osd check. sn={}, mode={}, elevation={}, height={}, airborne={}, modeOk={}",
                            deviceSn, osd.getModeCode(), osd.getElevation(), osd.getHeight(), airborne, modeOk);
                    return airborne && modeOk;
                })
                .orElse(false);
    }

    private boolean canPublishWithDockOsd(IDeviceRedisService deviceRedisService, String deviceSn) {
        return deviceRedisService.getDeviceOsd(deviceSn, OsdDockDrone.class)
                .map(osd -> {
                    boolean airborne = isAirborne(osd.getElevation(), osd.getHeight());
                    boolean modeOk = modeCodeCanReturnHome(osd.getModeCode());
                    log.info("return_home dock osd check. sn={}, mode={}, elevation={}, height={}, airborne={}, modeOk={}",
                            deviceSn, osd.getModeCode(), osd.getElevation(), osd.getHeight(), airborne, modeOk);
                    return airborne && modeOk;
                })
                .orElse(false);
    }

    private boolean isAirborne(Float elevation, Float height) {
        return (elevation != null && elevation > 0) || (height != null && height > 0);
    }

    private boolean modeCodeCanReturnHome(DroneModeCodeEnum modeCode) {
        return DroneModeCodeEnum.TAKEOFF_FINISHED == modeCode || DroneModeCodeEnum.TAKEOFF_AUTO == modeCode
                || DroneModeCodeEnum.WAYLINE == modeCode || DroneModeCodeEnum.PANORAMIC_SHOT == modeCode
                || DroneModeCodeEnum.ACTIVE_TRACK == modeCode || DroneModeCodeEnum.APAS == modeCode
                || DroneModeCodeEnum.VIRTUAL_JOYSTICK == modeCode || DroneModeCodeEnum.LIVE_FLIGHT_CONTROLS == modeCode
                || DroneModeCodeEnum.MANUAL == modeCode;
    }
}
