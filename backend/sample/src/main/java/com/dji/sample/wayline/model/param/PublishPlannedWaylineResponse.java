package com.dji.sample.wayline.model.param;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishPlannedWaylineResponse {

    private String plannedWaylineId;

    private String publishedWaylineId;

    private String publishedWaylineName;
}
