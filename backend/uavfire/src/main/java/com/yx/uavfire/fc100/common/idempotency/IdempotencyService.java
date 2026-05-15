package com.yx.uavfire.fc100.common.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等键 Redis 操作封装。
 *
 * <p>状态机：
 * <ul>
 *   <li>未占用 → tryAcquire 返回 true，写入 "IN_FLIGHT" + failedTtl</li>
 *   <li>成功 → markSuccess 写入 "OK:&lt;json&gt;" + successTtl</li>
 *   <li>失败 → markFailed 写入 "FAILED" + failedTtl，允许稍后重试</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 占位"执行中"；返回 true=新请求可执行，false=已被占用（并发或重放） */
    public boolean tryAcquire(String key, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, "IN_FLIGHT", ttl);
        return Boolean.TRUE.equals(ok);
    }

    /** 标记成功并写入序列化结果，供重放查询返回 */
    public void markSuccess(String key, String resultJson, Duration ttl) {
        redis.opsForValue().set(key, "OK:" + resultJson, ttl);
    }

    /** 标记失败（短 TTL，允许稍后重试） */
    public void markFailed(String key, Duration ttl) {
        redis.opsForValue().set(key, "FAILED", ttl);
    }

    /** 读取当前值。empty=未占用；OK:开头=成功重放；IN_FLIGHT/FAILED=并发或近失败 */
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }
}
