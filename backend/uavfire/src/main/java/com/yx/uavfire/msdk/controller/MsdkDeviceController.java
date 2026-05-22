package com.yx.uavfire.msdk.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.msdk.model.MsdkCommandAckParam;
import com.yx.uavfire.msdk.model.MsdkCommandDTO;
import com.yx.uavfire.msdk.model.MsdkCommandParam;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/msdk/devices")
public class MsdkDeviceController {

    private final MsdkDeviceStateService stateService;

    public MsdkDeviceController(MsdkDeviceStateService stateService) {
        this.stateService = stateService;
    }

    @PostMapping("/state")
    public HttpResultResponse<Void> upsertState(@RequestBody MsdkDeviceStateDTO state) {
        stateService.upsert(state);
        return HttpResultResponse.success();
    }

    @GetMapping
    public HttpResultResponse<List<MsdkDeviceStateDTO>> listOnline() {
        return HttpResultResponse.success(stateService.listOnline());
    }

    @GetMapping("/{aircraftSn}")
    public HttpResultResponse<MsdkDeviceStateDTO> get(@PathVariable String aircraftSn) {
        return stateService.get(aircraftSn)
                .map(HttpResultResponse::success)
                .orElseGet(() -> HttpResultResponse.error("device not found"));
    }

    @PostMapping("/{aircraftSn}/commands")
    public HttpResultResponse<MsdkCommandDTO> enqueueCommand(@PathVariable String aircraftSn,
                                                            @RequestBody MsdkCommandParam param) {
        return HttpResultResponse.success(stateService.enqueueCommand(aircraftSn, param));
    }

    @PostMapping("/{aircraftSn}/commands/poll")
    public HttpResultResponse<MsdkCommandDTO> pollCommand(@PathVariable String aircraftSn) {
        return HttpResultResponse.success(stateService.pollCommand(aircraftSn).orElse(null));
    }

    @PostMapping("/{aircraftSn}/commands/ack")
    public HttpResultResponse<MsdkCommandDTO> acknowledgeCommand(@PathVariable String aircraftSn,
                                                                @RequestBody MsdkCommandAckParam param) {
        return stateService.acknowledgeCommand(param.getCommandId(), param.getStatus(), param.getMessage())
                .map(HttpResultResponse::success)
                .orElseGet(() -> HttpResultResponse.error("command not found"));
    }
}
