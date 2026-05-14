package com.dji.sample.control.model.dto;

import com.dji.sample.common.util.SpringBeanUtilsTest;
import com.dji.sample.control.service.impl.RemoteDebugHandler;
import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sample.manage.service.IDeviceService;
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
public class ReturnHomeCancelState extends RemoteDebugHandler {

    @Override
    public boolean canPublish(String sn) {
        IDeviceRedisService deviceRedisService = SpringBeanUtilsTest.getBean(IDeviceRedisService.class);
        IDeviceService deviceService = SpringBeanUtilsTest.getBean(IDeviceService.class);
        boolean canPublish = canCancelForSn(deviceRedisService, deviceService, sn);
        log.info("return_home_cancel canPublish check. sn={}, result={}", sn, canPublish);
        return canPublish;
    }

    private boolean canCancelForSn(IDeviceRedisService deviceRedisService, IDeviceService deviceService, String sn) {
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

        log.info("return_home_cancel candidate sns. sn={}, candidates={}", sn, candidateSns);
        for (String candidateSn : candidateSns) {
            if (canCancelWithRcOsd(deviceRedisService, candidateSn) || canCancelWithDockOsd(deviceRedisService, candidateSn)) {
                log.info("return_home_cancel canPublish matched. sn={}, candidateSn={}", sn, candidateSn);
                return true;
            }
        }
        return false;
    }

    private boolean canCancelWithRcOsd(IDeviceRedisService deviceRedisService, String deviceSn) {
        return deviceRedisService.getDeviceOsd(deviceSn, OsdRcDrone.class)
                .map(osd -> {
                    boolean ok = DroneModeCodeEnum.RETURN_AUTO == osd.getModeCode();
                    log.info("return_home_cancel rc osd check. sn={}, mode={}, ok={}",
                            deviceSn, osd.getModeCode(), ok);
                    return ok;
                })
                .orElse(false);
    }

    private boolean canCancelWithDockOsd(IDeviceRedisService deviceRedisService, String deviceSn) {
        return deviceRedisService.getDeviceOsd(deviceSn, OsdDockDrone.class)
                .map(osd -> {
                    boolean ok = DroneModeCodeEnum.RETURN_AUTO == osd.getModeCode();
                    log.info("return_home_cancel dock osd check. sn={}, mode={}, ok={}",
                            deviceSn, osd.getModeCode(), ok);
                    return ok;
                })
                .orElse(false);
    }

}
