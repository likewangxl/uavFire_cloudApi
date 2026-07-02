package com.yx.uavfire.fc100.operation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.operation.dao.OperationResourceLeaseMapper;
import com.yx.uavfire.fc100.operation.lease.ResourceLeaseService;
import com.yx.uavfire.fc100.operation.lease.impl.ResourceLeaseServiceImpl;
import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceLeaseServiceTest {

    @Test
    void concurrentAcquireSameResourceAllowsOnlyOneActiveLease() throws Exception {
        OperationResourceLeaseMapper mapper = mock(OperationResourceLeaseMapper.class);
        AtomicBoolean active = new AtomicBoolean(false);
        when(mapper.insert(any(OperationResourceLeaseEntity.class))).thenAnswer(inv -> {
            OperationResourceLeaseEntity lease = inv.getArgument(0);
            if (!active.compareAndSet(false, true)) {
                throw new DuplicateKeyException("duplicate active resource lease");
            }
            lease.setId(100L);
            return 1;
        });
        ResourceLeaseService service = new ResourceLeaseServiceImpl(mapper, fixedClock());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> first = pool.submit(() -> acquireAfterStart(service, ready, start));
        Future<Boolean> second = pool.submit(() -> acquireAfterStart(service, ready, start));

        ready.await();
        start.countDown();

        int success = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        pool.shutdownNow();
        assertEquals(1, success);
    }

    @Test
    void expireStaleMarksTimedOutActiveLeasesAndAllowsReacquire() {
        OperationResourceLeaseMapper mapper = mock(OperationResourceLeaseMapper.class);
        when(mapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        when(mapper.insert(any(OperationResourceLeaseEntity.class))).thenAnswer(inv -> {
            OperationResourceLeaseEntity lease = inv.getArgument(0);
            lease.setId(101L);
            return 1;
        });
        ResourceLeaseService service = new ResourceLeaseServiceImpl(mapper, fixedClock());

        int expired = service.expireStale();
        OperationResourceLeaseEntity reacquired = service.acquire(
            "FC100-SN-001", "DELIVERY_PRIMARY", "INCIDENT", 501L, Duration.ofSeconds(30));

        assertEquals(1, expired);
        assertNotNull(reacquired.getId());
        verify(mapper).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void acquireConflictReturnsResourceConflictError() {
        OperationResourceLeaseMapper mapper = mock(OperationResourceLeaseMapper.class);
        when(mapper.insert(any(OperationResourceLeaseEntity.class)))
            .thenThrow(new DuplicateKeyException("duplicate active resource lease"));
        ResourceLeaseService service = new ResourceLeaseServiceImpl(mapper, fixedClock());

        Fc100BusinessException ex = assertThrows(Fc100BusinessException.class,
            () -> service.acquire("FC100-SN-001", "DELIVERY_PRIMARY", "INCIDENT", 501L, Duration.ofSeconds(30)));

        assertEquals(Fc100ErrorCode.RESOURCE_CONFLICT, ex.getErrorCode());
    }

    @Test
    void renewExtendsOnlyMatchingActiveOwnerLease() {
        OperationResourceLeaseMapper mapper = mock(OperationResourceLeaseMapper.class);
        when(mapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        ResourceLeaseService service = new ResourceLeaseServiceImpl(mapper, fixedClock());

        service.renew(10L, "INCIDENT", 501L, Duration.ofSeconds(60));

        verify(mapper).update(argThat(entity ->
            Long.valueOf(1770000060000L).equals(entity.getExpiresAt())
                && Long.valueOf(1770000000000L).equals(entity.getHeartbeatAt())),
            any(UpdateWrapper.class));
    }

    private boolean acquireAfterStart(ResourceLeaseService service, CountDownLatch ready, CountDownLatch start)
        throws Exception {
        ready.countDown();
        start.await();
        try {
            service.acquire("FC100-SN-001", "DELIVERY_PRIMARY", "INCIDENT", 501L, Duration.ofSeconds(30));
            return true;
        } catch (Fc100BusinessException e) {
            assertEquals(Fc100ErrorCode.RESOURCE_CONFLICT, e.getErrorCode());
            return false;
        }
    }

    private Clock fixedClock() {
        return () -> 1770000000000L;
    }
}
