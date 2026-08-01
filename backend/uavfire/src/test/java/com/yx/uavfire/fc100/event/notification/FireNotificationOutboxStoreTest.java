package com.yx.uavfire.fc100.event.notification;

import com.yx.uavfire.fc100.event.notification.dao.FireNotificationOutboxMapper;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import com.yx.uavfire.fc100.event.notification.service.FireNotificationOutboxStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FireNotificationOutboxStoreTest {
    private final FireNotificationOutboxMapper mapper = mock(FireNotificationOutboxMapper.class);
    private final FireNotificationOutboxStore store = new FireNotificationOutboxStore(mapper);

    @Test void claimsWithUniqueLeaseTokensAndBoundedBatch() {
        FireNotificationOutboxEntity one = row(1L, 0), two = row(2L, 2);
        when(mapper.selectEligibleForClaim(1_000L, 100)).thenReturn(Arrays.asList(one, two));
        when(mapper.claim(anyLong(), anyString(), eq(11_000L), eq(1_000L))).thenReturn(1);
        List<FireNotificationOutboxEntity> claimed = store.claim(500, 1_000L);
        assertEquals(2, claimed.size()); assertNotEquals(claimed.get(0).getLeaseToken(), claimed.get(1).getLeaseToken());
        assertEquals(1, claimed.get(0).getAttempts()); assertEquals(3, claimed.get(1).getAttempts());
    }

    @Test void staleLeaseCompletionCannotMutateRow() {
        FireNotificationOutboxEntity row = row(1L, 1); row.setLeaseToken("stale");
        when(mapper.completeSuccess(1L, "stale", 2_000L)).thenReturn(0);
        assertFalse(store.markSent(row, 2_000L));
    }

    @Test void retriesUseDeterministicCappedBackoffAndPreserveLeaseOwnership() {
        FireNotificationOutboxEntity row = row(7L, 1); row.setLeaseToken("lease");
        when(mapper.completeFailure(anyLong(), anyString(), anyLong(), anyLong(), anyString())).thenReturn(1);
        assertTrue(store.retry(row, 1_000L, "zero recipients"));
        verify(mapper).completeFailure(7L, "lease", 1_250L, 1_000L, "zero recipients");

        row.setAttempts(99);
        store.retry(row, 2_000L, "poison");
        verify(mapper).completeFailure(7L, "lease", 7_000L, 2_000L, "poison");
    }

    @Test void mapperClaimQueryExplicitlyRecoversExpiredInFlightRows() throws Exception {
        String sql = String.join(" ", FireNotificationOutboxMapper.class
            .getMethod("selectEligibleForClaim", long.class, int.class)
            .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(sql.contains("status='IN_FLIGHT' AND lease_expires_at<=#{now}"));
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"));
    }

    private FireNotificationOutboxEntity row(long id, int attempts) {
        FireNotificationOutboxEntity row = new FireNotificationOutboxEntity(); row.setId(id); row.setAttempts(attempts); return row;
    }
}
