package com.yx.uavfire.component.websocket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.component.websocket.config.MyConcurrentWebSocketSession;
import com.yx.uavfire.component.websocket.service.IWebSocketManageService;
import com.yx.uavfire.component.websocket.service.IWebSocketMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSocketMessageServiceImplStrictTest {
    private final IWebSocketManageService manage = mock(IWebSocketManageService.class);
    private final WebSocketMessageServiceImpl service = new WebSocketMessageServiceImpl();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "mapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "webSocketManageService", manage);
    }

    @Test void noWorkspaceSessionIsObservableFailure() {
        when(manage.getValueWithWorkspace("workspace-1")).thenReturn(Collections.emptyList());
        IWebSocketMessageService.DeliveryResult result = service.sendStrict("workspace-1", "fire_event_update", "payload");
        assertFalse(result.isDelivered()); assertEquals(0, result.getIntendedRecipients());
    }

    @Test void everyIntendedRecipientMustHaveNoSurfacedFailure() throws Exception {
        MyConcurrentWebSocketSession accepted = mock(MyConcurrentWebSocketSession.class);
        MyConcurrentWebSocketSession failed = mock(MyConcurrentWebSocketSession.class);
        when(accepted.isOpen()).thenReturn(true); when(failed.isOpen()).thenReturn(true);
        doThrow(new IOException("network down")).when(failed).sendMessage(any(TextMessage.class));
        when(manage.getValueWithWorkspace("workspace-1")).thenReturn(Arrays.asList(accepted, failed));
        IWebSocketMessageService.DeliveryResult result = service.sendStrict("workspace-1", "fire_event_update", "payload");
        assertFalse(result.isDelivered()); assertEquals(1, result.getAcceptedRecipients());
        assertEquals("network down", result.getFailure());
    }

    @Test void atLeastOneSuccessfulRecipientWithNoFailureIsDelivered() throws Exception {
        MyConcurrentWebSocketSession accepted = mock(MyConcurrentWebSocketSession.class);
        when(accepted.isOpen()).thenReturn(true);
        when(manage.getValueWithWorkspace("workspace-1")).thenReturn(Collections.singletonList(accepted));
        IWebSocketMessageService.DeliveryResult result = service.sendStrict("workspace-1", "fire_event_update", "payload");
        assertTrue(result.isDelivered()); assertEquals(1, result.getAcceptedRecipients());
        verify(accepted).sendMessage(any(TextMessage.class));
    }
}
