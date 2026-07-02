package com.yx.uavfire.fc100.operation.preflight.boundary;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import org.springframework.stereotype.Service;

@Service
public class NoReferenceUomBoundaryChecker implements UomBoundaryChecker {
    @Override
    public BoundaryCheckResult check(PreflightContext context) {
        return BoundaryCheckResult.noReferenceLayer();
    }
}
