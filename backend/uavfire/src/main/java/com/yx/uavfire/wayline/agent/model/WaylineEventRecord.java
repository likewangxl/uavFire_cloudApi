package com.yx.uavfire.wayline.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Persisted snapshot of one MQTT event received from an agent. The frontend
 * (or backend internals) can replay the timeline via WaylineEventStore.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaylineEventRecord {

    private String droneSn;
    private String missionId;
    private String method;
    private long receivedAt;
    private Object data;
}
