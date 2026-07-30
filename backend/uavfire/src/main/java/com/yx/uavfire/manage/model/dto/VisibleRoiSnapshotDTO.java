package com.yx.uavfire.manage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VisibleRoiSnapshotDTO {

    @JsonProperty("source_ts")
    private Long sourceTs;

    @JsonProperty("visible_roi")
    private Map<String, Double> visibleRoi;
}
