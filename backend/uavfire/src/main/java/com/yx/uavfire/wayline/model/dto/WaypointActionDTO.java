package com.yx.uavfire.wayline.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * Per-航点的单一动作。一个航点可挂多个 action,顺序执行。详见
 * docs/WAYLINE_L1_L2_CONTRACT.md 2.2。
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WaypointActionDTO {

    /** 在该航点 actionGroup 内的局部 id,从 0 起。 */
    private Integer actionId;

    /** reachPoint | betweenAdjacentPoints | multipleTiming。默认 reachPoint。 */
    private String actionTrigger;

    /** multipleTiming 时间间隔(秒);其他 trigger 类型忽略。 */
    private Double actionTriggerParam;

    /** takePhoto | startRecord | stopRecord | gimbalRotate | hover | focus | rotateYaw。 */
    private String actuatorFunc;

    /** actuatorFunc-specific 参数。详见 contract 2.2 表。 */
    private Map<String, Object> params;
}
