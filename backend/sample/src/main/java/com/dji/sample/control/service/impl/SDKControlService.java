package com.dji.sample.control.service.impl;

import com.dji.sample.component.websocket.model.BizCodeEnum;
import com.dji.sample.component.websocket.service.IWebSocketMessageService;
import com.dji.sample.control.model.dto.ResultNotifyDTO;
import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.model.enums.UserTypeEnum;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.cloudapi.control.*;
import com.dji.sdk.cloudapi.control.api.AbstractControlService;
import com.dji.sdk.mqtt.MqttReply;
import com.dji.sdk.mqtt.events.TopicEventsRequest;
import com.dji.sdk.mqtt.events.TopicEventsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * @author sean
 * @version 1.7
 * @date 2023/7/4
 */
@Service
@Slf4j
public class SDKControlService extends AbstractControlService {

    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private ObjectMapper mapper;

    public void publishDroneControl(String gatewaySn, DroneControlRequest request) {
        droneControlDown(SDKManager.getDeviceSDK(gatewaySn), request);
    }

    public void publishHeartBeat(String gatewaySn, HeartBeatRequest request) {
        heartBeatDown(SDKManager.getDeviceSDK(gatewaySn), request);
    }

    public void publishDroneEmergencyStop(String gatewaySn) {
        droneEmergencyStopDown(SDKManager.getDeviceSDK(gatewaySn));
    }

    @Override
    public TopicEventsResponse<MqttReply> flyToPointProgress(TopicEventsRequest<FlyToPointProgress> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            log.error("The dock is offline.");
            return null;
        }

        FlyToPointProgress eventsReceiver = request.getData();
        webSocketMessageService.sendBatch(deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                BizCodeEnum.FLY_TO_POINT_PROGRESS.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getResult().toString())
                        .result(eventsReceiver.getResult().getCode())
                        .status(eventsReceiver.getStatus() == null ? null : eventsReceiver.getStatus().getStatus())
                        .flightId(eventsReceiver.getFlyToId())
                        .build());
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicEventsResponse<MqttReply> takeoffToPointProgress(TopicEventsRequest<TakeoffToPointProgress> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            log.error("The dock is offline.");
            return null;
        }

        TakeoffToPointProgress eventsReceiver = request.getData();
        try {
            log.info("takeoffToPointProgress received. gatewaySn={}, flightId={}, result={}, status={}, remainingDistance={}, remainingTime={}, wayPointIndex={}, plannedPathPoints={}",
                    dockSn,
                    eventsReceiver.getFlightId(),
                    eventsReceiver.getResult(),
                    eventsReceiver.getStatus() == null ? null : eventsReceiver.getStatus().getStatus(),
                    eventsReceiver.getRemainingDistance(),
                    eventsReceiver.getRemainingTime(),
                    eventsReceiver.getWayPointIndex(),
                    mapper.writeValueAsString(eventsReceiver.getPlannedPathPoints()));
        } catch (Exception e) {
            log.warn("takeoffToPointProgress logging failed. gatewaySn={}, flightId={}, error={}",
                    dockSn, eventsReceiver.getFlightId(), e.getMessage());
        }
        webSocketMessageService.sendBatch(deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                BizCodeEnum.TAKE_OFF_TO_POINT_PROGRESS.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getResult().toString())
                        .result(eventsReceiver.getResult().getCode())
                        .status(eventsReceiver.getStatus() == null ? null : eventsReceiver.getStatus().getStatus())
                        .flightId(eventsReceiver.getFlightId())
                        .build());

        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicEventsResponse<MqttReply> drcStatusNotify(TopicEventsRequest<DrcStatusNotify> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            log.warn("DRC status notify ignored because gateway is offline. gatewaySn={}, data={}",
                    dockSn, request.getData());
            return null;
        }

        DrcStatusNotify eventsReceiver = request.getData();
        log.info("DRC status notify received. gatewaySn={}, result={}, state={}",
                dockSn, eventsReceiver.getResult(), eventsReceiver.getDrcState());
        Integer drcState = eventsReceiver.getDrcState() == null
                ? null
                : eventsReceiver.getDrcState().getState();
        webSocketMessageService.sendBatch(
                deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(), BizCodeEnum.DRC_STATUS_NOTIFY.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getResult().getMessage())
                        .result(eventsReceiver.getResult().getCode())
                        .drcState(drcState)
                        .build());
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicEventsResponse<MqttReply> joystickInvalidNotify(TopicEventsRequest<JoystickInvalidNotify> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            log.warn("Joystick invalid notify ignored because gateway is offline. gatewaySn={}, data={}",
                    dockSn, request.getData());
            return null;
        }

        JoystickInvalidNotify eventsReceiver = request.getData();
        log.warn("Joystick invalid notify received. gatewaySn={}, reason={}, message={}",
                dockSn, eventsReceiver.getReason(), eventsReceiver.getReason().getMessage());
        webSocketMessageService.sendBatch(
                deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(), BizCodeEnum.JOYSTICK_INVALID_NOTIFY.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getReason().getMessage())
                        .result(eventsReceiver.getReason().getVal()).build());
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }
}
