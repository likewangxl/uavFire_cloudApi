package com.yx.uavfire.fc100.operation.compliance.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RecordTakeoffConfirmationParam {
    @NotNull
    private Long incidentId;
    @NotBlank
    private String operatorId;
    private String confirmationNo;
    private String materialUrl;
    private Long confirmedAt;
}
