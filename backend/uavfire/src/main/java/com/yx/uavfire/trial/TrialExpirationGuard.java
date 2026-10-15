package com.yx.uavfire.trial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Refuses to create an expired backend and, when a cutoff is configured, closes
 * the Spring context at that instant.
 */
@Component
public final class TrialExpirationGuard {

    private static final Logger log = LoggerFactory.getLogger(TrialExpirationGuard.class);

    private final ConfigurableApplicationContext applicationContext;
    private final TrialExpirationPolicy policy;
    private final ScheduledExecutorService scheduler;

    public TrialExpirationGuard(ConfigurableApplicationContext applicationContext, TrialLicense trialLicense) {
        this.applicationContext = applicationContext;
        this.policy = trialLicense.policy();
        policy.assertCanStart();
        if (!policy.schedulesShutdown()) {
            this.scheduler = null;
            log.info("Trial cutoff disabled for this process; mode={} display={}",
                    policy.modeName(), policy.display());
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "uavfire-trial-expiration");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.schedule(this::expireApplication, policy.remaining().toMillis(), TimeUnit.MILLISECONDS);
        log.warn("Trial cutoff enabled; backend expires at {}", policy.display());
    }

    private void expireApplication() {
        log.error("{}; shutting down backend", policy.expiredMessage());
        applicationContext.close();
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
