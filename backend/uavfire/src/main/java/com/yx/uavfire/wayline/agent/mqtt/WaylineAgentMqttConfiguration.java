package com.yx.uavfire.wayline.agent.mqtt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private MqttPahoClientFactory mqttClientFactory;

    @Value("${wayline-agent.mqtt.event-topic:uavfire/agent/+/events/#}")
    private String eventTopic;

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
                "wayline-agent-" + UUID.randomUUID(), mqttClientFactory, eventTopic);
        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        converter.setPayloadAsBytes(true);
        adapter.setConverter(converter);
        adapter.setQos(1);
        adapter.setOutputChannel(inbound);
        return adapter;
    }
}
