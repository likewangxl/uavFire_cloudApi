package com.dji.sdk.mqtt;

import com.dji.sdk.common.Common;
import com.dji.sdk.exception.CloudSDKErrorEnum;
import com.dji.sdk.exception.CloudSDKException;
import com.dji.sdk.websocket.api.WebSocketMessageSend;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author sean.zhou
 * @date 2021/11/16
 * @version 0.1
 */
@Component
public class MqttGatewayPublish {

    private static final Logger log = LoggerFactory.getLogger(WebSocketMessageSend.class);

    private static final int DEFAULT_QOS = 0;
    public static final int DEFAULT_RETRY_COUNT = 2;
    public static final int DEFAULT_RETRY_TIMEOUT = 3000;

    @Resource
    private IMqttMessageGateway messageGateway;

    public void publish(String topic, int qos, CommonTopicRequest request) {
        try {
            // 提到 INFO：要核对真实下发 MQTT topic + JSON payload，必须拿到原始字节级别的
            // 调用记录，否则 toString 不能还原 @JsonProperty 字段名。
            byte[] payload = Common.getObjectMapper().writeValueAsBytes(request);
            log.info("MQTT send topic: {}, qos={}, payload: {}", topic, qos, new String(payload));
            messageGateway.publish(topic, payload, qos);
        } catch (JsonProcessingException e) {
            log.error("Failed to publish the message. {}", request.toString());
            e.printStackTrace();
        }
    }

    public void publish(String topic, int qos, CommonTopicResponse response) {
        try {
            byte[] payload = Common.getObjectMapper().writeValueAsBytes(response);
            log.info("MQTT send topic: {}, qos={}, payload: {}", topic, qos, new String(payload));
            messageGateway.publish(topic, payload, qos);
        } catch (JsonProcessingException e) {
            log.error("Failed to publish the message. {}", response.toString());
            e.printStackTrace();
        }
    }

    public void publish(String topic, CommonTopicRequest request, int publishCount) {
        AtomicInteger time = new AtomicInteger(0);
        while (time.getAndIncrement() < publishCount) {
            this.publish(topic, DEFAULT_QOS, request);
        }
    }

    public void publish(String topic, CommonTopicRequest request) {
        this.publish(topic, DEFAULT_QOS, request);
    }

    public void publishReply(CommonTopicResponse response, MessageHeaders headers) {
        this.publish(headers.get(MqttHeaders.RECEIVED_TOPIC) + TopicConst._REPLY_SUF, 2, response);
    }

    public <T> CommonTopicResponse<T> publishWithReply(Class<T> clazz, String topic, CommonTopicRequest request, int retryCount, long timeout) {
        AtomicInteger time = new AtomicInteger(0);
        boolean hasBid = StringUtils.hasText(request.getBid());
        request.setBid(hasBid ? request.getBid() : UUID.randomUUID().toString());
        // Retry
        while (time.getAndIncrement() <= retryCount) {
            this.publish(topic, request);

            // If the message is not received in 3 seconds then resend it again.
            CommonTopicResponse<T> receiver = Chan.getInstance(request.getTid(), true).get(request.getTid(), timeout);
            // Need to match tid and bid.
            if (Objects.nonNull(receiver)
                    && receiver.getTid().equals(request.getTid())
                    && receiver.getBid().equals(request.getBid())) {
                if (clazz.isAssignableFrom(receiver.getData().getClass())) {
                    return receiver;
                }
                throw new TypeMismatchException(receiver.getData(), clazz);
            }
            // It must be guaranteed that the tid and bid of each message are different.
            if (!hasBid) {
                request.setBid(UUID.randomUUID().toString());
            }
            request.setTid(UUID.randomUUID().toString());
        }
        throw new CloudSDKException(CloudSDKErrorEnum.MQTT_PUBLISH_ABNORMAL, "No message reply received.");
    }


}