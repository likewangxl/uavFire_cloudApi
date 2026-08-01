package com.yx.uavfire.fc100.event.service;

/** Durable Outbox identity exists with different immutable content. */
public class AgentFireNotificationConflictException extends RuntimeException {
    public AgentFireNotificationConflictException(String message) { super(message); }
}
