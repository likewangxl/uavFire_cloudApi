package com.yx.uavfire.fc100.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 任务编号生成：MISSION-yyyyMMdd-HHmmss-NNNN (NNNN 是 0000-9999 随机数)。
 *
 * <p>spec §3.2 mission_no UNIQUE。生成端冲突极小但仍可能；调用方应在唯一键冲突时
 * 重试 next() 直到成功，或考虑改用 snowflake。MVP 接受 ~4.6% 同秒万次碰撞概率。
 */
@Component
public class MissionNoGenerator {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Clock clock;

    public MissionNoGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        String ts = FMT.format(Instant.ofEpochMilli(clock.now()));
        int rand = ThreadLocalRandom.current().nextInt(10000);
        return String.format("MISSION-%s-%04d", ts, rand);
    }
}
