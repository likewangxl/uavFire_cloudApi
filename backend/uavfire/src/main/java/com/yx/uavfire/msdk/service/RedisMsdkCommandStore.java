package com.yx.uavfire.msdk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.msdk.model.MsdkCommandDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

public final class RedisMsdkCommandStore implements MsdkCommandStore {

    static final String COMMAND_PREFIX = "msdk:cmd:";
    static final String QUEUE_PREFIX = "msdk:cmdq:";
    private static final Duration RECORD_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisMsdkCommandStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(MsdkCommandDTO command) {
        try {
            redis.opsForValue().set(commandKey(command.getCommandId()),
                    objectMapper.writeValueAsString(command), RECORD_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("failed to persist msdk command " + command.getCommandId(), e);
        }
    }

    @Override
    public void enqueue(String aircraftSn, String commandId) {
        redis.opsForList().rightPush(queueKey(aircraftSn), commandId);
    }

    @Override
    public Optional<String> dequeue(String aircraftSn) {
        return Optional.ofNullable(redis.opsForList().leftPop(queueKey(aircraftSn)));
    }

    @Override
    public Optional<MsdkCommandDTO> find(String commandId) {
        if (!StringUtils.hasText(commandId)) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(commandKey(commandId));
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, MsdkCommandDTO.class));
        } catch (Exception e) {
            throw new IllegalStateException("failed to read msdk command " + commandId, e);
        }
    }

    private static String commandKey(String commandId) {
        return COMMAND_PREFIX + commandId;
    }

    private static String queueKey(String aircraftSn) {
        return QUEUE_PREFIX + aircraftSn;
    }
}
