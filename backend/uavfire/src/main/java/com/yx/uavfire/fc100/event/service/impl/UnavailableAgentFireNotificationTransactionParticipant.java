package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException;
import com.yx.uavfire.fc100.event.service.AgentFireNotificationTransactionParticipant;
import org.springframework.stereotype.Service;

/** Production stays fail-closed until Task 12 supplies a durable same-transaction Outbox. */
@Service
public class UnavailableAgentFireNotificationTransactionParticipant
        implements AgentFireNotificationTransactionParticipant {
    @Override
    public boolean enqueue(FireEventEntity event, AgentFireReportParam report, int notificationVersion) {
        throw new AgentFireIngressUnavailableException("durable fire notification Outbox is not available");
    }
}
