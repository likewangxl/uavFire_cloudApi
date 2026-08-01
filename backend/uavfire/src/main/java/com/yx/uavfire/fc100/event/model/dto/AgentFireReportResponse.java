package com.yx.uavfire.fc100.event.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentFireReportResponse {
    private String eventId;
    private long acceptedSequence;
    private boolean eventPersisted;
    private Boolean notificationQueued;
    private boolean duplicate;
}
