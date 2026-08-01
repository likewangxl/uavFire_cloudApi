package com.yx.uavfire.fc100.event.notification.model;

import lombok.Data;

/** Stable, UI-deduplicable WebSocket contract for one fire notification version. */
@Data
public class FireNotificationPayload {
    private String notificationId;
    private String eventId;
    private Integer notificationVersion;
    private String workspaceId;
    private String taskId;
    private String droneSn;
    private String detectionKind;
    private String state;
    private String locationStatus;
    private String flightStatus;
    private Double fireLat;
    private Double fireLng;
    private Double fireAlt;
    private Double aircraftLat;
    private Double aircraftLng;
    private Double aircraftAlt;
    private Long eventTimestamp;
    private String message;
}
