package com.yx.uavfire.fc100.operation.command.impl;

import com.yx.uavfire.fc100.operation.command.CommandExecutor;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import org.springframework.stereotype.Component;

@Component
public class MockCommandExecutor implements CommandExecutor {

    @Override
    public boolean supports(String commandType) {
        return "MOCK".equals(commandType);
    }

    @Override
    public void execute(OperationCommandEventEntity command) {
    }
}
