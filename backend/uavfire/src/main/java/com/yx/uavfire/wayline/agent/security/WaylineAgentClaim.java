package com.yx.uavfire.wayline.agent.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WaylineAgentClaim {

    public static final String ROLE = "wayline_agent";

    private String droneSn;

    private String role;
}
