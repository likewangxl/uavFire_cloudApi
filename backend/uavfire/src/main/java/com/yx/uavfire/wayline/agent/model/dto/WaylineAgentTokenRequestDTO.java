package com.yx.uavfire.wayline.agent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class WaylineAgentTokenRequestDTO {

    private String droneSn;

    private String sharedSecret;
}
