package com.yx.uavfire.miniapp.security;

import com.yx.uavfire.miniapp.web.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/** Per-process backpressure; deploy a shared ingress quota when scaling horizontally. */
@Component
public class MiniAppLoginLimiter {
    private final Map<String, long[]> buckets = new HashMap<>();
    public synchronized void check(String remoteAddress) {
        long minute = System.currentTimeMillis() / 60_000;
        buckets.entrySet().removeIf(entry -> entry.getValue()[0] != minute);
        long[] bucket = buckets.get(remoteAddress);
        if (bucket == null && buckets.size() < 5000) {
            bucket = new long[]{minute, 0}; buckets.put(remoteAddress, bucket);
        }
        if (bucket == null || ++bucket[1] > 30) throw new MiniAppException(HttpStatus.TOO_MANY_REQUESTS,
                MiniAppErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
    }
}
