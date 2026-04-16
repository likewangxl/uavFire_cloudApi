package com.dji.sample.control.model.param;

import com.dji.sample.component.redis.RedisConst;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Range;

import javax.validation.Valid;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;

/**
 * @author sean
 * @version 1.3
 * @date 2023/1/11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrcModeParam {

    @NotBlank
    @JsonProperty("client_id")
    @JsonAlias({"clientId"})
    private String clientId;

    @JsonProperty("dock_sn")
    @JsonAlias({"dockSn"})
    private String dockSn;

    @JsonProperty("gateway_sn")
    @JsonAlias({"gatewaySn"})
    private String gatewaySn;

    @Range(min = 1800, max = 86400)
    @Builder.Default
    @JsonProperty("expire_sec")
    @JsonAlias({"expireSec"})
    private long expireSec = RedisConst.DRC_MODE_ALIVE_SECOND;

    @Valid
    @Builder.Default
    private DeviceDrcInfoParam deviceInfo = new DeviceDrcInfoParam();

    @AssertTrue(message = "dock_sn or gateway_sn is required")
    public boolean isTargetValid() {
        return (dockSn != null && !dockSn.isBlank()) || (gatewaySn != null && !gatewaySn.isBlank());
    }

    public String getTargetSn() {
        return gatewaySn != null && !gatewaySn.isBlank() ? gatewaySn : dockSn;
    }

    public boolean isPilotGatewayScenario() {
        return gatewaySn != null && !gatewaySn.isBlank();
    }
}
