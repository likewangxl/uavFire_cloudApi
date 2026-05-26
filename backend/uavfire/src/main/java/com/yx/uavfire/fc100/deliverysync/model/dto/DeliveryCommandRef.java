package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DeliveryCommandRef {
    private Integer code;
    private String bid;
    private String gatewaySn;
    private String deviceSn;
    private String deviceCmdMethod;
    private String status;
    private Map<String, Object> deviceCmdData;
    private Long createTime;
    private Long updateTime;
}
