package com.yx.uavfire.manage.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 *
 * @author sean.zhou
 * @date 2021/11/15
 * @version 0.1
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "manage_device_dictionary", autoResultMap = true)
public class DeviceDictionaryEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField(value = "domain")
    private Integer domain;

    @TableField(value = "device_type")
    private Integer deviceType;

    @TableField(value = "sub_type")
    private Integer subType;

    @TableField(value = "device_name", typeHandler = com.yx.uavfire.common.model.OfficialDeviceNameTypeHandler.class)
    private String deviceName;

    @TableField(value = "device_desc", typeHandler = com.yx.uavfire.common.model.DeviceTextTypeHandler.class)
    private String deviceDesc;

}