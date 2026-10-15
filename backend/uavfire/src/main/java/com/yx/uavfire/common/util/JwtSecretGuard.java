package com.yx.uavfire.common.util;

import com.yx.uavfire.trial.DeploymentEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Refuses the sample HMAC secret in every environment unless a local test opts in,
 * and refuses a missing secret in production. Dev/test without JWT_SECRET gets a
 * process-local key so the service can boot.
 */
@Component
public class JwtSecretGuard {

    public static final String SAMPLE_SECRET = "CloudApiSample";

    private static final Logger log = LoggerFactory.getLogger(JwtSecretGuard.class);

    public JwtSecretGuard(Environment environment,
                           @Value("${jwt.secret:}") String secret,
                           @Value("${jwt.allow-insecure-secret:false}") boolean allowInsecureSecret) {
        boolean production = DeploymentEnvironment.isProduction(environment);
        boolean blank = secret == null || secret.isBlank();
        boolean sample = SAMPLE_SECRET.equals(secret);
        if (production && (blank || sample)) {
            throw new IllegalStateException(
                    "生产环境禁止使用空 JWT 密钥或示例密钥 CloudApiSample，请设置 JWT_SECRET");
        }
        if (sample && !allowInsecureSecret) {
            throw new IllegalStateException(
                    "拒绝示例 JWT 密钥 CloudApiSample。请设置 JWT_SECRET；仅本地排障可设 jwt.allow-insecure-secret=true");
        }
        if (sample) {
            JwtUtil.applySecret(secret);
            log.warn("insecure sample JWT secret is enabled for this process");
            return;
        }
        if (blank) {
            String ephemeral = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            JwtUtil.applySecret(ephemeral);
            log.warn("JWT_SECRET 未配置，已为本次进程生成临时密钥。重启后已签发令牌失效。生产环境必须设置 JWT_SECRET");
            return;
        }
        JwtUtil.applySecret(secret);
    }
}
