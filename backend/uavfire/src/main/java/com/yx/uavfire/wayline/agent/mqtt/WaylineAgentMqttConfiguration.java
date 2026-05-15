package com.yx.uavfire.wayline.agent.mqtt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;

import java.util.UUID;
import java.util.concurrent.Executors;

@Configuration
public class WaylineAgentMqttConfiguration {

    private static final String TOPIC_PATTERN = "uavfire/agent/+/events/#";

    @Autowired
    private MqttPahoClientFactory mqttClientFactory;

    @Bean(name = WaylineAgentMqttChannel.INBOUND)
    public MessageChannel waylineAgentMqttInbound() {
        return new ExecutorChannel(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wayline-agent-mqtt-inbound");
            t.setDaemon(true);
            return t;
        }));
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter waylineAgentMqttAdapter(
            @Qualifier(WaylineAgentMqttChannel.INBOUND) MessageChannel inbound) {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                "wayline-agent-" + UUID.randomUUID(), mqttClientFactory, TOPIC_PATTERN);
        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        converter.setPayloadAsBytes(true);
        adapter.setConverter(converter);
        adapter.setQos(1);
        adapter.setOutputChannel(inbound);
        return adapter;
    }
}
