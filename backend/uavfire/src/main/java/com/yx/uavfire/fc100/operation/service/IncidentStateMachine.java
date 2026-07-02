package com.yx.uavfire.fc100.operation.service;

import com.yx.uavfire.fc100.operation.model.entity.OperationIncidentEntity;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;

import java.util.Set;

public interface IncidentStateMachine {

    OperationIncidentEntity transit(IncidentTransitCommand cmd);

    Set<OperationIncidentEvent> allowedEvents(OperationIncidentStatus current);
}
