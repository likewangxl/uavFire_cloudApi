package com.yx.uavfire.wayline.agent.service;

import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ring-buffer of wayline events keyed by missionId. Skeleton
 * storage — replace with a DB-backed store before production once we know
 * what queries the frontend / analytics layer actually needs.
 */
@Component
public class WaylineEventStore {

    private static final int MAX_PER_MISSION = 500;

    private final Map<String, List<WaylineEventRecord>> byMission = new ConcurrentHashMap<>();

    public void append(WaylineEventRecord record) {
        if (record.getMissionId() == null) {
            return;
        }
        List<WaylineEventRecord> list = byMission.computeIfAbsent(
                record.getMissionId(),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(record);
            if (list.size() > MAX_PER_MISSION) {
                list.remove(0);
            }
        }
    }

    public List<WaylineEventRecord> getByMission(String missionId) {
        if (missionId == null) {
            return List.of();
        }
        List<WaylineEventRecord> list = byMission.get(missionId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public void clearMission(String missionId) {
        if (missionId == null) {
            return;
        }
        byMission.remove(missionId);
    }
}
