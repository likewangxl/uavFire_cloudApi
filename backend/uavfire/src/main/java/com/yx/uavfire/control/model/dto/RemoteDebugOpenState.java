package com.yx.uavfire.control.model.dto;

import com.yx.uavfire.common.util.SpringBeanUtilsTest;
import com.yx.uavfire.control.service.impl.RemoteDebugHandler;
import com.yx.uavfire.manage.service.IDeviceService;
import com.dji.sdk.cloudapi.device.DockModeCodeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author sean
 * @version 1.4
 * @date 2023/4/14
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class RemoteDebugOpenState extends RemoteDebugHandler {

    @Override
    public boolean canPublish(String sn) {
        IDeviceService deviceService = SpringBeanUtilsTest.getBean(IDeviceService.class);
        DockModeCodeEnum dockMode = deviceService.getDockMode(sn);
        return DockModeCodeEnum.IDLE == dockMode;
    }
}
