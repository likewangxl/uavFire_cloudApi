package com.yx.uavfire.fc100.operation.compliance.model.param;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class PreflightCheckParam {
    @NotNull
    private Long incidentId;
    private String operatorId;
}
