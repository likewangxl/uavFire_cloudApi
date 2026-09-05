package com.yx.uavfire.miniapp;

import com.yx.uavfire.miniapp.aircraft.AircraftCapabilityProfile;
import com.yx.uavfire.miniapp.aircraft.AircraftCapabilityProfileResolver;
import com.yx.uavfire.miniapp.aircraft.AircraftFamily;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.model.PayloadCapabilityDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AircraftCapabilityProfileResolverTest {

    private MiniAppProperties properties;
    private AircraftCapabilityProfileResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new MiniAppProperties();
        resolver = new AircraftCapabilityProfileResolver(properties);
    }

    @Test
    void recognizesEnterpriseAndDockFamiliesWithoutTreatingPlainM3AsEnterprise() {
        assertEquals(AircraftFamily.MAVIC_3_ENTERPRISE, resolver.resolveFamily("M3T"));
        assertEquals(AircraftFamily.MATRICE_300, resolver.resolveFamily("Matrice 300 RTK"));
        assertEquals(AircraftFamily.MATRICE_350, resolver.resolveFamily("M350_RTK"));
        assertEquals(AircraftFamily.MATRICE_4_DOCK, resolver.resolveFamily("M4TD"));
        assertEquals(AircraftFamily.MATRICE_400, resolver.resolveFamily("Matrice 400"));
        assertEquals(AircraftFamily.UNKNOWN, resolver.resolveFamily("M3"));
    }

    @Test
    void keepsKnownOnlineAircraftReadOnlyWhenFlightFeatureFlagIsOff() {
        AircraftCapabilityProfile profile = resolver.resolve(new MsdkDeviceStateDTO()
                .setAircraftModelKey("M3T")
                .setOnline(true)
                .setConnectionState("CONNECTED")
                .setCapabilities(Map.of("waylineExecute", true, "visibleStream", true)));

        assertTrue(profile.isKnownModel());
        assertTrue(profile.isTelemetryReadable());
        assertTrue(profile.isVisibleStreamReported());
        assertTrue(profile.isWaylineControlReported());
        assertFalse(profile.isFlightControlEligible());
        assertTrue(profile.getBlockingReasons().contains("MINIAPP_FLIGHT_CONTROL_DISABLED"));
    }

    @Test
    void allowsControlOnlyForKnownOnlineAircraftWithExplicitWaylineCapability() {
        properties.getFlightControl().setEnabled(true);
        properties.getFlightControl().setVerifiedCombinationKeys(
                List.of("M300|RCPLUS|H30T|0"));
        AircraftCapabilityProfile profile = resolver.resolve(new MsdkDeviceStateDTO()
                .setModel("M300 RTK")
                .setControllerModelKey("RC Plus")
                .setOnline(true)
                .setConnectionState("CONNECTED")
                .setCapabilities(Map.of("waylineExecute", true))
                .setPayloads(List.of(new PayloadCapabilityDTO()
                        .setPayloadModelKey("H30T")
                        .setPayloadPositionIndex(0)
                        .setThermalSupported(true)
                        .setLaserSupported(true)))
                .setSelectedPayloadPositionIndex(0));

        assertEquals(AircraftFamily.MATRICE_300, profile.getFamily());
        assertEquals("M300", profile.getModelKey());
        assertEquals("M300|RCPLUS|H30T|0", profile.getCombinationKey());
        assertTrue(profile.isAcceptanceVerified());
        assertTrue(profile.isThermalReported());
        assertTrue(profile.isLaserReported());
        assertTrue(profile.isFlightControlEligible());
        assertTrue(profile.getBlockingReasons().isEmpty());
    }

    @Test
    void unknownAircraftMayExposeTelemetryButNeverControl() {
        properties.getFlightControl().setEnabled(true);
        AircraftCapabilityProfile profile = resolver.resolve(new MsdkDeviceStateDTO()
                .setAircraftModelKey("FUTURE-X")
                .setOnline(true)
                .setConnectionState("CONNECTED")
                .setCapabilities(Map.of("waylineExecute", true)));

        assertTrue(profile.isTelemetryReadable());
        assertFalse(profile.isKnownModel());
        assertFalse(profile.isFlightControlEligible());
        assertTrue(profile.getBlockingReasons().contains("AIRCRAFT_MODEL_UNKNOWN"));
        assertTrue(profile.getBlockingReasons().contains("AIRCRAFT_COMBINATION_NOT_VERIFIED"));
    }

    @Test
    void agentBlockingReasonPreventsControlEvenWhenOtherGatesPass() {
        properties.getFlightControl().setEnabled(true);
        AircraftCapabilityProfile profile = resolver.resolve(new MsdkDeviceStateDTO()
                .setAircraftModelKey("M350")
                .setOnline(true)
                .setConnectionState("CONNECTED")
                .setCapabilities(Map.of("waylineControl", true))
                .setBlockingReasons(List.of("payload-not-confirmed")));

        assertFalse(profile.isFlightControlEligible());
        assertTrue(profile.getBlockingReasons().contains("AGENT:payload-not-confirmed"));
    }
}
