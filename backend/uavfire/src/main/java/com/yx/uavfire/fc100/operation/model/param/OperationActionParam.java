package com.yx.uavfire.fc100.operation.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class OperationActionParam {
    @NotBlank
    private String operatorId;
    private String reason;
}
