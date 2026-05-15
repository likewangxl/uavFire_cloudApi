package com.yx.uavfire.fc100.mission.statemachine;

import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent.*;
import static com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus.*;

/**
 * spec §4.4 from×to 矩阵的代码化。
 *
 * <p>状态机 transit() 通过此表查询合法转移；非法转移返 STATUS_TRANSITION_FORBIDDEN。
 * <p>设计约束：
 * <ul>
 *   <li>ARCHIVED 是绝对终态，无任何转出</li>
 *   <li>FAILED/REJECTED/CANCELLED 只能 → ARCHIVED</li>
 *   <li>IN_PROGRESS 之后不允许 CANCEL（飞机在空中），只能 TAKEOVER 或 FORCE_FAIL</li>
 *   <li>FORCE_FAIL 是 admin 紧急逃生口，几乎任意非终态可用</li>
 * </ul>
 */
public final class TransitionTable {

    private static final Map<FireMissionStatus, Map<FireMissionEvent, FireMissionStatus>> TABLE =
        new EnumMap<>(FireMissionStatus.class);

    static {
        put(CREATED, CANCEL, CANCELLED);
        put(CREATED, FORCE_FAIL, FAILED);

        put(WAITING_REVIEW, APPROVE, APPROVED);
        put(WAITING_REVIEW, REJECT, REJECTED);
        put(WAITING_REVIEW, CANCEL, CANCELLED);
        put(WAITING_REVIEW, FORCE_FAIL, FAILED);

        put(APPROVED, CANCEL, CANCELLED);
        put(APPROVED, GEN_WP, ROUTE_GENERATED);
        put(APPROVED, FORCE_FAIL, FAILED);

        put(ROUTE_GENERATED, CANCEL, CANCELLED);
        put(ROUTE_GENERATED, GEN_WP, ROUTE_GENERATED);     // 重新生成允许
        put(ROUTE_GENERATED, EXP_KMZ, ROUTE_EXPORTED);
        put(ROUTE_GENERATED, FORCE_FAIL, FAILED);

        put(ROUTE_EXPORTED, CANCEL, CANCELLED);
        put(ROUTE_EXPORTED, GEN_WP, ROUTE_GENERATED);       // 重做航点回退
        put(ROUTE_EXPORTED, EXP_KMZ, ROUTE_EXPORTED);       // 重新导出
        put(ROUTE_EXPORTED, CREATE_DELIVERY_TASK, SENT_TO_DELIVERY);
        put(ROUTE_EXPORTED, FORCE_FAIL, FAILED);

        put(SENT_TO_DELIVERY, CANCEL, CANCELLED);
        put(SENT_TO_DELIVERY, START_DELIVERY, IN_PROGRESS);
        put(SENT_TO_DELIVERY, FORCE_FAIL, FAILED);

        put(ACCEPTED_BY_PILOT, START_DELIVERY, IN_PROGRESS);
        put(ACCEPTED_BY_PILOT, FORCE_FAIL, FAILED);

        put(IN_PROGRESS, MARK_RELEASE_PENDING, PAYLOAD_RELEASE_PENDING);
        put(IN_PROGRESS, TAKEOVER, MANUAL_TAKEOVER);
        put(IN_PROGRESS, FORCE_FAIL, FAILED);

        put(PAYLOAD_RELEASE_PENDING, CONFIRM_RELEASE, PAYLOAD_RELEASED);
        put(PAYLOAD_RELEASE_PENDING, MARK_RELEASE_FAILED, PAYLOAD_RELEASE_FAILED);
        put(PAYLOAD_RELEASE_PENDING, TAKEOVER, MANUAL_TAKEOVER);
        put(PAYLOAD_RELEASE_PENDING, FORCE_FAIL, FAILED);

        put(PAYLOAD_RELEASED, MARK_RETURNING, RETURNING);
        put(PAYLOAD_RELEASED, TAKEOVER, MANUAL_TAKEOVER);
        put(PAYLOAD_RELEASED, FORCE_FAIL, FAILED);

        put(PAYLOAD_RELEASE_FAILED, RETRY_RELEASE, PAYLOAD_RELEASE_PENDING);
        put(PAYLOAD_RELEASE_FAILED, TAKEOVER, MANUAL_TAKEOVER);
        put(PAYLOAD_RELEASE_FAILED, FORCE_FAIL, FAILED);

        put(RETURNING, MARK_RETURN_COMPLETED, REVIEWING);
        put(RETURNING, MARK_RETURN_FAILED, RETURN_FAILED);
        put(RETURNING, TAKEOVER, MANUAL_TAKEOVER);
        put(RETURNING, FORCE_FAIL, FAILED);

        put(RETURN_FAILED, TAKEOVER, MANUAL_TAKEOVER);
        put(RETURN_FAILED, FORCE_FAIL, FAILED);

        put(MANUAL_TAKEOVER, RESOLVE_TAKEOVER_OK, REVIEWING);
        put(MANUAL_TAKEOVER, RESOLVE_TAKEOVER_FAILED, FAILED);
        put(MANUAL_TAKEOVER, FORCE_FAIL, FAILED);

        put(REVIEWING, SUBMIT_REVIEW, COMPLETED);
        put(REVIEWING, FORCE_FAIL, FAILED);

        put(COMPLETED, ARCHIVE, ARCHIVED);
        put(FAILED,    ARCHIVE, ARCHIVED);
        put(REJECTED,  ARCHIVE, ARCHIVED);
        put(CANCELLED, ARCHIVE, ARCHIVED);
        // ARCHIVED: 终态，无转出（map 中不出现）
    }

    private static void put(FireMissionStatus from, FireMissionEvent ev, FireMissionStatus to) {
        TABLE.computeIfAbsent(from, k -> new EnumMap<>(FireMissionEvent.class)).put(ev, to);
    }

    private TransitionTable() {}

    public static Optional<FireMissionStatus> nextStatus(FireMissionStatus from, FireMissionEvent event) {
        Map<FireMissionEvent, FireMissionStatus> m = TABLE.get(from);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(event));
    }

    public static Set<FireMissionEvent> allowedEvents(FireMissionStatus from) {
        Map<FireMissionEvent, FireMissionStatus> m = TABLE.get(from);
        return m == null ? Set.of() : Collections.unmodifiableSet(m.keySet());
    }
}
