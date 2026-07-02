package com.yx.uavfire.fc100.operation.lease.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.operation.dao.OperationResourceLeaseMapper;
import com.yx.uavfire.fc100.operation.lease.ResourceLeaseService;
import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class ResourceLeaseServiceImpl implements ResourceLeaseService {

    private static final String ACTIVE = "ACTIVE";
    private static final String RELEASED = "RELEASED";
    private static final String EXPIRED = "EXPIRED";

    private final OperationResourceLeaseMapper mapper;
    private final Clock clock;

    public ResourceLeaseServiceImpl(OperationResourceLeaseMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OperationResourceLeaseEntity acquire(String resourceSn, String leaseType, String ownerType,
                                                Long ownerId, Duration ttl) {
        long now = clock.now();
        OperationResourceLeaseEntity lease = new OperationResourceLeaseEntity();
        lease.setResourceSn(requireText(resourceSn, "resourceSn"));
        lease.setLeaseType(requireText(leaseType, "leaseType"));
        lease.setOwnerType(requireText(ownerType, "ownerType"));
        lease.setOwnerId(ownerId);
        lease.setHeartbeatAt(now);
        lease.setExpiresAt(now + ttl.toMillis());
        lease.setStatus(ACTIVE);
        try {
            mapper.insert(lease);
            return lease;
        } catch (DuplicateKeyException e) {
            throw new Fc100BusinessException(Fc100ErrorCode.RESOURCE_CONFLICT,
                "resource already leased: " + resourceSn);
        }
    }

    @Override
    @Transactional
    public void renew(Long leaseId, String ownerType, Long ownerId, Duration ttl) {
        long now = clock.now();
        OperationResourceLeaseEntity update = new OperationResourceLeaseEntity();
        update.setHeartbeatAt(now);
        update.setExpiresAt(now + ttl.toMillis());
        int affected = mapper.update(update, new UpdateWrapper<OperationResourceLeaseEntity>()
            .eq("id", leaseId)
            .eq("owner_type", ownerType)
            .eq("owner_id", ownerId)
            .eq("status", ACTIVE));
        if (affected == 0) {
            throw new Fc100BusinessException(Fc100ErrorCode.RESOURCE_CONFLICT,
                "active lease not found: " + leaseId);
        }
    }

    @Override
    @Transactional
    public void release(Long leaseId, String ownerType, Long ownerId) {
        if (leaseId == null) {
            return;
        }
        OperationResourceLeaseEntity update = new OperationResourceLeaseEntity();
        update.setStatus(RELEASED);
        int affected = mapper.update(update, new UpdateWrapper<OperationResourceLeaseEntity>()
            .eq("id", leaseId)
            .eq("owner_type", ownerType)
            .eq("owner_id", ownerId)
            .eq("status", ACTIVE));
        if (affected == 0) {
            throw new Fc100BusinessException(Fc100ErrorCode.RESOURCE_CONFLICT,
                "active lease not found: " + leaseId);
        }
    }

    @Override
    @Transactional
    public int expireStale() {
        OperationResourceLeaseEntity update = new OperationResourceLeaseEntity();
        update.setStatus(EXPIRED);
        return mapper.update(update, new UpdateWrapper<OperationResourceLeaseEntity>()
            .eq("status", ACTIVE)
            .lt("expires_at", clock.now()));
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, name + " is required");
        }
        return value.trim();
    }
}
