package com.yx.uavfire.fc100.event.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FireEventCreateResponse {
    private Long fireEventId;
    private String eventId;
    private Boolean missionCreated;
    private String missionNo;
    private String status;
    private Boolean created;
    private Boolean merged;
    private Boolean notificationRequired;
    private String notificationReason;

    public FireEventCreateResponse(Long fireEventId, String eventId, Boolean missionCreated, String missionNo, String status) {
        this(fireEventId, eventId, missionCreated, missionNo, status, false, false, false, null);
    }

    public FireEventCreateResponse(Long fireEventId, String eventId, Boolean missionCreated, String missionNo, String status,
                                   Boolean created, Boolean merged, Boolean notificationRequired, String notificationReason) {
        this.fireEventId = fireEventId;
        this.eventId = eventId;
        this.missionCreated = missionCreated;
        this.missionNo = missionNo;
        this.status = status;
        this.created = created;
        this.merged = merged;
        this.notificationRequired = notificationRequired;
        this.notificationReason = notificationReason;
    }
}
