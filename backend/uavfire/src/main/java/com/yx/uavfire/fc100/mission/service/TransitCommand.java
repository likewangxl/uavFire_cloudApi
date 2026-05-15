package com.yx.uavfire.fc100.mission.service;

import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionStatus;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.Map;

/**
 * 状态机推进命令。
 *
 * <p>关键字段：
 * <ul>
 *   <li>expectedFrom：调用方期望的当前状态，null=不校验；非 null 时与 DB 实际不匹配会拒</li>
 *   <li>expectedVersion：乐观锁版本号，null=取当前 DB 版本（无并发保护）；非 null 时不匹配会拒</li>
 *   <li>payload：事件副作用参数（如 APPROVE 携带 aircraftSn、windSpeed 等）</li>
 * </ul>
 */
@Value
@Builder
public class TransitCommand {
    String missionNo;
    FireMissionEvent event;
    FireMissionStatus expectedFrom;
    Long expectedVersion;
    String operatorId;
    String operatorRole;
    String clientIp;
    String requestId;
    String idempotencyKey;
    @Builder.Default
    Map<String, Object> payload = Collections.emptyMap();
    String remark;
}
