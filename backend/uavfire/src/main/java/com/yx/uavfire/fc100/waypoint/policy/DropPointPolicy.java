package com.yx.uavfire.fc100.waypoint.policy;

import com.yx.uavfire.fc100.waypoint.config.Fc100WaypointProperties;
import org.springframework.stereotype.Component;

/**
 * 投放点偏移策略。
 *
 * <p>spec §5.1 物理推算：
 * <pre>
 *   fallTime = sqrt(2 * dropAltAgl / g)
 *   dropOffset = clamp(windSpeed * fallTime * driftCoefficient, 0, maxDropOffset)
 * </pre>
 *
 * <p>P4 位置 = F 沿 windDirectionDeg（上风方向）偏移 dropOffset 米。
 * 物理直觉：水袋离开吊舱后被风吹回火点，P4 必须在上风侧。
 */
@Component
public class DropPointPolicy {

    private static final double G = 9.8;
    private final Fc100WaypointProperties props;

    public DropPointPolicy(Fc100WaypointProperties props) {
        this.props = props;
    }

    public double dropOffset(double dropAltAgl, double windSpeed) {
        double fallTime = Math.sqrt(2 * dropAltAgl / G);
        double raw = windSpeed * fallTime * props.getDriftCoefficient();
        if (raw < 0) raw = 0;
        if (raw > props.getMaxDropOffsetM()) raw = props.getMaxDropOffsetM();
        return raw;
    }
}
