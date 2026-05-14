package com.yx.uavfire.control.service.impl;

import com.yx.uavfire.control.model.param.RcAircraftDrcControlParam;
import com.yx.uavfire.control.model.param.RcAircraftTakeoffSkeletonParam;
import com.yx.uavfire.control.service.IRcAircraftControlService;
import com.yx.uavfire.manage.service.IDeviceRedisService;
import com.dji.sdk.cloudapi.control.DroneControlRequest;
import com.dji.sdk.cloudapi.control.HeartBeatRequest;
import com.dji.sdk.common.HttpResultResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class RcAircraftControlServiceImpl implements IRcAircraftControlService {

    @Autowired
    private SDKControlService sdkControlService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Override
    public HttpResultResponse heartBeat(String gatewaySn, Long seq) {
        if (!deviceRedisService.checkDeviceOnline(gatewaySn)) {
            return HttpResultResponse.error("The gateway is offline.");
        }

        sdkControlService.publishHeartBeat(gatewaySn,
                new HeartBeatRequest()
                        .setSeq(seq)
                        .setTimestamp(System.currentTimeMillis()));
        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse droneControl(String gatewaySn, RcAircraftDrcControlParam param) {
        if (!deviceRedisService.checkDeviceOnline(gatewaySn)) {
            return HttpResultResponse.error("The gateway is offline.");
        }

        sdkControlService.publishDroneControl(gatewaySn, new DroneControlRequest()
                .setSeq(param.getSeq())
                .setX(param.getX())
                .setY(param.getY())
                .setH(param.getH())
                .setW(param.getW())
                .setFreq(param.getFreq())
                .setDelayTime(param.getDelayTime()));
        return HttpResultResponse.success();
    }

    @Override
    public HttpResultResponse experimentalTakeoff(String gatewaySn, RcAircraftTakeoffSkeletonParam param) {
        return runVerticalSkeleton(gatewaySn, param, param.getThrottle(), "takeoff");
    }

    @Override
    public HttpResultResponse experimentalLanding(String gatewaySn, RcAircraftTakeoffSkeletonParam param) {
        return runVerticalSkeleton(gatewaySn, param, -Math.abs(param.getThrottle()), "landing");
    }

    @Override
    public HttpResultResponse emergencyStop(String gatewaySn) {
        if (!deviceRedisService.checkDeviceOnline(gatewaySn)) {
            return HttpResultResponse.error("The gateway is offline.");
        }

        sdkControlService.publishDroneEmergencyStop(gatewaySn);
        return HttpResultResponse.success(Map.of(
                "gateway_sn", gatewaySn,
                "note", "Experimental RC emergency stop command published."
        ));
    }

    private HttpResultResponse runVerticalSkeleton(String gatewaySn,
                                                   RcAircraftTakeoffSkeletonParam param,
                                                   float throttle,
                                                   String action) {
        if (!deviceRedisService.checkDeviceOnline(gatewaySn)) {
            return HttpResultResponse.error("The gateway is offline.");
        }

        long seq = param.getStartSeq();
        try {
            for (int i = 0; i < param.getPulseCount(); i++) {
                if (Boolean.TRUE.equals(param.getSendHeartbeat())) {
                    sdkControlService.publishHeartBeat(gatewaySn,
                            new HeartBeatRequest()
                                    .setSeq(seq++)
                                    .setTimestamp(System.currentTimeMillis()));
                }

                sdkControlService.publishDroneControl(gatewaySn, new DroneControlRequest()
                        .setSeq(seq++)
                        .setH(throttle)
                        .setFreq(param.getFreq())
                        .setDelayTime(param.getDelayTime()));

                Thread.sleep(param.getIntervalMs());
            }

            sdkControlService.publishDroneControl(gatewaySn, new DroneControlRequest()
                    .setSeq(seq)
                    .setH(0F)
                    .setFreq(param.getFreq())
                    .setDelayTime(param.getDelayTime()));

            return HttpResultResponse.success(Map.of(
                    "gateway_sn", gatewaySn,
                    "action", action,
                    "start_seq", param.getStartSeq(),
                    "last_seq", seq,
                    "pulse_count", param.getPulseCount(),
                    "note", "Experimental RC vertical skeleton only. Validate DRC session and site safety before use."
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RC aircraft {} skeleton interrupted. gatewaySn={}", action, gatewaySn, e);
            return HttpResultResponse.error("The experimental RC vertical skeleton was interrupted.");
        }
    }
}
