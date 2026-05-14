package com.yx.uavfire.control.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author sean
 * @version 1.4
 * @date 2023/3/1
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResultNotifyDTO {

    private Integer result;

    private Integer drcState;

    private String message;

    private String sn;

    /**
     * For takeoff_to_point_progress / fly_to_point_progress: the DJI progress
     * status string (e.g. task_ready, wayline_progress, wayline_ok, task_finish).
     * Frontend uses wayline_ok / task_finish to know the aircraft is stable
     * before chaining the next command, avoiding 337029 "previous command busy".
     */
    private String status;

    /**
     * Correlation ID echoed from the aircraft: flight_id for takeoff progress,
     * fly_to_id for fly_to_point progress. Lets the frontend match an event to
     * the specific command it issued.
     */
    private String flightId;
}
