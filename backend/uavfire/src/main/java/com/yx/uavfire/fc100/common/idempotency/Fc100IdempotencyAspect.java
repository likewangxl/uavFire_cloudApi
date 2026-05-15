package com.yx.uavfire.fc100.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;

/**
 * @Idempotent 切面。
 *
 * <p>每个 POST 状态推进端点：
 * <ol>
 *   <li>读 X-Idempotency-Key 头，缺失返 INVALID_PARAM</li>
 *   <li>查 Redis：命中 OK:&lt;json&gt; → 解析返回；命中 IN_FLIGHT/FAILED → 409 IDEMPOTENCY_REPLAY</li>
 *   <li>tryAcquire 失败 → 409</li>
 *   <li>执行业务，成功 markSuccess，异常 markFailed 后重抛</li>
 * </ol>
 */
@Aspect
@Component
public class Fc100IdempotencyAspect {

    private final IdempotencyService svc;
    private final ObjectMapper objectMapper;

    public Fc100IdempotencyAspect(IdempotencyService svc, ObjectMapper om) {
        this.svc = svc;
        this.objectMapper = om;
    }

    @Around("@annotation(com.yx.uavfire.fc100.common.idempotency.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method m = ((MethodSignature) pjp.getSignature()).getMethod();
        Idempotent ann = m.getAnnotation(Idempotent.class);

        HttpServletRequest req = currentRequest();
        String idemHeader = req.getHeader("X-Idempotency-Key");
        if (idemHeader == null || idemHeader.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "X-Idempotency-Key header required for " + ann.value());
        }

        String key = "fc100:idem:" + ann.value() + ":" + idemHeader;

        Optional<String> existing = svc.get(key);
        if (existing.isPresent()) {
            String v = existing.get();
            if (v.startsWith("OK:")) {
                String json = v.substring(3);
                return objectMapper.readValue(json, ApiResult.class);
            }
            throw new Fc100BusinessException(Fc100ErrorCode.IDEMPOTENCY_REPLAY,
                "request in flight or recently failed: " + ann.value());
        }

        boolean acquired = svc.tryAcquire(key, Duration.ofSeconds(ann.failedTtlSeconds()));
        if (!acquired) {
            throw new Fc100BusinessException(Fc100ErrorCode.IDEMPOTENCY_REPLAY,
                "concurrent request: " + ann.value());
        }

        try {
            Object result = pjp.proceed();
            svc.markSuccess(key, objectMapper.writeValueAsString(result),
                Duration.ofSeconds(ann.successTtlSeconds()));
            return result;
        } catch (Throwable t) {
            svc.markFailed(key, Duration.ofSeconds(ann.failedTtlSeconds()));
            throw t;
        }
    }

    private HttpServletRequest currentRequest() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest();
    }
}
