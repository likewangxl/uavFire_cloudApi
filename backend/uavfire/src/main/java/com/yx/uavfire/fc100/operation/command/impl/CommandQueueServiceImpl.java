package com.yx.uavfire.fc100.operation.command.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.operation.command.CommandExecutor;
import com.yx.uavfire.fc100.operation.command.CommandQueueProperties;
import com.yx.uavfire.fc100.operation.command.CommandQueueService;
import com.yx.uavfire.fc100.operation.dao.OperationCommandEventMapper;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CommandQueueServiceImpl implements CommandQueueService {

    private static final String PENDING = "PENDING";
    private static final String SENDING = "SENDING";
    private static final String WAIT_ACK = "WAIT_ACK";
    private static final String ACKED = "ACKED";
    private static final String FAILED = "FAILED";
    private static final String TIMEOUT = "TIMEOUT";
    private static final String DEAD = "DEAD";

    private final OperationCommandEventMapper mapper;
    private final List<CommandExecutor> executors;
    private final ObjectMapper objectMapper;
    private final CommandQueueProperties properties;
    private final Clock clock;

    public CommandQueueServiceImpl(OperationCommandEventMapper mapper,
                                   List<CommandExecutor> executors,
                                   ObjectMapper objectMapper,
                                   CommandQueueProperties properties,
                                   Clock clock) {
        this.mapper = mapper;
        this.executors = executors;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OperationCommandEventEntity enqueue(String targetSn, String commandType, Object payload,
                                               String idempotencyKey, String operator) {
        String key = blankToNull(idempotencyKey);
        if (key != null) {
            OperationCommandEventEntity existing = findByIdempotencyKey(key);
            if (existing != null) {
                return existing;
            }
        }
        long now = clock.now();
        OperationCommandEventEntity event = new OperationCommandEventEntity();
        event.setCommandId("CMD-" + UUID.randomUUID());
        event.setTargetSn(requireText(targetSn, "targetSn"));
        event.setCommandType(requireText(commandType, "commandType"));
        event.setMissionNo(extractMissionNo(payload));
        event.setPayloadJson(toJson(payload));
        event.setStatus(PENDING);
        event.setIdempotencyKey(key);
        event.setOperatorId(blankToNull(operator));
        event.setRetryCount(0);
        event.setNextAttemptAt(now);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        try {
            mapper.insert(event);
            return event;
        } catch (DuplicateKeyException e) {
            OperationCommandEventEntity existing = key == null ? null : findByIdempotencyKey(key);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    @Override
    public int dispatchDueCommands() {
        long now = clock.now();
        List<OperationCommandEventEntity> due = mapper.selectList(new QueryWrapper<OperationCommandEventEntity>()
            .in("status", List.of(PENDING, FAILED, TIMEOUT))
            .lt("retry_count", properties.getMaxRetries())
            .and(wrapper -> wrapper.isNull("next_attempt_at").or().le("next_attempt_at", now))
            .orderByAsc("create_time")
            .last("limit " + Math.max(1, properties.getDispatchBatchSize())));
        int handled = 0;
        for (OperationCommandEventEntity command : due == null ? List.<OperationCommandEventEntity>of() : due) {
            if (claimForSending(command)) {
                executeClaimed(command);
                handled++;
            }
        }
        return handled;
    }

    @Override
    @Transactional
    public int markSendTimeouts() {
        long now = clock.now();
        List<OperationCommandEventEntity> sending = mapper.selectList(new QueryWrapper<OperationCommandEventEntity>()
            .eq("status", SENDING)
            .lt("update_time", now - properties.getSendTimeout().toMillis()));
        int count = 0;
        for (OperationCommandEventEntity event : sending == null ? List.<OperationCommandEventEntity>of() : sending) {
            markTimeout(event, "send timeout; 待人工接管", now);
            count++;
        }
        return count;
    }

    @Override
    @Transactional
    public int markAckTimeouts() {
        long now = clock.now();
        List<OperationCommandEventEntity> waiting = mapper.selectList(new QueryWrapper<OperationCommandEventEntity>()
            .eq("status", WAIT_ACK)
            .lt("sent_at", now - properties.getAckTimeout().toMillis()));
        int count = 0;
        for (OperationCommandEventEntity event : waiting == null ? List.<OperationCommandEventEntity>of() : waiting) {
            markTimeout(event, "ack timeout; 待人工接管", now);
            count++;
        }
        return count;
    }

    @Override
    @Transactional
    public int ackLatest(String targetSn, String commandType) {
        QueryWrapper<OperationCommandEventEntity> query = new QueryWrapper<OperationCommandEventEntity>()
            .eq("target_sn", targetSn)
            .eq("status", WAIT_ACK)
            .orderByDesc("sent_at")
            .last("limit 1");
        if (commandType != null && !commandType.isBlank()) {
            query.eq("command_type", commandType);
        }
        OperationCommandEventEntity latest = mapper.selectOne(query);
        if (latest == null) {
            return 0;
        }
        long now = clock.now();
        latest.setStatus(ACKED);
        latest.setAckAt(now);
        latest.setUpdateTime(now);
        latest.setErrorMessage(null);
        return mapper.updateById(latest);
    }

    @Override
    public Page<OperationCommandEventEntity> list(String targetSn, String missionNo, int page, int size) {
        QueryWrapper<OperationCommandEventEntity> query = new QueryWrapper<>();
        if (targetSn != null && !targetSn.isBlank()) {
            query.eq("target_sn", targetSn.trim());
        }
        if (missionNo != null && !missionNo.isBlank()) {
            query.eq("mission_no", missionNo.trim());
        }
        query.orderByDesc("create_time").orderByDesc("id");
        return mapper.selectPage(new Page<>(Math.max(1, page), Math.max(1, size)), query);
    }

    private boolean claimForSending(OperationCommandEventEntity command) {
        OperationCommandEventEntity update = new OperationCommandEventEntity();
        update.setStatus(SENDING);
        update.setUpdateTime(clock.now());
        return mapper.update(update, new UpdateWrapper<OperationCommandEventEntity>()
            .eq("id", command.getId())
            .eq("status", command.getStatus())) == 1;
    }

    private void executeClaimed(OperationCommandEventEntity command) {
        try {
            executorFor(command.getCommandType()).execute(command);
            long now = clock.now();
            command.setStatus(WAIT_ACK);
            command.setSentAt(now);
            command.setUpdateTime(now);
            command.setErrorMessage(null);
            mapper.updateById(command);
        } catch (RuntimeException e) {
            markFailed(command, e);
        }
    }

    private void markFailed(OperationCommandEventEntity command, RuntimeException e) {
        long now = clock.now();
        int retryCount = command.getRetryCount() == null ? 0 : command.getRetryCount();
        retryCount++;
        command.setRetryCount(retryCount);
        command.setUpdateTime(now);
        command.setNextAttemptAt(now + properties.getRetryBackoff().toMillis());
        if (retryCount >= properties.getMaxRetries()) {
            command.setStatus(DEAD);
            command.setErrorMessage("待人工接管: " + safeMessage(e));
        } else {
            command.setStatus(FAILED);
            command.setErrorMessage(safeMessage(e));
        }
        mapper.updateById(command);
    }

    private void markTimeout(OperationCommandEventEntity command, String message, long now) {
        int retryCount = command.getRetryCount() == null ? 0 : command.getRetryCount();
        retryCount++;
        command.setRetryCount(retryCount);
        command.setUpdateTime(now);
        command.setNextAttemptAt(now + properties.getRetryBackoff().toMillis());
        if (retryCount >= properties.getMaxRetries()) {
            command.setStatus(DEAD);
            command.setErrorMessage("待人工接管: " + message);
        } else {
            command.setStatus(TIMEOUT);
            command.setErrorMessage(message);
        }
        mapper.updateById(command);
    }

    private CommandExecutor executorFor(String commandType) {
        return executors.stream()
            .filter(executor -> executor.supports(commandType))
            .findFirst()
            .orElseThrow(() -> new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "no command executor for type: " + commandType));
    }

    private OperationCommandEventEntity findByIdempotencyKey(String key) {
        return mapper.selectOne(new QueryWrapper<OperationCommandEventEntity>()
            .eq("idempotency_key", key)
            .last("limit 1"));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "invalid command payload: " + e.getMessage());
        }
    }

    private String extractMissionNo(Object payload) {
        try {
            JsonNode node = objectMapper.valueToTree(payload == null ? java.util.Map.of() : payload);
            JsonNode missionNo = node.get("missionNo");
            return missionNo == null || missionNo.asText().isBlank() ? null : missionNo.asText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, name + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
