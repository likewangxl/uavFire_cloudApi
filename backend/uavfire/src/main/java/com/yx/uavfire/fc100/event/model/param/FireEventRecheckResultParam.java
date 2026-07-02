package com.yx.uavfire.fc100.event.model.param;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class FireEventRecheckResultParam {
    @NotBlank
    private String operatorId;
    @NotNull
    private Double maxTemp;
    @NotNull
    private Double hotAreaM2;
    @NotNull
    private Boolean flameVisible;
    @NotBlank
    private String suggestion;
    private String remark;
}
