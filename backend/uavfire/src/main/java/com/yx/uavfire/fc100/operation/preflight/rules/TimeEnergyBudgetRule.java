package com.yx.uavfire.fc100.operation.preflight.rules;

import com.yx.uavfire.fc100.operation.preflight.PreflightContext;
import com.yx.uavfire.fc100.operation.preflight.PreflightProperties;
import com.yx.uavfire.fc100.operation.preflight.PreflightRule;
import com.yx.uavfire.fc100.operation.preflight.RuleCheckResult;

public class TimeEnergyBudgetRule extends AbstractPreflightRule implements PreflightRule {
    public String id() { return "R13"; }
    public String description() { return "任务时间和能量预算充足"; }
    public RuleCheckResult check(PreflightContext context) {
        if (context.mission() == null || context.incident() == null || context.deviceProperties() == null
            || context.mission().getTakeoffLat() == null || context.mission().getTakeoffLng() == null
            || context.incident().getCenterLat() == null || context.incident().getCenterLng() == null
            || context.deviceProperties().getBatteryPercent() == null) {
            return block("time budget inputs missing");
        }
        PreflightProperties.TimeBudget p = context.properties().getTimeBudget();
        double distanceM = distanceMeters(context.mission().getTakeoffLat(), context.mission().getTakeoffLng(),
            context.incident().getCenterLat(), context.incident().getCenterLng());
        double flightMinutes = (distanceM * 2.0) / Math.max(p.getCruiseSpeedMps(), 1.0) / 60.0;
        double required = flightMinutes + p.getHoverMinutes() * p.getHoverRatio()
            + p.getMaxFlightMinutes() * p.getSafetyReserveRatio();
        double available = p.getMaxFlightMinutes() * context.deviceProperties().getBatteryPercent() / 100.0;
        return required <= available ? pass()
            : block("required " + round(required) + "min exceeds available " + round(available) + "min");
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
