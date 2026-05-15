package com.yx.uavfire.fc100.event.model.param;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

@Data
public class FireEventCreateParam {

    @NotBlank @Size(max = 64)
    private String eventId;

    @NotBlank @Size(max = 32)
    private String source;       // M4T / MANUAL / TEST

    @Size(max = 64)
    private String deviceSn;

    @NotNull @DecimalMin("0.0") @DecimalMax("1.0")
    private BigDecimal confidence;

    @Size(max = 16)
    private String fireLevel;     // LOW / MEDIUM / HIGH / UNKNOWN

    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
    private Double lat;

    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
    private Double lng;

    private Double alt;

    @Pattern(regexp = "ELLIPSOID|AGL|ASL")
    private String altitudeReference;

    private Double thermalTemperature;

    @Pattern(regexp = "K|C")
    private String temperatureUnit;

    private String thermalImageUrl;

    private String visibleImageUrl;

    /** ISO8601 字符串，服务端转 epoch ms */
    @NotBlank
    private String timestamp;

    @Size(max = 64)
    private String workspaceId;
}
