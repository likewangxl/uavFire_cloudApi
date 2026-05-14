package com.dji.sample.manage.service;

import com.dji.sample.manage.service.impl.CloudControlAuthStateResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudControlAuthStateResolverTest {

    private final CloudControlAuthStateResolver resolver = new CloudControlAuthStateResolver();

    @Test
    void resolvesFlightAuthorizationFromCloudControlAuthArray() {
        CloudControlAuthStateResolver.ResolvedCloudControlAuthState state =
                resolver.resolve(Map.of("cloud_control_auth", List.of("flight"))).orElseThrow();

        assertTrue(state.authorized());
        assertEquals(List.of("flight"), state.controlKeys());
    }

    @Test
    void resolvesAuthorizationReleaseFromEmptyCloudControlAuthArray() {
        CloudControlAuthStateResolver.ResolvedCloudControlAuthState state =
                resolver.resolve(Map.of("cloud_control_auth", List.of())).orElseThrow();

        assertFalse(state.authorized());
        assertEquals(List.of(), state.controlKeys());
    }

    @Test
    void resolvesBooleanIsCloudControlAuth() {
        CloudControlAuthStateResolver.ResolvedCloudControlAuthState state =
                resolver.resolve(Map.of("is_cloud_control_auth", true)).orElseThrow();

        assertTrue(state.authorized());
        assertEquals(List.of(), state.controlKeys());
    }

    @Test
    void ignoresUnrelatedStatePayloads() {
        assertTrue(resolver.resolve(Map.of("current_commander_flight_mode", 1)).isEmpty());
    }
}
