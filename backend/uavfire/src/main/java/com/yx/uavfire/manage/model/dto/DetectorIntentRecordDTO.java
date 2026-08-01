package com.yx.uavfire.manage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DetectorIntentRecordDTO {
    private String intent;
    private Long version;
}
