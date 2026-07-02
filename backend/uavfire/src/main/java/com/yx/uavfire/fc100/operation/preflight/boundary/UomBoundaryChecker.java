package com.yx.uavfire.fc100.operation.preflight.boundary;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;

public interface UomBoundaryChecker {
    BoundaryCheckResult check(PreflightContext context);
}
