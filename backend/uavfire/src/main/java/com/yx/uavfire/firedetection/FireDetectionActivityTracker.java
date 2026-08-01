package com.yx.uavfire.firedetection;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录哪些飞机当前处于火情监测中。
 * 仅监测中的飞机允许后端自动下发 focus-thermal / 测温指令，
 * 记录兼容监测状态；新的生产识别状态以 Agent 心跳为准。
 */
@Component
public class FireDetectionActivityTracker {

    private final Set<String> activeDrones = ConcurrentHashMap.newKeySet();

    public void markActive(String droneSn) {
        if (StringUtils.hasText(droneSn)) {
            activeDrones.add(droneSn);
        }
    }

    public void markInactive(String droneSn) {
        if (StringUtils.hasText(droneSn)) {
            activeDrones.remove(droneSn);
        }
    }

    public boolean isActive(String droneSn) {
        return StringUtils.hasText(droneSn) && activeDrones.contains(droneSn);
    }
}
