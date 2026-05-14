package com.yx.uavfire.control.service;

import com.yx.uavfire.control.model.param.RcAircraftDrcControlParam;
import com.yx.uavfire.control.model.param.RcAircraftTakeoffSkeletonParam;
import com.dji.sdk.common.HttpResultResponse;

public interface IRcAircraftControlService {

    HttpResultResponse heartBeat(String gatewaySn, Long seq);

    HttpResultResponse droneControl(String gatewaySn, RcAircraftDrcControlParam param);

    HttpResultResponse experimentalTakeoff(String gatewaySn, RcAircraftTakeoffSkeletonParam param);

    HttpResultResponse experimentalLanding(String gatewaySn, RcAircraftTakeoffSkeletonParam param);

    HttpResultResponse emergencyStop(String gatewaySn);
}
