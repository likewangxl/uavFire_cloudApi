package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.component.websocket.model.BizCodeEnum;
import com.yx.uavfire.manage.model.dto.CloudControlAuthStateDTO;
import com.yx.uavfire.manage.model.dto.DeviceDTO;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.yx.uavfire.manage.service.IDeviceService;
import com.dji.sdk.mqtt.state.TopicStateRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudControlAuthStatePushServiceTest {

    @Test
    void cloudControlAuthTapDoesNotCompeteWithDefaultChannelSubscriber() throws Exception {
        CloudControlAuthStatePushService service = new CloudControlAuthStatePushService();
        IDeviceRedisService deviceRedisService = mock(IDeviceRedisService.class);
        IDeviceService deviceService = mock(IDeviceService.class);

        DeviceDTO gateway = new DeviceDTO();
        gateway.setWorkspaceId("workspace-1");
        when(deviceRedisService.getDeviceOnline("RC-1")).thenReturn(Optional.of(gateway));

        ReflectionTestUtils.setField(service, "resolver", new CloudControlAuthStateResolver());
        ReflectionTestUtils.setField(service, "deviceRedisService", deviceRedisService);
        ReflectionTestUtils.setField(service, "deviceService", deviceService);

        Method factory = CloudControlAuthStatePushService.class
                .getDeclaredMethod("cloudControlAuthStateInterceptor");
        factory.setAccessible(true);
        ChannelInterceptor interceptor = (ChannelInterceptor) factory.invoke(service);

        DirectChannel defaultChannel = new DirectChannel();
        defaultChannel.addInterceptor(interceptor);
        AtomicInteger defaultSubscriberCalls = new AtomicInteger();
        defaultChannel.subscribe(message -> defaultSubscriberCalls.incrementAndGet());

        TopicStateRequest<Object> request = new TopicStateRequest<>()
                .setFrom("RC-1")
                .setData(Map.of("cloud_control_auth", List.of("flight")));
        assertTrue(defaultChannel.send(MessageBuilder.withPayload(request).build()));

        assertEquals(1, defaultSubscriberCalls.get());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(deviceService).pushOsdDataToWeb(
                eq("workspace-1"),
                eq(BizCodeEnum.CLOUD_CONTROL_AUTH_UPDATE),
                eq("RC-1"),
                payload.capture());
        CloudControlAuthStateDTO dto = (CloudControlAuthStateDTO) payload.getValue();
        assertTrue(dto.isAuthorized());
        assertEquals(List.of("flight"), dto.getControlKeys());
    }
}
