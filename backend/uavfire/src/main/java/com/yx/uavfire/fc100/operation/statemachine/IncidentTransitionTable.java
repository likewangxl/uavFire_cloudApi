package com.yx.uavfire.fc100.operation.statemachine;

import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent;
import com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.yx.uavfire.fc100.operation.model.enums.OperationIncidentEvent.*;
import static com.yx.uavfire.fc100.operation.model.enums.OperationIncidentStatus.*;

public final class IncidentTransitionTable {

    private static final Map<OperationIncidentStatus, Map<OperationIncidentEvent, OperationIncidentStatus>> TABLE =
        new EnumMap<>(OperationIncidentStatus.class);

    static {
        put(CANDIDATE, CONFIRM, CONFIRMED);
        put(CANDIDATE, MARK_FALSE_ALARM, FALSE_ALARM);

        put(CONFIRMED, DISPATCH, DISPATCHING);
        put(CONFIRMED, MARK_FALSE_ALARM, FALSE_ALARM);
        put(CONFIRMED, ABORT, ABORTED);

        put(DISPATCHING, RESPOND, RESPONDING);
        put(DISPATCHING, ABORT, ABORTED);

        put(RESPONDING, START_RECHECK, RECHECKING);
        put(RESPONDING, RESOLVE, RESOLVED);
        put(RESPONDING, ABORT, ABORTED);

        put(RECHECKING, CONTINUE_RESPONSE, RESPONDING);
        put(RECHECKING, RESOLVE, RESOLVED);
        put(RECHECKING, ABORT, ABORTED);

        put(RESOLVED, ARCHIVE, ARCHIVED);
        put(FALSE_ALARM, ARCHIVE, ARCHIVED);
        put(ABORTED, ARCHIVE, ARCHIVED);
    }

    private IncidentTransitionTable() {
    }

    public static Optional<OperationIncidentStatus> nextStatus(OperationIncidentStatus from, OperationIncidentEvent event) {
        Map<OperationIncidentEvent, OperationIncidentStatus> events = TABLE.get(from);
        return events == null ? Optional.empty() : Optional.ofNullable(events.get(event));
    }

    public static Set<OperationIncidentEvent> allowedEvents(OperationIncidentStatus from) {
        Map<OperationIncidentEvent, OperationIncidentStatus> events = TABLE.get(from);
        return events == null ? Set.of() : Collections.unmodifiableSet(events.keySet());
    }

    private static void put(OperationIncidentStatus from, OperationIncidentEvent event, OperationIncidentStatus to) {
        TABLE.computeIfAbsent(from, k -> new EnumMap<>(OperationIncidentEvent.class)).put(event, to);
    }
}
