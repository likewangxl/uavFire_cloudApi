package com.yx.uavfire.fc100.event.model.dto;

import com.yx.uavfire.fc100.operation.model.dto.OperationIncidentDTO;
import lombok.Data;

@Data
public class FireEventDecisionResult {
    private FireEventDTO fireEvent;
    private OperationIncidentDTO incident;
    private boolean reusedIncident;
    private boolean draftMissionCreated;
    private String draftMissionNo;
    private boolean recommendedRecheck;
    private String recheckReason;
}
