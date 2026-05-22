package com.yx.uavfire.msdk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MsdkCommandParam {

    private String command;

    private Map<String, Object> params;
}
