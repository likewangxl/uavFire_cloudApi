package com.yx.uavfire.wayline.agent.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.common.util.JwtUtil;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentTokenRequestDTO;
import com.yx.uavfire.wayline.agent.model.dto.WaylineAgentTokenResponseDTO;
import com.yx.uavfire.wayline.agent.security.WaylineAgentClaim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("${url.wayline-agent.prefix}${url.wayline-agent.version}/auth")
public class WaylineAgentAuthController {

    @Value("${wayline-agent.shared-secret:change-me}")
    private String sharedSecret;

    @Value("${wayline-agent.token-ttl-seconds:86400}")
    private long tokenTtlSeconds;

    @PostMapping("/token")
    public ResponseEntity<HttpResultResponse<WaylineAgentTokenResponseDTO>> issueToken(@RequestBody WaylineAgentTokenRequestDTO body) {
        if (!StringUtils.hasText(body.getDroneSn()) || !StringUtils.hasText(body.getSharedSecret())) {
            return ResponseEntity.badRequest().body(HttpResultResponse.error("droneSn and sharedSecret required"));
        }
        if (!sharedSecret.equals(body.getSharedSecret())) {
            log.warn("wayline-agent: token request rejected for droneSn={}", body.getDroneSn());
            return ResponseEntity.status(401).body(HttpResultResponse.error("invalid shared secret"));
        }

        String token = JwtUtil.createToken(Map.of(
                "droneSn", body.getDroneSn(),
                "role", WaylineAgentClaim.ROLE));

        WaylineAgentTokenResponseDTO data = new WaylineAgentTokenResponseDTO()
                .setToken(token)
                .setExpiresIn(tokenTtlSeconds);
        return ResponseEntity.ok(HttpResultResponse.success(data));
    }
}
