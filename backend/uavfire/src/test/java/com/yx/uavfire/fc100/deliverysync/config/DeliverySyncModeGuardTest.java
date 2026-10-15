package com.yx.uavfire.fc100.deliverysync.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliverySyncModeGuardTest {

    @Test
    void productionRejectsMockUnlessExplicitlyAllowed() {
        assertThrows(IllegalStateException.class,
                () -> new DeliverySyncModeGuard(new DeliverySyncProperties(), production()));

        DeliverySyncProperties allowed = new DeliverySyncProperties();
        allowed.setAllowMock(true);
        new DeliverySyncModeGuard(allowed, production());

        DeliverySyncProperties http = new DeliverySyncProperties();
        http.setMode("http");
        new DeliverySyncModeGuard(http, production());
        new DeliverySyncModeGuard(new DeliverySyncProperties(), dev());
    }

    private static Environment production() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        return environment;
    }

    private static Environment dev() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(environment.getProperty("uavfire.env")).thenReturn(null);
        when(environment.getProperty("UAVFIRE_ENV")).thenReturn(null);
        return environment;
    }
}
