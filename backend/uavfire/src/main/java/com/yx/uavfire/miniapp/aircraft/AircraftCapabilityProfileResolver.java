package com.yx.uavfire.miniapp.aircraft;

import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.model.PayloadCapabilityDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves identity and runtime capabilities independently. A known model name is
 * never sufficient evidence that a command can be executed safely.
 */
@Component
public class AircraftCapabilityProfileResolver {

    private final MiniAppProperties properties;

    public AircraftCapabilityProfileResolver(MiniAppProperties properties) {
        this.properties = properties;
    }

    public AircraftCapabilityProfile resolve(MsdkDeviceStateDTO state) {
        String modelKey = resolveModelKey(state);
        AircraftFamily family = resolveFamily(modelKey);
        String combinationKey = resolveCombinationKey(state, modelKey);
        boolean acceptanceVerified = properties.getFlightControl().getVerifiedCombinationKeys().stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeCombinationKey)
                .anyMatch(normalizeCombinationKey(combinationKey)::equals);
        boolean online = state != null && Boolean.TRUE.equals(state.getOnline())
                && !"DISCONNECTED".equalsIgnoreCase(state.getConnectionState());
        Map<String, Boolean> capabilities = state == null ? null : state.getCapabilities();

        boolean visible = capability(capabilities, "visibleStream")
                || payloadCapability(state, PayloadCapabilityDTO::getVisibleSupported);
        boolean thermal = capability(capabilities, "thermalFocus")
                || payloadCapability(state, PayloadCapabilityDTO::getThermalSupported);
        boolean laser = (state != null && Boolean.TRUE.equals(state.getLaserSupported()))
                || payloadCapability(state, PayloadCapabilityDTO::getLaserSupported);
        boolean wayline = capability(capabilities, "waylineExecute")
                || capability(capabilities, "waylineControl")
                || payloadCapability(state, PayloadCapabilityDTO::getWaylineSupported);

        List<String> reasons = new ArrayList<>();
        if (!properties.getFlightControl().isEnabled()) {
            reasons.add("MINIAPP_FLIGHT_CONTROL_DISABLED");
        }
        if (!online) {
            reasons.add("AIRCRAFT_OFFLINE");
        }
        if (family == AircraftFamily.UNKNOWN) {
            reasons.add("AIRCRAFT_MODEL_UNKNOWN");
        }
        if (!acceptanceVerified) {
            reasons.add("AIRCRAFT_COMBINATION_NOT_VERIFIED");
        }
        if (!wayline) {
            reasons.add("WAYLINE_CAPABILITY_NOT_REPORTED");
        }
        if (state != null && state.getBlockingReasons() != null) {
            state.getBlockingReasons().stream()
                    .filter(StringUtils::hasText)
                    .map(reason -> "AGENT:" + reason)
                    .forEach(reasons::add);
        }

        return new AircraftCapabilityProfile()
                .setModelKey(modelKey)
                .setCombinationKey(combinationKey)
                .setFamily(family)
                .setKnownModel(family != AircraftFamily.UNKNOWN)
                .setAcceptanceVerified(acceptanceVerified)
                .setTelemetryReadable(online)
                .setVisibleStreamReported(visible)
                .setThermalReported(thermal)
                .setLaserReported(laser)
                .setWaylineControlReported(wayline)
                .setFlightControlEligible(reasons.isEmpty())
                .setBlockingReasons(reasons);
    }

    public AircraftFamily resolveFamily(String rawModelKey) {
        String normalized = normalize(rawModelKey);
        switch (normalized) {
            case "M3E":
            case "M3T":
            case "M3M":
            case "MAVIC3E":
            case "MAVIC3T":
            case "MAVIC3M":
            case "MAVIC3ENTERPRISE":
            case "MAVIC3THERMAL":
            case "MAVIC3MULTISPECTRAL":
                return AircraftFamily.MAVIC_3_ENTERPRISE;
            case "M30":
            case "M30T":
            case "MATRICE30":
            case "MATRICE30T":
                return AircraftFamily.MATRICE_30;
            case "M300":
            case "M300RTK":
            case "MATRICE300":
            case "MATRICE300RTK":
                return AircraftFamily.MATRICE_300;
            case "M350":
            case "M350RTK":
            case "MATRICE350":
            case "MATRICE350RTK":
                return AircraftFamily.MATRICE_350;
            case "M3D":
            case "M3TD":
            case "MATRICE3D":
            case "MATRICE3TD":
                return AircraftFamily.MATRICE_3_DOCK;
            case "M4E":
            case "M4T":
            case "MATRICE4E":
            case "MATRICE4T":
                return AircraftFamily.MATRICE_4_ENTERPRISE;
            case "M4D":
            case "M4TD":
            case "MATRICE4D":
            case "MATRICE4TD":
                return AircraftFamily.MATRICE_4_DOCK;
            case "M400":
            case "MATRICE400":
                return AircraftFamily.MATRICE_400;
            default:
                // Plain "M3" is intentionally ambiguous and therefore remains unknown.
                return AircraftFamily.UNKNOWN;
        }
    }

    private String resolveModelKey(MsdkDeviceStateDTO state) {
        if (state == null) {
            return "UNKNOWN";
        }
        if (StringUtils.hasText(state.getAircraftModelKey())) {
            return canonicalModelKey(state.getAircraftModelKey());
        }
        if (StringUtils.hasText(state.getModel())) {
            return canonicalModelKey(state.getModel());
        }
        return "UNKNOWN";
    }

    private String resolveCombinationKey(MsdkDeviceStateDTO state, String modelKey) {
        String controller = state != null && StringUtils.hasText(state.getControllerModelKey())
                ? normalize(state.getControllerModelKey()) : "UNKNOWN_CONTROLLER";
        Integer selectedPosition = state == null ? null : state.getSelectedPayloadPositionIndex();
        PayloadCapabilityDTO selectedPayload = state == null || state.getPayloads() == null ? null
                : state.getPayloads().stream()
                        .filter(payload -> selectedPosition != null
                                && selectedPosition.equals(payload.getPayloadPositionIndex()))
                        .findFirst()
                        .orElse(state.getPayloads().size() == 1 ? state.getPayloads().get(0) : null);
        String payload = selectedPayload != null && StringUtils.hasText(selectedPayload.getPayloadModelKey())
                ? normalize(selectedPayload.getPayloadModelKey()) : "NO_PAYLOAD_REPORTED";
        String position = selectedPosition == null ? "NA" : selectedPosition.toString();
        return modelKey + "|" + controller + "|" + payload + "|" + position;
    }

    private String canonicalModelKey(String rawModelKey) {
        String normalized = normalize(rawModelKey);
        switch (normalized) {
            case "MAVIC3E": return "M3E";
            case "MAVIC3T": return "M3T";
            case "MAVIC3M": return "M3M";
            case "M300RTK":
            case "MATRICE300":
            case "MATRICE300RTK": return "M300";
            case "M350RTK":
            case "MATRICE350":
            case "MATRICE350RTK": return "M350";
            case "MATRICE30": return "M30";
            case "MATRICE30T": return "M30T";
            case "MATRICE3D": return "M3D";
            case "MATRICE3TD": return "M3TD";
            case "MATRICE4E": return "M4E";
            case "MATRICE4T": return "M4T";
            case "MATRICE4D": return "M4D";
            case "MATRICE4TD": return "M4TD";
            case "MATRICE400": return "M400";
            default: return StringUtils.hasText(normalized) ? normalized : "UNKNOWN";
        }
    }

    private String normalizeCombinationKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private boolean capability(Map<String, Boolean> capabilities, String key) {
        return capabilities != null && Boolean.TRUE.equals(capabilities.get(key));
    }

    private boolean payloadCapability(
            MsdkDeviceStateDTO state,
            java.util.function.Function<PayloadCapabilityDTO, Boolean> selector) {
        if (state == null || state.getPayloads() == null) {
            return false;
        }
        return state.getPayloads().stream().anyMatch(payload -> Boolean.TRUE.equals(selector.apply(payload)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
