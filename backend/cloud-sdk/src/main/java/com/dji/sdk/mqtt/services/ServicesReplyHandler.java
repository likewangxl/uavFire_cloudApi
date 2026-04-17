package com.dji.sdk.mqtt.services;

import com.dji.sdk.cloudapi.log.FileUploadListResponse;
import com.dji.sdk.cloudapi.log.LogMethodEnum;
import com.dji.sdk.common.Common;
import com.dji.sdk.mqtt.Chan;
import com.dji.sdk.mqtt.ChannelName;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/**
 * @author sean
 * @version 1.2
 * @date 2022/9/9
 */
@Component
public class ServicesReplyHandler {

    private static final Logger log = LoggerFactory.getLogger(ServicesReplyHandler.class);

    /**
     * Handle the reply message from topic "/services_reply".
     * @param message   reply message
     * @throws IOException
     */
    @ServiceActivator(inputChannel = ChannelName.INBOUND_SERVICES_REPLY)
    public void servicesReply(Message<?> message) throws IOException {
        byte[] payload = (byte[])message.getPayload();
        // 提到 INFO：飞机回 services_reply 时如果带 extra_error_info / 自定义 output，
        // 这是排查 336002 之类"飞机端拒绝"的唯一原始证据
        log.info("services_reply raw payload: {}", new String(payload));

        TopicServicesResponse<ServicesReplyReceiver> receiver = Common.getObjectMapper()
                .readValue(payload, new TypeReference<TopicServicesResponse<ServicesReplyReceiver>>() {});
        Chan chan = Chan.getInstance(receiver.getTid(), false);
        if (Objects.isNull(chan)) {
            return;
        }
        if (LogMethodEnum.FILE_UPLOAD_LIST.getMethod().equals(receiver.getMethod())) {
            receiver.getData().setOutput(Common.getObjectMapper().convertValue(receiver.getData(),
                    new TypeReference<FileUploadListResponse>() {}));
        }
        chan.put(receiver);
    }
}
