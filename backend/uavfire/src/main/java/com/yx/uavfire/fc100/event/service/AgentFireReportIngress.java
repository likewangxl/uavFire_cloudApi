package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;

/** Task 11 implements the one-transaction ordered persistence behind this port. */
public interface AgentFireReportIngress {
    /**
     * Accepts the parsed report together with the exact authenticated HTTP body and its SHA-256.
     * The byte identity is part of the idempotency contract; callers must not reserialize the DTO.
     */
    Result accept(AgentFireReportParam report, byte[] rawPayload, String payloadSha256);

    final class Result {
        public enum Status { COMMITTED, EXACT_DUPLICATE, CONFLICT }
        private final Status status;
        private final boolean notificationQueued;
        private final String reason;

        private Result(Status status, boolean notificationQueued, String reason) {
            this.status = status;
            this.notificationQueued = notificationQueued;
            this.reason = reason;
        }
        public static Result committed(boolean queued) { return new Result(Status.COMMITTED, queued, null); }
        public static Result duplicate(boolean queued) { return new Result(Status.EXACT_DUPLICATE, queued, null); }
        public static Result conflict(String reason) { return new Result(Status.CONFLICT, false, reason); }
        public Status getStatus() { return status; }
        public boolean isNotificationQueued() { return notificationQueued; }
        public String getReason() { return reason; }
    }
}
