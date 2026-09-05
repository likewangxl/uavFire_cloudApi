package com.yx.uavfire.video;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.manage.service.IDeviceService;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentClaim;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}")
public class VideoBandwidthController {
    private final VideoBandwidthService service;
    private final IDeviceService devices;
    public VideoBandwidthController(VideoBandwidthService service, IDeviceService devices) {
        this.service = service; this.devices = devices;
    }

    @PostMapping("/dual-stream/agents/{sn}/video-policy")
    public HttpResultResponse<VideoPolicyDecision> policy(@PathVariable String sn,
            @RequestBody VideoPolicyReport body, HttpServletRequest request) {
        Object claim = request.getAttribute(WaylineAgentAuthInterceptor.ATTR_CLAIM);
        if (!(claim instanceof WaylineAgentClaim) || !sn.equals(((WaylineAgentClaim) claim).getDroneSn())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent-device-mismatch");
        }
        if (body.getError() != null && body.getError().length() > 256) body.setError(body.getError().substring(0, 256));
        return HttpResultResponse.success(service.report(sn, body));
    }

    @PostMapping("/video-bandwidth/viewers/{viewerId}")
    public HttpResultResponse<Void> view(@PathVariable String viewerId, @RequestBody ViewerRequest body,
            HttpServletRequest request) {
        CustomClaim user = user(request);
        if (!viewerId.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid-viewer-id");
        }
        String sn = body.getDroneSn();
        if (sn != null && !sn.isBlank() && !visible(user, sn)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "device-workspace-mismatch");
        }
        try {
            service.view(user.getWorkspaceId() + ":" + user.getId(), viewerId, sn);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return HttpResultResponse.success();
    }

    @GetMapping("/video-bandwidth/status")
    public HttpResultResponse<Map<String, Object>> status(HttpServletRequest request) {
        CustomClaim user = user(request);
        Set<String> allowed = service.fleet().stream().filter(sn -> visible(user, sn)).collect(Collectors.toSet());
        return HttpResultResponse.success(service.status(allowed));
    }

    private boolean visible(CustomClaim user, String sn) {
        return service.fleet().contains(sn) && devices.getDeviceBySn(sn)
                .map(d -> user.getWorkspaceId().equals(d.getWorkspaceId())).orElse(false);
    }

    private CustomClaim user(HttpServletRequest request) {
        Object claim = request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        if (!(claim instanceof CustomClaim)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        CustomClaim user = (CustomClaim) claim;
        if (user.getId() == null || user.getId().isBlank() || user.getWorkspaceId() == null
                || user.getWorkspaceId().isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return user;
    }

    @Data
    public static class ViewerRequest {
        @com.fasterxml.jackson.annotation.JsonAlias("drone_sn")
        private String droneSn;
    }
}
