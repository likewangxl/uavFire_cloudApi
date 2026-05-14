package com.yx.uavfire.control.model.param;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class RcAircraftTakeoffSkeletonParam {

    @NotNull
    @Min(1)
    @JsonProperty("start_seq")
    @JsonAlias({"startSeq"})
    private Long startSeq;

    @NotNull
    @Min(1)
    @Max(100)
    @JsonProperty("pulse_count")
    @JsonAlias({"pulseCount"})
    private Integer pulseCount;

    @NotNull
    @Min(1)
    @Max(5)
    private Float throttle;

    @Min(2)
    @Max(10)
    private Integer freq = 2;

    @Min(100)
    @Max(1000)
    @JsonProperty("delay_time")
    @JsonAlias({"delayTime"})
    private Integer delayTime = 200;

    @Min(20)
    @Max(1000)
    @JsonProperty("interval_ms")
    @JsonAlias({"intervalMs"})
    private Integer intervalMs = 100;

    @NotNull
    @JsonProperty("send_heartbeat")
    @JsonAlias({"sendHeartbeat"})
    private Boolean sendHeartbeat = true;
}
