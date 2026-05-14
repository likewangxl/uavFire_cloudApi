package com.dji.sample.control.service.impl;

import com.dji.sample.component.websocket.model.BizCodeEnum;
import com.dji.sample.component.websocket.service.IWebSocketMessageService;
import com.dji.sample.control.model.dto.ResultNotifyDTO;
import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.model.enums.UserTypeEnum;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.cloudapi.control.DrcStatusErrorEnum;
import com.dji.sdk.cloudapi.control.DrcStatusNotify;
import com.dji.sdk.mqtt.MqttReply;
import com.dji.sdk.mqtt.events.TopicEventsRequest;
import com.dji.sdk.mqtt.events.TopicEventsResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SDKControlServiceTest {

    @Test
    void drcStatusNotifyAllowsResultOnlyPayloadWithoutDrcState() {
        SDKControlService service = new SDKControlService();
        IDeviceRedisService deviceRedisService = mock(IDeviceRedisService.class);
        IWebSocketMessageService webSocketMessageService = mock(IWebSocketMessageService.class);

        DeviceDTO gateway = new DeviceDTO();
        gateway.setWorkspaceId("workspace-1");
        when(deviceRedisService.getDeviceOnline("GATEWAY-1")).thenReturn(Optional.of(gateway));

        ReflectionTestUtils.setField(service, "deviceRedisService", deviceRedisService);
        ReflectionTestUtils.setField(service, "webSocketMessageService", webSocketMessageService);

        TopicEventsRequest<DrcStatusNotify> request = new TopicEventsRequest<DrcStatusNotify>()
                .setGateway("GATEWAY-1")
                .setData(new DrcStatusNotify()
                        .setResult(DrcStatusErrorEnum.MQTT_ERR));

        TopicEventsResponse<MqttReply> response = service.drcStatusNotify(
                request,
                new MessageHeaders(Map.of()));

        assertNotNull(response);
        assertEquals(0, response.getData().getResult());

        ArgumentCaptor<ResultNotifyDTO> payload = ArgumentCaptor.forClass(ResultNotifyDTO.class);
        verify(webSocketMessageService).sendBatch(
                eq("workspace-1"),
                eq(UserTypeEnum.WEB.getVal()),
                eq(BizCodeEnum.DRC_STATUS_NOTIFY.getCode()),
                payload.capture());
        assertEquals("GATEWAY-1", payload.getValue().getSn());
        assertEquals(DrcStatusErrorEnum.MQTT_ERR.getCode(), payload.getValue().getResult());
        assertEquals(DrcStatusErrorEnum.MQTT_ERR.getMessage(), payload.getValue().getMessage());
        assertNull(payload.getValue().getDrcState());
    }
}
