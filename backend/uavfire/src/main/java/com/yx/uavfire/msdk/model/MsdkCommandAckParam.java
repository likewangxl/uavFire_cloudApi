package com.yx.uavfire.msdk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MsdkCommandAckParam {

    private String commandId;

    private String status;

    private String message;
}
