package com.yx.uavfire.fc100.event.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.component.websocket.model.BizCodeEnum;
import com.yx.uavfire.component.websocket.service.IWebSocketMessageService;
import com.yx.uavfire.fc100.event.notification.model.FireNotificationOutboxEntity;
import com.yx.uavfire.fc100.event.notification.service.FireNotificationDispatcher;
import com.yx.uavfire.fc100.event.notification.service.FireNotificationOutboxStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FireNotificationDispatcherTest {
    private final FireNotificationOutboxStore store = mock(FireNotificationOutboxStore.class);
    private final IWebSocketMessageService webSocket = mock(IWebSocketMessageService.class);
    private final Executor direct = Runnable::run;
    private final FireNotificationDispatcher dispatcher = new FireNotificationDispatcher(
        store, webSocket, new ObjectMapper(), () -> 1_000L, direct);

    @Test void zeroRecipientsIsTransientAndNeverMarkedSent() {
        FireNotificationOutboxEntity row = row(1L, "{}", "lease-1");
        when(store.claim(25, 1_000L)).thenReturn(Collections.singletonList(row));
        when(webSocket.sendStrict(eq("workspace-1"), eq("fire_event_update"), any()))
            .thenReturn(new IWebSocketMessageService.DeliveryResult(0, 0, "no connected workspace recipients"));
        assertEquals(0, dispatcher.dispatchDue());
        verify(store).retry(row, 1_000L, "no connected workspace recipients");
        verify(store, never()).markSent(any(), anyLong());
    }

    @Test void successMarksSentOnlyAfterObservableWorkspaceDelivery() {
        FireNotificationOutboxEntity row = row(1L, "{}", "lease-1");
        when(store.claim(25, 1_000L)).thenReturn(Collections.singletonList(row));
        when(webSocket.sendStrict(eq("workspace-1"), eq(BizCodeEnum.FIRE_EVENT_UPDATE.getCode()), any()))
            .thenReturn(new IWebSocketMessageService.DeliveryResult(2, 2, null));
        when(store.markSent(row, 1_000L)).thenReturn(true);
        assertEquals(1, dispatcher.dispatchDue());
    }

    @Test void poisonRowDoesNotBlockIndependentEvent() {
        FireNotificationOutboxEntity poison = row(1L, "not-json", "lease-1");
        FireNotificationOutboxEntity healthy = row(2L, "{}", "lease-2");
        when(store.claim(25, 1_000L)).thenReturn(Arrays.asList(poison, healthy));
        when(webSocket.sendStrict(any(), any(), any()))
            .thenReturn(new IWebSocketMessageService.DeliveryResult(1, 1, null));
        when(store.markSent(healthy, 1_000L)).thenReturn(true);
        assertEquals(1, dispatcher.dispatchDue());
        verify(store).retry(eq(poison), eq(1_000L), any());
        verify(store).markSent(healthy, 1_000L);
    }

    @Test void failedPoisonRescheduleStillDoesNotBlockIndependentEvent() {
        FireNotificationOutboxEntity poison = row(1L, "not-json", "lease-1");
        FireNotificationOutboxEntity healthy = row(2L, "{}", "lease-2");
        when(store.claim(25, 1_000L)).thenReturn(Arrays.asList(poison, healthy));
        when(store.retry(eq(poison), eq(1_000L), any())).thenThrow(new RuntimeException("database hiccup"));
        when(webSocket.sendStrict(any(), any(), any()))
            .thenReturn(new IWebSocketMessageService.DeliveryResult(1, 1, null));
        when(store.markSent(healthy, 1_000L)).thenReturn(true);
        assertEquals(1, dispatcher.dispatchDue());
        verify(store).markSent(healthy, 1_000L);
    }

    @Test void surfacedSendExceptionRetriesAndDoesNotEscapeScheduler() {
        FireNotificationOutboxEntity row = row(1L, "{}", "lease-1");
        when(store.claim(25, 1_000L)).thenReturn(Collections.singletonList(row));
        when(webSocket.sendStrict(any(), any(), any())).thenThrow(new RuntimeException("socket exploded"));
        assertDoesNotThrow(dispatcher::scheduledDispatch);
        verify(store).retry(row, 1_000L, "socket exploded");
    }

    @Test void afterCommitWakeRejectionCannotEscapeCommittedCaller() {
        Executor rejected = command -> { throw new java.util.concurrent.RejectedExecutionException("shutdown"); };
        FireNotificationDispatcher rejecting = new FireNotificationDispatcher(
            store, webSocket, new ObjectMapper(), () -> 1_000L, rejected);
        assertDoesNotThrow(rejecting::wakeAfterCommit);
        verifyNoInteractions(webSocket);
    }

    private FireNotificationOutboxEntity row(long id, String payload, String lease) {
        FireNotificationOutboxEntity row = new FireNotificationOutboxEntity(); row.setId(id); row.setPayload(payload);
        row.setWorkspaceId("workspace-1"); row.setLeaseToken(lease); row.setAttempts(1); return row;
    }
}
