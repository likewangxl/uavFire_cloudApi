package com.yx.uavfire.fc100.operation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.operation.command.CommandExecutor;
import com.yx.uavfire.fc100.operation.command.CommandQueueProperties;
import com.yx.uavfire.fc100.operation.command.CommandQueueService;
import com.yx.uavfire.fc100.operation.command.impl.CommandQueueServiceImpl;
import com.yx.uavfire.fc100.operation.dao.OperationCommandEventMapper;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandQueueServiceTest {

    @Test
    void enqueueWithSameIdempotencyKeyReturnsExistingCommandWithoutSecondInsert() {
        OperationCommandEventMapper mapper = mock(OperationCommandEventMapper.class);
        CommandQueueService service = service(mapper, List.of(new NoopExecutor()));
        OperationCommandEventEntity existing = command("CMD-EXISTING", "PENDING", 0);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);
        when(mapper.insert(any(OperationCommandEventEntity.class))).thenAnswer(inv -> {
            OperationCommandEventEntity event = inv.getArgument(0);
            event.setId(11L);
            return 1;
        });

        OperationCommandEventEntity first = service.enqueue("FC100-SN-001", "MOCK",
            Map.of("missionNo", "M-001"), "IDEMP-001", "operator-1");
        OperationCommandEventEntity second = service.enqueue("FC100-SN-001", "MOCK",
            Map.of("missionNo", "M-001"), "IDEMP-001", "operator-1");

        assertNotEquals("CMD-EXISTING", first.getCommandId());
        assertEquals("CMD-EXISTING", second.getCommandId());
        verify(mapper, times(1)).insert(any(OperationCommandEventEntity.class));
    }

    @Test
    void failedCommandRetriesUntilConfiguredLimitThenDead() {
        OperationCommandEventMapper mapper = mock(OperationCommandEventMapper.class);
        OperationCommandEventEntity event = command("CMD-FAIL", "PENDING", 0);
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(inv ->
            "DEAD".equals(event.getStatus()) ? List.of() : List.of(event));
        when(mapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        when(mapper.updateById(any(OperationCommandEventEntity.class))).thenReturn(1);
        CommandQueueService service = service(mapper, List.of(new AlwaysFailExecutor()));

        service.dispatchDueCommands();
        service.dispatchDueCommands();
        service.dispatchDueCommands();

        assertEquals(3, event.getRetryCount());
        assertEquals("DEAD", event.getStatus());
        assertEquals("待人工接管: simulated failure", event.getErrorMessage());
    }

    @Test
    void waitAckCommandTimesOutAndCanBeListedForManualTakeover() {
        OperationCommandEventMapper mapper = mock(OperationCommandEventMapper.class);
        OperationCommandEventEntity event = command("CMD-ACK", "WAIT_ACK", 0);
        event.setSentAt(1769999990000L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(event));
        when(mapper.updateById(any(OperationCommandEventEntity.class))).thenReturn(1);
        CommandQueueService service = service(mapper, List.of(new NoopExecutor()));

        int timedOut = service.markAckTimeouts();

        assertEquals(1, timedOut);
        assertEquals("TIMEOUT", event.getStatus());
        assertEquals("ack timeout; 待人工接管", event.getErrorMessage());
    }

    private CommandQueueService service(OperationCommandEventMapper mapper, List<CommandExecutor> executors) {
        CommandQueueProperties properties = new CommandQueueProperties();
        properties.setMaxRetries(3);
        properties.setAckTimeout(Duration.ofSeconds(5));
        properties.setSendTimeout(Duration.ofSeconds(5));
        properties.setRetryBackoff(Duration.ZERO);
        return new CommandQueueServiceImpl(mapper, executors, new ObjectMapper(), properties, fixedClock());
    }

    private OperationCommandEventEntity command(String commandId, String status, int retryCount) {
        OperationCommandEventEntity event = new OperationCommandEventEntity();
        event.setId(1L);
        event.setCommandId(commandId);
        event.setTargetSn("FC100-SN-001");
        event.setCommandType("MOCK");
        event.setPayloadJson("{\"missionNo\":\"M-001\"}");
        event.setStatus(status);
        event.setRetryCount(retryCount);
        event.setCreateTime(1769999990000L);
        event.setUpdateTime(1769999990000L);
        return event;
    }

    private Clock fixedClock() {
        return () -> 1770000000000L;
    }

    private static class NoopExecutor implements CommandExecutor {
        @Override
        public boolean supports(String commandType) {
            return "MOCK".equals(commandType);
        }

        @Override
        public void execute(OperationCommandEventEntity command) {
        }
    }

    private static class AlwaysFailExecutor implements CommandExecutor {
        @Override
        public boolean supports(String commandType) {
            return "MOCK".equals(commandType);
        }

        @Override
        public void execute(OperationCommandEventEntity command) {
            throw new IllegalStateException("simulated failure");
        }
    }
}
