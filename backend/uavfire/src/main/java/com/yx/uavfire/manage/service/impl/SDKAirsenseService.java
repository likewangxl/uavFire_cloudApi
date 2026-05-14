package com.yx.uavfire.manage.service.impl;

import com.dji.sdk.cloudapi.airsense.AirsenseWarning;
import com.dji.sdk.cloudapi.airsense.api.AbstractAirsenseService;
import com.dji.sdk.mqtt.MqttReply;
import com.dji.sdk.mqtt.events.TopicEventsRequest;
import com.dji.sdk.mqtt.events.TopicEventsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ADS-B airsense warning handler.
 * Registers a subscriber for the inboundEventsAirsenseWarning channel so that
 * airsense_warning events from the RC are acknowledged and logged instead of
 * causing a NoSuchBeanDefinitionException.
 */
@Service
@Slf4j
public class SDKAirsenseService extends AbstractAirsenseService {

    @Override
    public TopicEventsResponse<MqttReply> airsenseWarning(
            TopicEventsRequest<List<AirsenseWarning>> request, MessageHeaders headers) {
        List<AirsenseWarning> warnings = request.getData();
        if (warnings != null && !warnings.isEmpty()) {
            log.warn("ADS-B airsense warning from [{}]: {} aircraft detected. First: icao={}, level={}, distance={}m",
                    request.getGateway(),
                    warnings.size(),
                    warnings.get(0).getIcao(),
                    warnings.get(0).getWarningLevel(),
                    warnings.get(0).getDistance());
        } else {
            log.debug("ADS-B airsense warning from [{}]: empty list", request.getGateway());
        }
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }
}
