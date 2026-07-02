package com.yx.uavfire.fc100.operation.preflight.boundary;

import lombok.Data;

@Data
public class BoundaryCheckResult {
    private boolean referenceLayerPresent;
    private boolean inside;
    private String message;

    public static BoundaryCheckResult inside() {
        BoundaryCheckResult result = new BoundaryCheckResult();
        result.referenceLayerPresent = true;
        result.inside = true;
        return result;
    }

    public static BoundaryCheckResult outside(String message) {
        BoundaryCheckResult result = new BoundaryCheckResult();
        result.referenceLayerPresent = true;
        result.inside = false;
        result.message = message;
        return result;
    }

    public static BoundaryCheckResult noReferenceLayer() {
        BoundaryCheckResult result = new BoundaryCheckResult();
        result.referenceLayerPresent = false;
        result.inside = true;
        result.message = "UOM reference layer missing";
        return result;
    }
}
