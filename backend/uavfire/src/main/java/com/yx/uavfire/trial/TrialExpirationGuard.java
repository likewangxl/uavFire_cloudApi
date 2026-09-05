package com.yx.uavfire.trial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Refuses to create an expired backend and closes a running Spring context at
 * the exact cutoff. Closing the context also tears down HTTP, WebSocket, MQTT,
 * scheduled jobs and database pools owned by this process.
 */
@Component
public final class TrialExpirationGuard {

    private static final Logger log = LoggerFactory.getLogger(TrialExpirationGuard.class);

    private final ConfigurableApplicationContext applicationContext;
    private final ScheduledExecutorService scheduler;

    public TrialExpirationGuard(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        TrialExpirationPolicy policy = TrialExpirationPolicy.systemClock();
        if (policy.isExpired()) {
            throw new IllegalStateException(TrialExpirationPolicy.EXPIRED_MESSAGE);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "uavfire-trial-expiration");
            thread.setDaemon(true);
            return thread;
        });
        Duration remaining = policy.remaining();
        scheduler.schedule(this::expireApplication, remaining.toMillis(), TimeUnit.MILLISECONDS);
        log.warn("Trial build enabled; backend expires at {}", TrialExpirationPolicy.EXPIRES_AT_DISPLAY);
    }

    private void expireApplication() {
        log.error("{}; shutting down backend", TrialExpirationPolicy.EXPIRED_MESSAGE);
        applicationContext.close();
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }
}
