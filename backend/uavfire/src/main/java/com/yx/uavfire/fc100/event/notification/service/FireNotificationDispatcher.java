package com.yx.uavfire.fc100.event.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.component.websocket.model.BizCodeEnum;
import com.yx.uavfire.component.websocket.service.IWebSocketMessageService;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class FireNotificationDispatcher {
    private static final int BATCH_SIZE = 25;
    private final FireNotificationOutboxStore store;
    private final IWebSocketMessageService webSocket;
    private final ObjectMapper json;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean workerScheduled = new AtomicBoolean();
    private final AtomicLong requestedEpoch = new AtomicLong();

    public FireNotificationDispatcher(FireNotificationOutboxStore store,
                                      IWebSocketMessageService webSocket,
                                      ObjectMapper json,
                                      Clock clock,
                                      @Qualifier("fireNotificationExecutor") Executor executor) {
        this.store = store; this.webSocket = webSocket; this.json = json; this.clock = clock; this.executor = executor;
    }

    public void wakeAfterCommit() {
        requestedEpoch.incrementAndGet();
        scheduleWorker();
    }

    private void scheduleWorker() {
        if (!workerScheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drainCommittedWakes);
        } catch (RuntimeException rejected) {
            workerScheduled.set(false);
            log.warn("Fire notification after-commit wake rejected", rejected);
        }
    }

    private void drainCommittedWakes() {
        long handledEpoch = 0L;
        while (true) {
            long targetEpoch = requestedEpoch.get();
            try { dispatchDue(); }
            catch (RuntimeException failure) { log.warn("Fire notification after-commit dispatch failed", failure); }
            handledEpoch = targetEpoch;
            if (requestedEpoch.get() != handledEpoch) continue;

            workerScheduled.set(false);
            if (requestedEpoch.get() == handledEpoch) return;
            // A wake raced with the final clear. Either this worker reacquires
            // ownership and drains it, or the waking caller already scheduled
            // the sole executor and this worker can return.
            if (!workerScheduled.compareAndSet(false, true)) return;
        }
    }

    @Scheduled(initialDelayString = "${fire.notification.initial-delay-ms:5000}",
        fixedDelayString = "${fire.notification.scan-delay-ms:1000}")
    public void scheduledDispatch() {
        try { dispatchDue(); }
        catch (RuntimeException failure) { log.warn("Scheduled fire notification dispatch failed", failure); }
    }

    public int dispatchDue() {
        long claimTime = clock.now();
        List<FireNotificationOutboxEntity> rows = store.claim(BATCH_SIZE, claimTime);
        int sent = 0;
        for (FireNotificationOutboxEntity row : rows) {
            try {
                FireNotificationPayload payload = json.readValue(row.getPayload(), FireNotificationPayload.class);
                IWebSocketMessageService.DeliveryResult result = webSocket.sendStrict(row.getWorkspaceId(),
                    BizCodeEnum.FIRE_EVENT_UPDATE.getCode(), payload);
                long completedAt = clock.now();
                if (result.isDelivered()) {
                    if (store.markSent(row, completedAt)) sent++;
                } else {
                    retrySafely(row, completedAt, result.getFailure());
                }
            } catch (Exception failure) {
                retrySafely(row, clock.now(), failure.getMessage());
            }
        }
        return sent;
    }

    private void retrySafely(FireNotificationOutboxEntity row, long now, String failure) {
        try {
            store.retry(row, now, failure);
        } catch (RuntimeException persistenceFailure) {
            // The lease expiry is the final crash-recovery backstop. Continue
            // the batch so one corrupt or temporarily unwriteable row cannot
            // prevent delivery for an unrelated fire event.
            log.warn("Failed to reschedule fire notification {}", row.getNotificationId(), persistenceFailure);
        }
    }
}
