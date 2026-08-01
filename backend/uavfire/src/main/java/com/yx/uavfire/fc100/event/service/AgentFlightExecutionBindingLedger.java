package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.wayline.model.entity.PlannedWaylineEntity;

public interface AgentFlightExecutionBindingLedger {
    void recordPrepared(PlannedWaylineEntity task, long preparedAt);
    void recordExecutionStarted(PlannedWaylineEntity task, long startedAt);
    void recordRuntimeStatus(String flightId, String droneSn, String status, long observedAt);
}
