package com.yx.uavfire.fc100.deliverysync.service;

import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncProperties;
import com.yx.uavfire.fc100.deliverysync.config.DeliverySyncSecretsValidator;
import com.yx.uavfire.fc100.deliverysync.dao.DeliverySyncLogMapper;
import com.yx.uavfire.fc100.deliverysync.model.entity.DeliverySyncLogEntity;
import org.springframework.stereotype.Service;

/**
 * B-5 治理：所有 Delivery Sync 调用强制过此服务落日志（含 AK 指纹脱敏）。
 */
@Service
public class DeliverySyncLogService {

    private final DeliverySyncLogMapper mapper;
    private final DeliverySyncProperties props;
    private final Clock clock;

    public DeliverySyncLogService(DeliverySyncLogMapper m, DeliverySyncProperties p, Clock c) {
        this.mapper = m;
        this.props = p;
        this.clock = c;
    }

    public void recordSuccess(String apiName, Long missionId, String method,
                               String url, String requestBody,
                               Integer responseCode, String responseBody,
                               String idempotencyKey, Integer latencyMs) {
        DeliverySyncLogEntity e = new DeliverySyncLogEntity();
        e.setApiName(apiName);
        e.setMissionId(missionId);
        e.setRequestMethod(method);
        e.setRequestUrl(url);
        e.setRequestBody(requestBody);
        e.setResponseCode(responseCode);
        e.setResponseBody(responseBody);
        e.setSuccess(1);
        e.setAkFingerprint(fingerprint());
        e.setIdempotencyKey(idempotencyKey);
        e.setLatencyMs(latencyMs);
        e.setCreateTime(clock.now());
        mapper.insert(e);
    }

    public void recordFailure(String apiName, Long missionId, String method,
                               String url, String requestBody, String errorMessage,
                               String idempotencyKey, Integer latencyMs) {
        DeliverySyncLogEntity e = new DeliverySyncLogEntity();
        e.setApiName(apiName);
        e.setMissionId(missionId);
        e.setRequestMethod(method);
        e.setRequestUrl(url);
        e.setRequestBody(requestBody);
        e.setSuccess(0);
        e.setErrorMessage(errorMessage);
        e.setAkFingerprint(fingerprint());
        e.setIdempotencyKey(idempotencyKey);
        e.setLatencyMs(latencyMs);
        e.setCreateTime(clock.now());
        mapper.insert(e);
    }

    private String fingerprint() {
        return props.getAk() != null ? DeliverySyncSecretsValidator.fingerprint(props.getAk()) : null;
    }
}
