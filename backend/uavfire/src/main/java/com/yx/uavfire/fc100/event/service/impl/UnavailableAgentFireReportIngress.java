package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException;
import com.yx.uavfire.fc100.event.service.AgentFireReportIngress;

/** Fail-closed production placeholder until Task 11 supplies durable ordered persistence. */
public class UnavailableAgentFireReportIngress implements AgentFireReportIngress {
    @Override public Result accept(AgentFireReportParam report, byte[] rawPayload, String payloadSha256) {
        throw new AgentFireIngressUnavailableException("agent fire ingress persistence is not available");
    }
}
