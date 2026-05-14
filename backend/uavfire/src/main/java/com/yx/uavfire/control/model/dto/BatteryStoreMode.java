package com.yx.uavfire.control.model.dto;

import com.yx.uavfire.control.service.impl.RemoteDebugHandler;
import com.dji.sdk.cloudapi.device.BatteryStoreModeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * @author sean
 * @version 1.3
 * @date 2022/11/14
 */
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BatteryStoreMode extends RemoteDebugHandler {

    private BatteryStoreModeEnum action;
}
