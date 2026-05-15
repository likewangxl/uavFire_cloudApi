package com.yx.uavfire.fc100.deliverysync.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * spec §5.7 — HTTP 模式启动时强制校验 AK/SK，缺失 fail-fast。
 * Mock 模式下本 bean 不注册，AK/SK 可空。
 */
@Component
@ConditionalOnProperty(prefix = "fc100.delivery-sync", name = "mode", havingValue = "http")
@Slf4j
public class DeliverySyncSecretsValidator {

    private final DeliverySyncProperties props;

    public DeliverySyncSecretsValidator(DeliverySyncProperties p) {
        this.props = p;
    }

    @PostConstruct
    public void validate() {
        if (props.getAk() == null || props.getAk().isBlank()
            || props.getSk() == null || props.getSk().isBlank()) {
            throw new IllegalStateException(
                "fc100.delivery-sync.mode=http but AK/SK not configured. "
                  + "Set FC100_DELIVERY_SYNC_AK/SK env vars.");
        }
        log.info("Delivery Sync HTTP mode initialized; AK fingerprint={}",
            fingerprint(props.getAk()));
    }

    /** AK 指纹：前 6 字 + ★★★ + 后 4 字，B-5 治理：日志/数据库都用此 */
    public static String fingerprint(String ak) {
        if (ak == null || ak.length() < 12) return "***";
        return ak.substring(0, 6) + "***" + ak.substring(ak.length() - 4);
    }
}
