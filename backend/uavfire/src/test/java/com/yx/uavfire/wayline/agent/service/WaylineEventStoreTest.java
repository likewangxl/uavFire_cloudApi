package com.yx.uavfire.wayline.agent.service;

import com.yx.uavfire.wayline.agent.model.WaylineEventRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaylineEventStoreTest {

    @Test
    void append_andGetByMission_returnsRecordsInInsertionOrder() {
        WaylineEventStore store = new WaylineEventStore();
        store.append(new WaylineEventRecord("SN-A", "m-1", "wayline_state_change", 1L, "first"));
        store.append(new WaylineEventRecord("SN-A", "m-1", "wayline_progress", 2L, "second"));

        List<WaylineEventRecord> events = store.getByMission("m-1");

        assertEquals(2, events.size());
        assertEquals("first", events.get(0).getData());
        assertEquals("second", events.get(1).getData());
    }

    @Test
    void getByMission_isEmptyWhenMissionUnknown() {
        WaylineEventStore store = new WaylineEventStore();
        assertTrue(store.getByMission("missing").isEmpty());
    }

    @Test
    void append_isolatesPerMission() {
        WaylineEventStore store = new WaylineEventStore();
        store.append(new WaylineEventRecord("SN-A", "m-1", "x", 1L, "a"));
        store.append(new WaylineEventRecord("SN-A", "m-2", "x", 2L, "b"));

        assertEquals(1, store.getByMission("m-1").size());
        assertEquals(1, store.getByMission("m-2").size());
        assertEquals("a", store.getByMission("m-1").get(0).getData());
        assertEquals("b", store.getByMission("m-2").get(0).getData());
    }

    @Test
    void append_dropsMissingMissionIdSilently() {
        WaylineEventStore store = new WaylineEventStore();
        store.append(new WaylineEventRecord("SN-A", null, "x", 1L, "ignored"));
        assertTrue(store.getByMission(null).isEmpty());
    }

    @Test
    void append_evictsOldestPastCapacity() {
        WaylineEventStore store = new WaylineEventStore();
        // MAX_PER_MISSION = 500
        IntStream.range(0, 510).forEach(i ->
                store.append(new WaylineEventRecord("SN-A", "m-1", "x", i, i)));

        List<WaylineEventRecord> events = store.getByMission("m-1");
        assertEquals(500, events.size());
        // oldest 10 entries (0..9) should have been evicted; first entry is now i=10
        assertEquals(10, events.get(0).getData());
        assertEquals(509, events.get(499).getData());
    }

    @Test
    void clearMission_drainsList() {
        WaylineEventStore store = new WaylineEventStore();
        store.append(new WaylineEventRecord("SN-A", "m-1", "x", 1L, "a"));
        store.clearMission("m-1");
        assertTrue(store.getByMission("m-1").isEmpty());
    }
}
