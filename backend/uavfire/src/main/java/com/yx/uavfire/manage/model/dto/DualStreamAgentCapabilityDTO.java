package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import com.yx.uavfire.msdk.model.PayloadCapabilityDTO;

import java.util.List;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamAgentCapabilityDTO {

    private String droneSn;

    private Boolean visibleSupported;

    private Boolean thermalSupported;

    private String aircraftModelKey;
    private String controllerModelKey;
    private List<PayloadCapabilityDTO> payloads;
    private Integer selectedPayloadPositionIndex;
    private Boolean laserSupported;
    private Boolean fireClosedLoopReady;
    private List<String> blockingReasons;
}
