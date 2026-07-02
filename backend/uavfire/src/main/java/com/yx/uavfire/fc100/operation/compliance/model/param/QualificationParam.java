package com.yx.uavfire.fc100.operation.compliance.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class QualificationParam {
    @NotBlank
    private String qualificationType;
    @NotBlank
    private String qualificationNo;
    private String issuer;
    private Long validFrom;
    private Long validTo;
    private String materialUrl;
    private String status;
    @NotBlank
    private String operatorId;
}
