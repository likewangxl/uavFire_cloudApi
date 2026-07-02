package com.yx.uavfire.fc100.operation.model.param;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class CreateOperationIncidentParam {
    @NotNull
    private Long fireEventId;
    private String level;
    private Double centerLat;
    private Double centerLng;
    private Double riskRadiusM;
    private String createdBy;
    private String confirmedBy;
}
