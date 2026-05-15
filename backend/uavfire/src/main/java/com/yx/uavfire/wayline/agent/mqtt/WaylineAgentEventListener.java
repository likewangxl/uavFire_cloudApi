package com.yx.uavfire.wayline.agent.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class WaylineAgentEventListener {

    @ServiceActivator(inputChannel = WaylineAgentMqttChannel.INBOUND)
    public void onEvent(Message<byte[]> message) {
        String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.info("wayline-agent event topic={} payload={}", topic, payload);
    }
}
