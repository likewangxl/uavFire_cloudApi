package com.yx.uavfire.manage.service.impl;

import com.yx.uavfire.component.websocket.model.BizCodeEnum;
import com.yx.uavfire.manage.model.dto.CloudControlAuthStateDTO;
import com.yx.uavfire.manage.model.dto.DeviceDTO;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.yx.uavfire.manage.service.IDeviceService;
import com.dji.sdk.mqtt.state.TopicStateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.config.GlobalChannelInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@Slf4j
public class CloudControlAuthStatePushService {

    @Autowired
    private CloudControlAuthStateResolver resolver;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IDeviceService deviceService;

    @Bean
    @GlobalChannelInterceptor(patterns = "default")
    public ChannelInterceptor cloudControlAuthStateInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                Object payload = message.getPayload();
                if (payload instanceof TopicStateRequest) {
                    handleUnknownState((TopicStateRequest<Object>) payload);
                }
                return message;
            }
        };
    }

    void handleUnknownState(TopicStateRequest<Object> request) {
        resolver.resolve(request.getData()).ifPresent(state -> {
            String gatewaySn = request.getFrom();
            Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(gatewaySn);
            if (deviceOpt.isEmpty()) {
                deviceOpt = deviceService.getDeviceBySn(gatewaySn);
            }
            if (deviceOpt.isEmpty() || !StringUtils.hasText(deviceOpt.get().getWorkspaceId())) {
                log.debug("Skip cloud_control_auth push because workspace is missing. gatewaySn={}", gatewaySn);
                return;
            }

            CloudControlAuthStateDTO dto = CloudControlAuthStateDTO.builder()
                    .authorized(state.authorized())
                    .controlKeys(state.controlKeys())
                    .build();
            log.info("Cloud control auth state update. gatewaySn={}, authorized={}, controlKeys={}",
                    gatewaySn, dto.isAuthorized(), dto.getControlKeys());
            deviceService.pushOsdDataToWeb(
                    deviceOpt.get().getWorkspaceId(),
                    BizCodeEnum.CLOUD_CONTROL_AUTH_UPDATE,
                    gatewaySn,
                    dto);
        });
    }
}
