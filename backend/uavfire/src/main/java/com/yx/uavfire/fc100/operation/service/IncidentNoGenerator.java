package com.yx.uavfire.fc100.operation.service;

import com.yx.uavfire.fc100.common.Clock;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class IncidentNoGenerator {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Clock clock;

    public IncidentNoGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        String ts = FMT.format(Instant.ofEpochMilli(clock.now()));
        int rand = ThreadLocalRandom.current().nextInt(10000);
        return String.format("INCIDENT-%s-%04d", ts, rand);
    }
}
