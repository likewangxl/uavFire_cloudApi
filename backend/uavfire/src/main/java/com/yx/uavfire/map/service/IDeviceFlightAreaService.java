package com.yx.uavfire.map.service;

import com.yx.uavfire.map.model.dto.DeviceFlightAreaDTO;

import java.util.Optional;

/**
 * @author sean
 * @version 1.9
 * @date 2023/11/23
 */
public interface IDeviceFlightAreaService {

    Optional<DeviceFlightAreaDTO> getDeviceFlightAreaByDevice(String workspaceId, String deviceSn);

    Boolean updateDeviceFile(DeviceFlightAreaDTO deviceFile);

    Boolean updateOrSaveDeviceFile(DeviceFlightAreaDTO deviceFile);
}
