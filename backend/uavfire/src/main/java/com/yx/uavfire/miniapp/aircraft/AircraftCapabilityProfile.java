package com.yx.uavfire.miniapp.aircraft;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class AircraftCapabilityProfile {

    private String modelKey;
    private String combinationKey;
    private AircraftFamily family;
    private boolean knownModel;
    private boolean acceptanceVerified;
    private boolean telemetryReadable;
    private boolean visibleStreamReported;
    private boolean thermalReported;
    private boolean laserReported;
    private boolean waylineControlReported;
    private boolean flightControlEligible;
    private List<String> blockingReasons = new ArrayList<>();
}
