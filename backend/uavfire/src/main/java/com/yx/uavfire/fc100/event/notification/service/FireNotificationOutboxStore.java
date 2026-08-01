package com.yx.uavfire.fc100.event.notification.service;

import com.yx.uavfire.fc100.event.notification.dao.FireNotificationOutboxMapper;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class FireNotificationOutboxStore {
    public static final long LEASE_MILLIS = 10_000L;
    private static final long[] BACKOFF = {250L, 500L, 1_000L, 2_000L, 5_000L};
    private final FireNotificationOutboxMapper mapper;

    public FireNotificationOutboxStore(FireNotificationOutboxMapper mapper) { this.mapper = mapper; }

    @Transactional
    public List<FireNotificationOutboxEntity> claim(int requestedLimit, long now) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        List<FireNotificationOutboxEntity> eligible = mapper.selectEligibleForClaim(now, limit);
        List<FireNotificationOutboxEntity> claimed = new ArrayList<>();
        for (FireNotificationOutboxEntity row : eligible) {
            String token = UUID.randomUUID().toString();
            if (mapper.claim(row.getId(), token, now + LEASE_MILLIS, now) == 1) {
                row.setLeaseToken(token);
                row.setLeaseExpiresAt(now + LEASE_MILLIS);
                row.setStatus("IN_FLIGHT");
                row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
                claimed.add(row);
            }
        }
        return claimed;
    }

    @Transactional
    public boolean markSent(FireNotificationOutboxEntity row, long now) {
        return mapper.completeSuccess(row.getId(), row.getLeaseToken(), now) == 1;
    }

    @Transactional
    public boolean retry(FireNotificationOutboxEntity row, long now, String error) {
        int attempt = row.getAttempts() == null ? 1 : Math.max(1, row.getAttempts());
        long delay = BACKOFF[Math.min(attempt - 1, BACKOFF.length - 1)];
        String bounded = error == null ? "delivery failed" : error.substring(0, Math.min(500, error.length()));
        return mapper.completeFailure(row.getId(), row.getLeaseToken(), now + delay, now, bounded) == 1;
    }

    static long retryDelay(int attempt) {
        return BACKOFF[Math.min(Math.max(attempt, 1) - 1, BACKOFF.length - 1)];
    }
}
