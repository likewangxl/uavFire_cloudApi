package com.yx.uavfire.wayline.agent.model.enums;

public enum WaylineAgentMethodEnum {

    WAYLINE_DISPATCH("wayline_dispatch"),
    WAYLINE_PAUSE("wayline_pause"),
    WAYLINE_RESUME("wayline_resume"),
    WAYLINE_STOP("wayline_stop"),
    WAYLINE_QUERY_BREAKPOINT("wayline_query_breakpoint");

    private final String method;

    WaylineAgentMethodEnum(String method) {
        this.method = method;
    }

    public String getMethod() {
        return method;
    }
}
