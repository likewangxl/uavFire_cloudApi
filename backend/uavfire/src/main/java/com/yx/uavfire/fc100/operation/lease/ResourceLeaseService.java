package com.yx.uavfire.fc100.operation.lease;

import com.yx.uavfire.fc100.operation.model.entity.OperationResourceLeaseEntity;

import java.time.Duration;

public interface ResourceLeaseService {

    OperationResourceLeaseEntity acquire(String resourceSn, String leaseType, String ownerType,
                                         Long ownerId, Duration ttl);

    void renew(Long leaseId, String ownerType, Long ownerId, Duration ttl);

    void release(Long leaseId, String ownerType, Long ownerId);

    int expireStale();
}
