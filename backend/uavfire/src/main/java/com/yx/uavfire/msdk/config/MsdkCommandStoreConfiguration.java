package com.yx.uavfire.msdk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.msdk.service.MsdkCommandStore;
import com.yx.uavfire.msdk.service.RedisMsdkCommandStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class MsdkCommandStoreConfiguration {

    @Bean
    public MsdkCommandStore msdkCommandStore(StringRedisTemplate redis) {
        return new RedisMsdkCommandStore(redis, new ObjectMapper());
    }
}
