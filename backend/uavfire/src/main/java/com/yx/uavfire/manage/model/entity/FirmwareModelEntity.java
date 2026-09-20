package com.yx.uavfire.manage.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @author sean
 * @version 1.3
 * @date 2022/12/21
 */
@Data
@TableName(value = "manage_firmware_model", autoResultMap = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FirmwareModelEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("firmware_id")
    private String firmwareId;

    @TableField(value = "device_name", typeHandler = com.yx.uavfire.common.model.OfficialDeviceNameTypeHandler.class)
    private String deviceName;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Long createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Long updateTime;
}
