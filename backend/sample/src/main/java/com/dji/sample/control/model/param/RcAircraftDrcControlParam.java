package com.dji.sample.control.model.param;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class RcAircraftDrcControlParam {

    @NotNull
    @Min(0)
    private Long seq;

    @Min(-17)
    @Max(17)
    private Float x;

    @Min(-17)
    @Max(17)
    private Float y;

    @Min(-4)
    @Max(5)
    private Float h;

    @Min(-90)
    @Max(90)
    private Float w;

    @Min(2)
    @Max(10)
    private Integer freq = 2;

    @Min(100)
    @Max(1000)
    @JsonProperty("delay_time")
    @JsonAlias({"delayTime"})
    private Integer delayTime = 200;
}
