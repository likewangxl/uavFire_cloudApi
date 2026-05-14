package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.DeviceHmsDTO;
import com.yx.uavfire.manage.model.param.DeviceHmsQueryParam;
import com.dji.sdk.common.PaginationData;

/**
 * @author sean
 * @version 1.1
 * @date 2022/7/6
 */
public interface IDeviceHmsService {

    /**
     * Query hms data by paging according to query parameters.
     * @param param
     * @return
     */
    PaginationData<DeviceHmsDTO> getDeviceHmsByParam(DeviceHmsQueryParam param);

    /**
     * Read message handling.
     * @param deviceSn
     */
    void updateUnreadHms(String deviceSn);
}
