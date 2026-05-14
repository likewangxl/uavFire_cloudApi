package com.yx.uavfire.control.service.impl;

import com.yx.uavfire.component.mqtt.config.MqttPropertyConfiguration;
import com.yx.uavfire.component.mqtt.model.EventsReceiver;
import com.yx.uavfire.component.mqtt.model.MapKeyConst;
import com.yx.uavfire.component.redis.RedisConst;
import com.yx.uavfire.component.redis.RedisOpsUtils;
import com.yx.uavfire.component.websocket.service.IWebSocketMessageService;
import com.yx.uavfire.control.model.dto.JwtAclDTO;
import com.yx.uavfire.control.model.enums.DroneAuthorityEnum;
import com.yx.uavfire.control.model.enums.MqttAclAccessEnum;
import com.yx.uavfire.control.model.param.DrcConnectParam;
import com.yx.uavfire.control.model.param.DrcModeParam;
import com.yx.uavfire.control.service.IControlService;
import com.yx.uavfire.control.service.IDrcService;
import com.yx.uavfire.manage.model.dto.DeviceDTO;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.yx.uavfire.manage.service.IDeviceService;
import com.yx.uavfire.wayline.model.enums.WaylineJobStatusEnum;
import com.yx.uavfire.wayline.model.enums.WaylineTaskStatusEnum;
import com.yx.uavfire.wayline.model.param.UpdateJobParam;
import com.yx.uavfire.wayline.service.IFlightTaskService;
import com.yx.uavfire.wayline.service.IWaylineJobService;
import com.yx.uavfire.wayline.service.IWaylineRedisService;
import com.dji.sdk.cloudapi.control.CloudControlAuthRequest;
import com.dji.sdk.cloudapi.control.CloudControlReleaseRequest;
import com.dji.sdk.cloudapi.control.DrcModeEnterRequest;
import com.dji.sdk.cloudapi.control.DrcModeMqttBroker;
import com.dji.sdk.cloudapi.control.api.AbstractControlService;
import com.dji.sdk.cloudapi.device.DockModeCodeEnum;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.wayline.FlighttaskProgress;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.mqtt.TopicConst;
import com.dji.sdk.mqtt.services.ServicesReplyData;
import com.dji.sdk.mqtt.services.TopicServicesResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author sean
 * @version 1.3
 * @date 2023/1/11
 */
@Service
@Slf4j
public class DrcServiceImpl implements IDrcService {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IWaylineJobService waylineJobService;

    @Autowired
    private IFlightTaskService flighttaskService;

    @Autowired
    private IDeviceService deviceService;
    
    @Autowired
    private ObjectMapper mapper;
    
    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IControlService controlService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IWaylineRedisService waylineRedisService;

    @Autowired
    private AbstractControlService abstractControlService;

    @Override
    public void setDrcModeInRedis(String dockSn, String clientId) {
        RedisOpsUtils.setWithExpire(RedisConst.DRC_PREFIX + dockSn, clientId, RedisConst.DRC_MODE_ALIVE_SECOND);
    }

    @Override
    public String getDrcModeInRedis(String dockSn) {
        return (String) RedisOpsUtils.get(RedisConst.DRC_PREFIX + dockSn);
    }

    @Override
    public Boolean delDrcModeInRedis(String dockSn) {
        return RedisOpsUtils.del(RedisConst.DRC_PREFIX + dockSn);
    }

    @Override
    public DrcModeMqttBroker userDrcAuth(String workspaceId, String userId, String username, DrcConnectParam param) {
        log.info("DRC broker auth start. workspaceId={}, userId={}, username={}, incomingClientId={}, expireSec={}",
                workspaceId, userId, username, param.getClientId(), param.getExpireSec());

        // refresh token
        String clientId = param.getClientId();
        // first time
        if (!StringUtils.hasText(clientId) || !RedisOpsUtils.checkExist(RedisConst.MQTT_ACL_PREFIX + clientId)) {
            clientId = userId + "-" + System.currentTimeMillis();
            RedisOpsUtils.hashSet(RedisConst.MQTT_ACL_PREFIX + clientId, "", MqttAclAccessEnum.ALL.getValue());
        }

        String key = RedisConst.MQTT_ACL_PREFIX + clientId;

        try {
            RedisOpsUtils.expireKey(key, RedisConst.DRC_MODE_ALIVE_SECOND);
            DrcModeMqttBroker broker = MqttPropertyConfiguration.getMqttBrokerWithDrc(
                    clientId, username, param.getExpireSec(), Collections.emptyMap());
            log.info("DRC broker auth success. workspaceId={}, clientId={}, broker={}",
                    workspaceId, clientId, broker);
            return broker;
        } catch (RuntimeException e) {
            RedisOpsUtils.del(key);
            log.error("DRC broker auth failed. workspaceId={}, clientId={}", workspaceId, clientId, e);
            throw e;
        }
    }

    private String getGatewaySn(DrcModeParam param) {
        return param.getTargetSn();
    }

    private boolean isPilotGatewayScenario(DrcModeParam param) {
        return param.isPilotGatewayScenario();
    }

    private void checkDrcModeCondition(String workspaceId, String dockSn) {
        Optional<EventsReceiver<FlighttaskProgress>> runningOpt = waylineRedisService.getRunningWaylineJob(dockSn);
        if (runningOpt.isPresent() && WaylineJobStatusEnum.IN_PROGRESS == waylineJobService.getWaylineState(dockSn)) {
            flighttaskService.updateJobStatus(workspaceId, runningOpt.get().getBid(),
                    UpdateJobParam.builder().status(WaylineTaskStatusEnum.PAUSE).build());
        }

        DockModeCodeEnum dockMode = deviceService.getDockMode(dockSn);
        Optional<DeviceDTO> dockOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (dockOpt.isPresent() && (DockModeCodeEnum.IDLE == dockMode || DockModeCodeEnum.WORKING == dockMode)) {
            Optional<OsdDockDrone> deviceOsd = deviceRedisService.getDeviceOsd(dockOpt.get().getChildDeviceSn(), OsdDockDrone.class);
            if (deviceOsd.isEmpty() || deviceOsd.get().getElevation() <= 0) {
                throw new RuntimeException("The drone is not in the sky and cannot enter command flight mode.");
            }
        } else {
            throw new RuntimeException("The current state of the dock does not support entering command flight mode.");
        }

        HttpResultResponse result = controlService.seizeAuthority(dockSn, DroneAuthorityEnum.FLIGHT, null);
        if (HttpResultResponse.CODE_SUCCESS != result.getCode()) {
            throw new IllegalArgumentException(result.getMessage());
        }

    }

    private void checkPilotDrcModeCondition(String gatewaySn) {
        if (!deviceRedisService.checkDeviceOnline(gatewaySn)) {
            throw new RuntimeException("The gateway is offline and cannot enter remote control mode.");
        }
    }

    @Override
    public JwtAclDTO deviceDrcEnter(String workspaceId, String userId, String username, DrcModeParam param) {
        String gatewaySn = getGatewaySn(param);
        boolean pilotGatewayScenario = isPilotGatewayScenario(param);
        String topic = TopicConst.THING_MODEL_PRE + TopicConst.PRODUCT + gatewaySn + TopicConst.DRC;
        String pubTopic = topic + TopicConst.DOWN;
        String subTopic = topic + TopicConst.UP;
        log.info("DRC enter start. workspaceId={}, gatewaySn={}, dockSn={}, clientId={}, pilotGatewayScenario={}, pubTopic={}, subTopic={}",
                workspaceId, gatewaySn, param.getDockSn(), param.getClientId(), pilotGatewayScenario, pubTopic, subTopic);

        // If the dock is in drc mode, refresh the permissions directly.
        if (!pilotGatewayScenario
                && deviceService.checkDockDrcMode(gatewaySn)
                && param.getClientId().equals(this.getDrcModeInRedis(gatewaySn))) {
            log.info("DRC enter fast-path refresh. workspaceId={}, gatewaySn={}, clientId={}",
                    workspaceId, gatewaySn, param.getClientId());
            refreshAcl(gatewaySn, param.getClientId(), pubTopic, subTopic);
            return JwtAclDTO.builder().sub(List.of(subTopic)).pub(List.of(pubTopic)).build();
        }

        if (pilotGatewayScenario) {
            checkPilotDrcModeCondition(gatewaySn);
            requestPilotCloudControlAuth(gatewaySn, userId, username);
        } else {
            checkDrcModeCondition(workspaceId, gatewaySn);
        }

        TopicServicesResponse<ServicesReplyData> reply = abstractControlService.drcModeEnter(
                SDKManager.getDeviceSDK(gatewaySn),
                new DrcModeEnterRequest()
                        .setMqttBroker(MqttPropertyConfiguration.getMqttBrokerWithDrc(gatewaySn + "-" + System.currentTimeMillis(), gatewaySn,
                                RedisConst.DRC_MODE_ALIVE_SECOND.longValue(),
                                Map.of(MapKeyConst.ACL, objectMapper.convertValue(JwtAclDTO.builder()
                                        .pub(List.of(subTopic))
                                        .sub(List.of(pubTopic))
                                        .build(), new TypeReference<Map<String, ?>>() {}))))
                        .setHsiFrequency(1).setOsdFrequency(10));
        log.info("DRC enter reply. workspaceId={}, gatewaySn={}, reply={}", workspaceId, gatewaySn, reply);

        if (!reply.getData().getResult().isSuccess()) {
            throw new RuntimeException("SN: " + gatewaySn + "; Error:" + reply.getData().getResult() +
                    "; Failed to enter command flight control mode, please try again later!");
        }

        refreshAcl(gatewaySn, param.getClientId(), pubTopic, subTopic);
        log.info("DRC enter success. workspaceId={}, gatewaySn={}, clientId={}", workspaceId, gatewaySn, param.getClientId());
        return JwtAclDTO.builder().sub(List.of(subTopic)).pub(List.of(pubTopic)).build();
    }

    private void requestPilotCloudControlAuth(String gatewaySn, String userId, String username) {
        try {
            log.info("DRC pilot scenario: publishing cloud_control_auth_request before drc_mode_enter. gatewaySn={}, userId={}, username={}",
                    gatewaySn, userId, username);
            TopicServicesResponse<ServicesReplyData> response = abstractControlService.cloudControlAuthRequest(
                    SDKManager.getDeviceSDK(gatewaySn),
                    new CloudControlAuthRequest()
                            .setUserId(userId)
                            .setUserCallsign(username)
                            .setControlKeys(List.of("flight")));
            ServicesReplyData serviceReply = response.getData();
            log.info("DRC pilot scenario: cloud_control_auth_request reply. gatewaySn={}, result={}",
                    gatewaySn, serviceReply.getResult());
            if (!serviceReply.getResult().isSuccess()) {
                throw new RuntimeException("SN: " + gatewaySn + "; Error:" + serviceReply.getResult() +
                        "; Failed to request cloud flight control authorization.");
            }
        } catch (RuntimeException e) {
            log.error("DRC pilot scenario: cloud_control_auth_request failed. gatewaySn={}", gatewaySn, e);
            throw e;
        }
    }

    private void releasePilotCloudControl(String gatewaySn) {
        try {
            log.info("DRC pilot scenario: publishing cloud_control_release. gatewaySn={}", gatewaySn);
            TopicServicesResponse<ServicesReplyData> response = abstractControlService.cloudControlRelease(
                    SDKManager.getDeviceSDK(gatewaySn),
                    new CloudControlReleaseRequest().setControlKeys(List.of("flight")));
            ServicesReplyData serviceReply = response.getData();
            log.info("DRC pilot scenario: cloud_control_release reply. gatewaySn={}, result={}",
                    gatewaySn, serviceReply.getResult());
            if (!serviceReply.getResult().isSuccess()) {
                throw new RuntimeException("SN: " + gatewaySn + "; Error:" + serviceReply.getResult() +
                        "; Failed to release cloud flight control authorization.");
            }
        } catch (RuntimeException e) {
            log.error("DRC pilot scenario: cloud_control_release failed. gatewaySn={}", gatewaySn, e);
            throw e;
        }
    }

    private void refreshAcl(String dockSn, String clientId, String pubTopic, String subTopic) {
        this.setDrcModeInRedis(dockSn, clientId);

        // assign acl，Match by clientId. https://www.emqx.io/docs/zh/v4.4/advanced/acl-redis.html
        // scheme: HSET mqtt_acl:[clientid] [topic] [access]
        String key = RedisConst.MQTT_ACL_PREFIX + clientId;
        RedisOpsUtils.hashSet(key, pubTopic, MqttAclAccessEnum.PUB.getValue());
        RedisOpsUtils.hashSet(key, subTopic, MqttAclAccessEnum.SUB.getValue());
        RedisOpsUtils.expireKey(key, RedisConst.DRC_MODE_ALIVE_SECOND);
    }

    @Override
    public void deviceDrcExit(String workspaceId, DrcModeParam param) {
        String gatewaySn = getGatewaySn(param);
        boolean pilotGatewayScenario = isPilotGatewayScenario(param);
        log.info("DRC exit start. workspaceId={}, gatewaySn={}, dockSn={}, clientId={}, pilotGatewayScenario={}",
                workspaceId, gatewaySn, param.getDockSn(), param.getClientId(), pilotGatewayScenario);
        if (!pilotGatewayScenario && !deviceService.checkDockDrcMode(gatewaySn)) {
            throw new RuntimeException("The dock is not in flight control mode.");
        }
        TopicServicesResponse<ServicesReplyData> reply =
                abstractControlService.drcModeExit(SDKManager.getDeviceSDK(gatewaySn));
        log.info("DRC exit reply. workspaceId={}, gatewaySn={}, reply={}", workspaceId, gatewaySn, reply);
        if (!reply.getData().getResult().isSuccess()) {
            throw new RuntimeException("SN: " + gatewaySn + "; Error:" +
                    reply.getData().getResult() + "; Failed to exit command flight control mode, please try again later!");
        }

        if (pilotGatewayScenario) {
            releasePilotCloudControl(gatewaySn);
        }

        String jobId = waylineRedisService.getPausedWaylineJobId(gatewaySn);
        if (!pilotGatewayScenario && StringUtils.hasText(jobId)) {
            flighttaskService.updateJobStatus(workspaceId, jobId, UpdateJobParam.builder().status(WaylineTaskStatusEnum.RESUME).build());
        }

        this.delDrcModeInRedis(gatewaySn);
        RedisOpsUtils.del(RedisConst.MQTT_ACL_PREFIX + param.getClientId());
        log.info("DRC exit success. workspaceId={}, gatewaySn={}, clientId={}", workspaceId, gatewaySn, param.getClientId());
    }

}
