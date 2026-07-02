package com.yx.uavfire.fc100.operation.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AssignOperationResourceParam {
    @NotBlank
    private String resourceSn;
    @NotBlank
    private String role;
    @NotBlank
    private String operatorId;
    private String remark;
}
