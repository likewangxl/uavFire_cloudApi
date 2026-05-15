package com.yx.uavfire.fc100.event.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FireEventCreateResponse {
    private Long fireEventId;
    private String eventId;
    private Boolean missionCreated;
    private String missionNo;
    private String status;
}
