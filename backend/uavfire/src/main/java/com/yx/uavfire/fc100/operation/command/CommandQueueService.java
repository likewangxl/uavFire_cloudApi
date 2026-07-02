package com.yx.uavfire.fc100.operation.command;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;

public interface CommandQueueService {

    OperationCommandEventEntity enqueue(String targetSn, String commandType, Object payload,
                                        String idempotencyKey, String operator);

    int dispatchDueCommands();

    int markSendTimeouts();

    int markAckTimeouts();

    int ackLatest(String targetSn, String commandType);

    Page<OperationCommandEventEntity> list(String targetSn, String missionNo, int page, int size);
}
