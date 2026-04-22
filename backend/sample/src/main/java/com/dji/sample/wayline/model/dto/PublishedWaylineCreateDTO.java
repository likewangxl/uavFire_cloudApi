package com.dji.sample.wayline.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishedWaylineCreateDTO {

    private String filename;

    private String objectKey;

    private String username;

    private byte[] content;
}
