package com.yx.uavfire.wayline.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaylineEventEnvelopeDTO {

    private String tid;

    private String bid;

    private Long timestamp;

    private String method;

    /** Original JSON of the `data` block; concrete payload decoded by the dispatcher. */
    private com.fasterxml.jackson.databind.JsonNode data;
}
