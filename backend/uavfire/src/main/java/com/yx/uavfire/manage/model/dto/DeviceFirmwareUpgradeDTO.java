package com.yx.uavfire.manage.model.dto;

import lombok.Data;

/**
 * @author sean
 * @version 1.2
 * @date 2022/8/16
 */
@Data
public class DeviceFirmwareUpgradeDTO {

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.yx.uavfire.common.model.DeviceModelNameSerializer.class)
    private String deviceName;

    private String sn;

    private String productVersion;

    private Integer firmwareUpgradeType;
}
