package com.yx.uavfire.fc100.deliverysync.model.param;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class DeviceCommandRequest {
    private String missionNo;
    private String deviceSn;
    private String deviceCmdMethod;
    private Map<String, Object> deviceCmdData;
}
