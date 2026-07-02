package com.yx.uavfire.fc100.operation.service;

import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class IncidentTransitCommand {
    Long incidentId;
    OperationIncidentEvent event;
    OperationIncidentStatus expectedFrom;
    String operatorId;
    String operatorRole;
    String clientIp;
    String requestId;
    String idempotencyKey;
    String remark;
}
