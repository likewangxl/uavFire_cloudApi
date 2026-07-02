package com.yx.uavfire.fc100.event.model.dto;

import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import lombok.Data;

@Data
public class FireEventRecheckResultDTO {
    private Long fireEventId;
    private Long incidentId;
    private boolean saturated;
    private boolean resolved;
    private String reason;
    private OperationIncidentDTO incident;
}
