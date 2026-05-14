package com.yx.uavfire.control.model.param;

import com.yx.uavfire.component.redis.RedisConst;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

/**
 * @author sean
 * @version 1.3
 * @date 2023/1/11
 */
@Data
public class DrcConnectParam {

    @JsonProperty("client_id")
    @JsonAlias({"clientId"})
    private String clientId;

    @JsonProperty("expire_sec")
    @JsonAlias({"expireSec"})
    @Range(min = 1800, max = 86400)
    private long expireSec = RedisConst.DRC_MODE_ALIVE_SECOND;
}
