package com.dji.sample.control.controller;

import com.dji.sample.control.model.param.RcAircraftDrcControlParam;
import com.dji.sample.control.model.param.RcAircraftTakeoffSkeletonParam;
import com.dji.sample.control.service.IRcAircraftControlService;
import com.dji.sdk.common.HttpResultResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("${url.control.prefix}${url.control.version}/aircrafts")
public class RcAircraftController {

    @Autowired
    private IRcAircraftControlService rcAircraftControlService;

    @PostMapping("/{gateway_sn}/drc/heartbeat")
    public HttpResultResponse heartBeat(@PathVariable("gateway_sn") String gatewaySn,
                                        @RequestParam("seq") Long seq) {
        return rcAircraftControlService.heartBeat(gatewaySn, seq);
    }

    @PostMapping("/{gateway_sn}/drc/control")
    public HttpResultResponse droneControl(@PathVariable("gateway_sn") String gatewaySn,
                                           @Valid @RequestBody RcAircraftDrcControlParam param) {
        return rcAircraftControlService.droneControl(gatewaySn, param);
    }

    @PostMapping("/{gateway_sn}/jobs/takeoff-skeleton")
    public HttpResultResponse takeoffSkeleton(@PathVariable("gateway_sn") String gatewaySn,
                                              @Valid @RequestBody RcAircraftTakeoffSkeletonParam param) {
        return rcAircraftControlService.experimentalTakeoff(gatewaySn, param);
    }

    @PostMapping("/{gateway_sn}/jobs/landing-skeleton")
    public HttpResultResponse landingSkeleton(@PathVariable("gateway_sn") String gatewaySn,
                                              @Valid @RequestBody RcAircraftTakeoffSkeletonParam param) {
        return rcAircraftControlService.experimentalLanding(gatewaySn, param);
    }

    @PostMapping("/{gateway_sn}/drc/emergency-stop")
    public HttpResultResponse emergencyStop(@PathVariable("gateway_sn") String gatewaySn) {
        return rcAircraftControlService.emergencyStop(gatewaySn);
    }
}
