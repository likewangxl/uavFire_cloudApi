package com.yx.uavfire.manage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "uavfire.auth")
public class AuthSecurityProperties {

    private boolean demoLoginEnabled = false;

    /** Comma-separated login names that receive the ADMIN role. */
    private String adminUsernames = "";

    public boolean isDemoLoginEnabled() {
        return demoLoginEnabled;
    }

    public void setDemoLoginEnabled(boolean demoLoginEnabled) {
        this.demoLoginEnabled = demoLoginEnabled;
    }

    public String getAdminUsernames() {
        return adminUsernames;
    }

    public void setAdminUsernames(String adminUsernames) {
        this.adminUsernames = adminUsernames;
    }

    public Set<String> adminUsernameSet() {
        if (adminUsernames == null || adminUsernames.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : adminUsernames.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }
}
