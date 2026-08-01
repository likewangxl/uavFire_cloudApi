package com.yx.uavfire.fc100.event.controller;

import com.yx.uavfire.fc100.event.model.dto.AgentFireReportResponse;
import com.yx.uavfire.fc100.event.model.param.AgentFireReportParam;
import com.yx.uavfire.fc100.event.service.AgentFireIngressUnavailableException;
import com.yx.uavfire.fc100.event.service.AgentFireReportIngress;
import com.yx.uavfire.fc100.event.service.AgentFireReportValidator;
import com.yx.uavfire.wayline.agent.security.WaylineAgentAuthInterceptor;
import com.yx.uavfire.wayline.agent.security.WaylineAgentClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;

/** Staged Agent ingress; Task 11 owns the durable implementation of its port. */
@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/fire-events")
public class AgentFireReportController {
    private final AgentFireReportIngress ingress;
    private final AgentFireReportValidator validator;
    private final ObjectMapper objectMapper;

    public AgentFireReportController(AgentFireReportIngress ingress, AgentFireReportValidator validator) {
        this(ingress, validator, new ObjectMapper());
    }

    @Autowired
    public AgentFireReportController(AgentFireReportIngress ingress, AgentFireReportValidator validator,
                                     ObjectMapper objectMapper) {
        this.ingress = ingress;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/agent-report")
    public ResponseEntity<AgentFireReportResponse> report(@RequestBody byte[] rawPayload,
                                                          HttpServletRequest request) {
        try {
            AgentFireReportParam param = objectMapper.readValue(rawPayload, AgentFireReportParam.class);
            WaylineAgentClaim claim = (WaylineAgentClaim) request.getAttribute(WaylineAgentAuthInterceptor.ATTR_CLAIM);
            if (claim == null || !Objects.equals(param.getDroneSn(), claim.getDroneSn())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            validator.validate(param);
            AgentFireReportIngress.Result result = ingress.accept(param, rawPayload, sha256(rawPayload));
            if (result.getStatus() == AgentFireReportIngress.Result.Status.CONFLICT) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            boolean duplicate = result.getStatus() == AgentFireReportIngress.Result.Status.EXACT_DUPLICATE;
            return ResponseEntity.ok(new AgentFireReportResponse(
                param.getEventId(), param.getSequence(), true, result.isNotificationQueued(), duplicate));
        } catch (IOException | IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        } catch (AgentFireIngressUnavailableException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
