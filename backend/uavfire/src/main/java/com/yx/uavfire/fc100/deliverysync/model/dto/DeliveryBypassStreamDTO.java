package com.yx.uavfire.fc100.deliverysync.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryBypassStreamDTO {
    private String converterId;
    private String playRtmpUrl;
    private String converterState;
    private Long createTs;
    private Long updateTs;
}
