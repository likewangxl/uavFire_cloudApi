package com.yx.uavfire.fc100.operation.command;

import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;

public interface CommandExecutor {

    boolean supports(String commandType);

    void execute(OperationCommandEventEntity command);
}
