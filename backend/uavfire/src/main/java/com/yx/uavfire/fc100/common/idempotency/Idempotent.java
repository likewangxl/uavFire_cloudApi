package com.yx.uavfire.fc100.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 Controller 方法为幂等。客户端必须在请求头携带 X-Idempotency-Key。
 *
 * <p>spec §4.6 B-2 修复：所有状态推进端点必须用此注解 + 后端乐观锁联动。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** Redis key 前缀，建议使用 API 业务名（如 "mission.approve"） */
    String value();

    /** 成功记录的 TTL（秒）。默认 24h。 */
    long successTtlSeconds() default 86400;

    /** 失败/执行中记录的 TTL（秒）。默认 30s，允许稍后重试。 */
    long failedTtlSeconds() default 30;
}
