package com.yx.uavfire.msdk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MsdkCommandDTO {

    private String commandId;

    private String aircraftSn;

    private String command;

    private Map<String, Object> params;

    private String status;

    private Long createdAt;

    private Long updatedAt;

    private String message;
}
