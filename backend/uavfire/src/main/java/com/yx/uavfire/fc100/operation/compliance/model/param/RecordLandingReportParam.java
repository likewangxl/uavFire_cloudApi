package com.yx.uavfire.fc100.operation.compliance.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RecordLandingReportParam {
    @NotNull
    private Long incidentId;
    private String reportNo;
    private String materialUrl;
    @NotBlank
    private String operatorId;
    private Long landedAt;
}
