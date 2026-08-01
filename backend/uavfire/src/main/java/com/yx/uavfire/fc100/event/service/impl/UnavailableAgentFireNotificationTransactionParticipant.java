package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationTransactionParticipant;

/** Production stays fail-closed until Task 12 supplies a durable same-transaction Outbox. */
public class UnavailableAgentFireNotificationTransactionParticipant
        implements AgentFireNotificationTransactionParticipant {
    @Override
    public boolean enqueue(FireEventEntity event, AgentFireReportParam report, int notificationVersion) {
        throw new AgentFireIngressUnavailableException("durable fire notification Outbox is not available");
    }
}
