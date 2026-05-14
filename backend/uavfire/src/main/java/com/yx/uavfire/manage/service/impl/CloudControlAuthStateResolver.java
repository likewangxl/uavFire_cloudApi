package com.yx.uavfire.manage.service.impl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class CloudControlAuthStateResolver {

    public Optional<ResolvedCloudControlAuthState> resolve(Object data) {
        if (!(data instanceof Map)) {
            return Optional.empty();
        }
        Map<?, ?> map = (Map<?, ?>) data;

        if (map.containsKey("is_cloud_control_auth")) {
            Object raw = map.get("is_cloud_control_auth");
            if (raw instanceof Boolean) {
                return Optional.of(new ResolvedCloudControlAuthState((Boolean) raw, List.of()));
            }
        }

        if (map.containsKey("cloud_control_auth")) {
            List<String> controlKeys = toStringList(map.get("cloud_control_auth"));
            return Optional.of(new ResolvedCloudControlAuthState(controlKeys.contains("flight"), controlKeys));
        }

        return Optional.empty();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof Collection)) {
            return Collections.emptyList();
        }
        Collection<?> collection = (Collection<?>) value;

        List<String> result = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof String) {
                result.add((String) item);
            }
        }
        return result;
    }

    public static class ResolvedCloudControlAuthState {

        private final boolean authorized;

        private final List<String> controlKeys;

        public ResolvedCloudControlAuthState(boolean authorized, List<String> controlKeys) {
            this.authorized = authorized;
            this.controlKeys = controlKeys;
        }

        public boolean authorized() {
            return authorized;
        }

        public List<String> controlKeys() {
            return controlKeys;
        }
    }
}
