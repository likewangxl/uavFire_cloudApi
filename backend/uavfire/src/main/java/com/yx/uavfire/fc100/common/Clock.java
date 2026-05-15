package com.yx.uavfire.fc100.common;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 时间抽象：测试可注入 FixedClock 实现。
 * 默认 Spring Bean 是 SystemClock。
 */
public interface Clock {

    long now();

    @Component
    @Primary
    class SystemClock implements Clock {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    }
}
