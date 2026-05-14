package com.yx.uavfire.control.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.control.model.dto.JwtAclDTO;
import com.yx.uavfire.control.model.param.DrcConnectParam;
import com.yx.uavfire.control.model.param.DrcModeParam;
import com.yx.uavfire.control.service.IDrcService;
import com.dji.sdk.cloudapi.control.DrcModeMqttBroker;
import com.dji.sdk.common.HttpResultResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import static com.yx.uavfire.component.AuthInterceptor.TOKEN_CLAIM;

/**
 * @author sean
 * @version 1.3
 * @date 2023/1/11
 */
@RestController
@Slf4j
@RequestMapping("${url.control.prefix}${url.control.version}")
public class DrcController {

    @Autowired
    private IDrcService drcService;

    @PostMapping("/workspaces/{workspace_id}/drc/connect")
    public HttpResultResponse drcConnect(@PathVariable("workspace_id") String workspaceId, HttpServletRequest request, @Valid @RequestBody DrcConnectParam param) {
        CustomClaim claims = (CustomClaim) request.getAttribute(TOKEN_CLAIM);
        log.info("DRC connect request. workspaceId={}, userId={}, username={}, clientId={}, expireSec={}",
                workspaceId, claims.getId(), claims.getUsername(), param.getClientId(), param.getExpireSec());

        DrcModeMqttBroker brokerDTO = drcService.userDrcAuth(workspaceId, claims.getId(), claims.getUsername(), param);
        return HttpResultResponse.success(brokerDTO);
    }

    @PostMapping("/workspaces/{workspace_id}/drc/enter")
    public HttpResultResponse drcEnter(@PathVariable("workspace_id") String workspaceId, HttpServletRequest request, @Valid @RequestBody DrcModeParam param) {
        CustomClaim claims = (CustomClaim) request.getAttribute(TOKEN_CLAIM);
        log.info("DRC enter request. workspaceId={}, userId={}, username={}, clientId={}, dockSn={}, gatewaySn={}, targetSn={}, pilotGatewayScenario={}",
                workspaceId, claims.getId(), claims.getUsername(), param.getClientId(), param.getDockSn(), param.getGatewaySn(),
                param.getTargetSn(), param.isPilotGatewayScenario());
        JwtAclDTO acl = drcService.deviceDrcEnter(workspaceId, claims.getId(), claims.getUsername(), param);

        return HttpResultResponse.success(acl);
    }

    @PostMapping("/workspaces/{workspace_id}/drc/exit")
    public HttpResultResponse drcExit(@PathVariable("workspace_id") String workspaceId, @Valid @RequestBody DrcModeParam param) {
        log.info("DRC exit request. workspaceId={}, clientId={}, dockSn={}, gatewaySn={}, targetSn={}, pilotGatewayScenario={}",
                workspaceId, param.getClientId(), param.getDockSn(), param.getGatewaySn(),
                param.getTargetSn(), param.isPilotGatewayScenario());
        drcService.deviceDrcExit(workspaceId, param);

        return HttpResultResponse.success();
    }


}
