package com.yx.uavfire.component.websocket.service;

import com.yx.uavfire.component.websocket.config.MyConcurrentWebSocketSession;
import com.dji.sdk.websocket.WebSocketMessageResponse;

import java.util.Collection;

/**
 * @author sean.zhou
 * @date 2021/11/24
 * @version 0.1
 */
public interface IWebSocketMessageService {

    /**
     * Observable delivery result for durable message dispatchers. Legacy void
     * methods intentionally retain their historical best-effort contract.
     */
    final class DeliveryResult {
        private final int intendedRecipients;
        private final int acceptedRecipients;
        private final String failure;

        public DeliveryResult(int intendedRecipients, int acceptedRecipients, String failure) {
            this.intendedRecipients = intendedRecipients;
            this.acceptedRecipients = acceptedRecipients;
            this.failure = failure;
        }

        public int getIntendedRecipients() { return intendedRecipients; }
        public int getAcceptedRecipients() { return acceptedRecipients; }
        public String getFailure() { return failure; }
        public boolean isDelivered() {
            return intendedRecipients > 0 && acceptedRecipients > 0 && failure == null;
        }
    }

    /**
     * Send a message to the specific connection.
     * @param session   A WebSocket connection object
     * @param message   message
     */
    void sendMessage(MyConcurrentWebSocketSession session, WebSocketMessageResponse message);

    /**
     * Send the same message to specific connection.
     * @param sessions  A collection of WebSocket connection objects.
     * @param message   message
     */
    void sendBatch(Collection<MyConcurrentWebSocketSession> sessions, WebSocketMessageResponse message);

    void sendBatch(String workspaceId, Integer userType, String bizCode, Object data);

    void sendBatch(String workspaceId, String bizCode, Object data);

    /**
     * Sends to every currently connected session in the workspace and exposes
     * zero-recipient, closed-session and I/O failures to the caller.
     */
    DeliveryResult sendStrict(String workspaceId, String bizCode, Object data);
}
