package com.yx.uavfire.fc100.payload.service;

import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.mission.dao.FireMissionLogMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionLogEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import com.yx.uavfire.fc100.mission.model.enums.ReleaseExecutionMode;
import com.yx.uavfire.fc100.mission.model.enums.ReleasePolicy;
import com.yx.uavfire.fc100.payload.config.Fc100ReleaseProperties;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayloadReleasePolicyService {

    public static final String AUTO_RELEASE_OPERATOR = "system-auto-release";

    private final FireMissionLogMapper logMapper;
    private final Clock clock;
    private final Fc100ReleaseProperties properties;

    public PayloadReleasePolicyService(FireMissionLogMapper logMapper, Clock clock) {
        this(logMapper, clock, new Fc100ReleaseProperties());
    }

    @Autowired
    public PayloadReleasePolicyService(FireMissionLogMapper logMapper, Clock clock,
                                       Fc100ReleaseProperties properties) {
        this.logMapper = logMapper;
        this.clock = clock;
        this.properties = properties == null ? new Fc100ReleaseProperties() : properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
        noRollbackFor = Fc100BusinessException.class)
    public ReleasePolicyDecision validateReleaseRequest(FireMissionEntity mission,
                                                        ReleaseRequest request) {
        ReleasePolicy policy = ReleasePolicy.fromDb(mission.getReleasePolicy());
        ReleaseExecutionMode mode = ReleaseExecutionMode.fromDb(mission.getReleaseExecutionMode());
        String operatorId = safe(request == null ? null : request.getOperatorId());

        if (!FireMissionStatus.PAYLOAD_RELEASE_PENDING.name().equals(mission.getStatus())) {
            recordAudit(mission, request, "PAYLOAD_RELEASE_STATUS_DENIED", operatorId,
                "release requires PAYLOAD_RELEASE_PENDING");
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                "release requires PAYLOAD_RELEASE_PENDING");
        }
        if (mode == ReleaseExecutionMode.DELIVERY_SYNC_REMOTE) {
            // DJI has not provided written confirmation that the Delivery Sync
            // device command can safely release the FC100 hook. Keep this
            // branch explicit so it can be opened only after vendor confirmation.
            deny(mission, request, operatorId, Fc100ErrorCode.RELEASE_CAPABILITY_UNCONFIRMED,
                "RELEASE_CAPABILITY_UNCONFIRMED");
        }
        if (operatorId == null) {
            deny(mission, request, null, Fc100ErrorCode.RELEASE_CONFIRMATION_REQUIRED,
                "RELEASE_CONFIRMATION_REQUIRED");
        }
        validateConfirmationToken(mission, request, operatorId);
        if (policy == ReleasePolicy.MANUAL_CONFIRM && !Boolean.TRUE.equals(request.getConfirmedRelease())) {
            deny(mission, request, operatorId, Fc100ErrorCode.RELEASE_CONFIRMATION_REQUIRED,
                "RELEASE_CONFIRMATION_REQUIRED");
        }
        if (policy == ReleasePolicy.CONTROLLED_TEST_AUTO && !properties.isControlledTestAutoEnabled()) {
            deny(mission, request, operatorId, Fc100ErrorCode.CONTROLLED_TEST_AUTO_DISABLED,
                "CONTROLLED_TEST_AUTO_DISABLED");
        }
        if (policy == ReleasePolicy.DRY_RUN) {
            recordAudit(mission, request, "PAYLOAD_RELEASE_DRY_RUN", operatorId,
                "DRY_RUN");
            return ReleasePolicyDecision.dryRun(policy, mode);
        }

        recordAudit(mission, request, "PAYLOAD_RELEASE_POLICY_ACCEPTED", operatorId,
            "policy=" + policy.name() + ",mode=" + mode.name());
        return ReleasePolicyDecision.execute(policy, mode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowAutoRelease(FireMissionEntity mission) {
        ReleasePolicy policy = ReleasePolicy.fromDb(mission.getReleasePolicy());
        ReleaseExecutionMode mode = ReleaseExecutionMode.fromDb(mission.getReleaseExecutionMode());
        ReleaseRequest request = ReleaseRequest.builder()
            .operatorId(AUTO_RELEASE_OPERATOR)
            .source("AUTO_RELEASE")
            .build();

        if (policy != ReleasePolicy.CONTROLLED_TEST_AUTO) {
            recordAudit(mission, request, "PAYLOAD_AUTO_RELEASE_POLICY_BLOCKED",
                AUTO_RELEASE_OPERATOR, "release_policy=" + policy.name());
            return false;
        }
        if (!properties.isControlledTestAutoEnabled()) {
            recordAudit(mission, request, "PAYLOAD_AUTO_RELEASE_POLICY_BLOCKED",
                AUTO_RELEASE_OPERATOR, "CONTROLLED_TEST_AUTO_DISABLED");
            return false;
        }
        if (mode == ReleaseExecutionMode.DELIVERY_SYNC_REMOTE) {
            recordAudit(mission, request, "PAYLOAD_AUTO_RELEASE_POLICY_BLOCKED",
                AUTO_RELEASE_OPERATOR, "RELEASE_CAPABILITY_UNCONFIRMED");
            return false;
        }

        recordAudit(mission, request, "PAYLOAD_AUTO_RELEASE_POLICY_ACCEPTED",
            AUTO_RELEASE_OPERATOR, "policy=" + policy.name() + ",mode=" + mode.name());
        return true;
    }

    private void deny(FireMissionEntity mission, ReleaseRequest request, String operatorId,
                      Fc100ErrorCode errorCode, String remark) {
        recordAudit(mission, request, "PAYLOAD_RELEASE_POLICY_DENIED", operatorId, remark);
        throw new Fc100BusinessException(errorCode, errorCode.defaultMessage());
    }

    private void validateConfirmationToken(FireMissionEntity mission, ReleaseRequest request, String operatorId) {
        String submitted = safe(request == null ? null : request.getConfirmationToken());
        String expected = safe(mission.getReleaseConfirmationToken());
        if (submitted == null) {
            denyToken(mission, request, operatorId, "RELEASE_CONFIRMATION_TOKEN_REQUIRED");
        }
        if (expected == null || !expected.equals(submitted)) {
            denyToken(mission, request, operatorId, "RELEASE_CONFIRMATION_TOKEN_MISMATCH");
        }
        Long expiresAt = mission.getReleaseTokenExpiresAt();
        if (expiresAt != null && expiresAt < clock.now()) {
            denyToken(mission, request, operatorId, "RELEASE_CONFIRMATION_TOKEN_EXPIRED");
        }
        if (mission.getReleaseTokenUsedAt() != null) {
            denyToken(mission, request, operatorId, "RELEASE_CONFIRMATION_TOKEN_REPLAYED");
        }
    }

    private void denyToken(FireMissionEntity mission, ReleaseRequest request, String operatorId, String remark) {
        recordAudit(mission, request, "PAYLOAD_RELEASE_TOKEN_DENIED", operatorId, remark);
        throw new Fc100BusinessException(Fc100ErrorCode.RELEASE_CONFIRMATION_REQUIRED, remark);
    }

    private void recordAudit(FireMissionEntity mission, ReleaseRequest request, String action,
                             String operatorId, String remark) {
        FireMissionLogEntity log = new FireMissionLogEntity();
        log.setMissionId(mission.getId());
        log.setAction(action);
        log.setFromStatus(mission.getStatus());
        log.setToStatus(mission.getStatus());
        log.setOperatorId(operatorId);
        log.setClientIp(request == null ? null : request.getClientIp());
        log.setRequestId(request == null ? null : request.getRequestId());
        log.setIdempotencyKey(request == null ? null : request.getIdempotencyKey());
        log.setRemark(remark);
        log.setCreateTime(clock.now());
        logMapper.insert(log);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Data
    @Builder
    public static class ReleaseRequest {
        private String operatorId;
        private Boolean confirmedRelease;
        private String confirmationToken;
        private String source;
        private String clientIp;
        private String requestId;
        private String idempotencyKey;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ReleasePolicyDecision {
        private final ReleasePolicy policy;
        private final ReleaseExecutionMode mode;
        private final boolean dryRun;

        private static ReleasePolicyDecision dryRun(ReleasePolicy policy, ReleaseExecutionMode mode) {
            return new ReleasePolicyDecision(policy, mode, true);
        }

        private static ReleasePolicyDecision execute(ReleasePolicy policy, ReleaseExecutionMode mode) {
            return new ReleasePolicyDecision(policy, mode, false);
        }
    }
}
