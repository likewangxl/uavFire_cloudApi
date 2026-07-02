package com.yx.uavfire.fc100.operation.compliance.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RecordFlightApplicationParam {
    @NotNull
    private Long incidentId;
    @NotBlank
    private String applicationNo;
    @NotBlank
    private String approvalNo;
    private Long validFrom;
    private Long validTo;
    private String materialUrl;
    @NotBlank
    private String operatorId;
}
