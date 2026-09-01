package com.yx.uavfire.manage.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
public class AgentFireEvidenceReceiptDTO {
    private String eventId;
    private String status;
    private String visibleImageUrl;
    private String evidenceSha256;
    private Long evidenceCapturedAt;
}
