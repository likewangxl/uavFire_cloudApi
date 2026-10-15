package com.yx.uavfire.msdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.uavfire.msdk.model.MsdkCommandDTO;
import com.yx.uavfire.msdk.model.MsdkCommandParam;
import com.yx.uavfire.msdk.service.InMemoryMsdkCommandStore;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import com.yx.uavfire.msdk.service.RedisMsdkCommandStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MsdkCommandStoreRestartTest {

    @Test
    void commandSurvivesANewServiceInstanceSharingTheStore() {
        InMemoryMsdkCommandStore store = new InMemoryMsdkCommandStore();
        MsdkDeviceStateService writer = new MsdkDeviceStateService(store, () -> 1_000L, 15_000L);
        MsdkCommandParam param = new MsdkCommandParam();
        param.setCommand("hover");
        param.setParams(Map.of());
        MsdkCommandDTO queued = writer.enqueueCommand("AIRCRAFT-1", param);

        MsdkDeviceStateService reader = new MsdkDeviceStateService(store, () -> 2_000L, 15_000L);
        MsdkCommandDTO polled = reader.pollCommand("AIRCRAFT-1").orElseThrow();

        assertEquals(queued.getCommandId(), polled.getCommandId());
        assertEquals("DISPATCHED", polled.getStatus());
    }

    @Test
    void redisStoreRoundTripsTheCommandRecordAndQueue() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        RedisMsdkCommandStore store = new RedisMsdkCommandStore(redis, new ObjectMapper());

        MsdkCommandDTO command = new MsdkCommandDTO()
                .setCommandId("c1")
                .setAircraftSn("AIRCRAFT-1")
                .setCommand("hover")
                .setStatus("PENDING");
        store.save(command);
        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("msdk:cmd:c1"), json.capture(), eq(Duration.ofDays(7)));

        when(values.get("msdk:cmd:c1")).thenReturn(json.getValue());
        assertEquals("hover", store.find("c1").orElseThrow().getCommand());

        store.enqueue("AIRCRAFT-1", "c1");
        verify(lists).rightPush("msdk:cmdq:AIRCRAFT-1", "c1");
        when(lists.leftPop("msdk:cmdq:AIRCRAFT-1")).thenReturn("c1");
        assertEquals("c1", store.dequeue("AIRCRAFT-1").orElseThrow());
        assertTrue(store.find("missing").isEmpty());
    }
}
