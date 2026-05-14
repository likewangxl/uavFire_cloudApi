package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DualStreamAgentHeartbeatDTO {

    private String droneSn;

    private String connectionState;

    private String sessionState;
}
