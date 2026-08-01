package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.fc100.event.model.entity.FireEventEntity;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;

/**
 * Narrow same-transaction seam for Task 12's durable notification Outbox.
 * Implementations must be idempotent on (eventId, notificationVersion).
 */
public interface AgentFireNotificationTransactionParticipant {
    boolean enqueue(FireEventEntity event, AgentFireReportParam report, int notificationVersion);
}
