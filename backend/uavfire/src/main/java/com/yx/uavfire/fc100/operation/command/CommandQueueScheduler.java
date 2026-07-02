package com.yx.uavfire.fc100.operation.command;

import com.yx.uavfire.fc100.operation.lease.ResourceLeaseService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CommandQueueScheduler {

    private final CommandQueueService commandQueueService;
    private final ResourceLeaseService resourceLeaseService;

    public CommandQueueScheduler(CommandQueueService commandQueueService,
                                 ResourceLeaseService resourceLeaseService) {
        this.commandQueueService = commandQueueService;
        this.resourceLeaseService = resourceLeaseService;
    }

    @Scheduled(initialDelay = 5, fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void tickOperationQueue() {
        resourceLeaseService.expireStale();
        commandQueueService.markSendTimeouts();
        commandQueueService.markAckTimeouts();
        commandQueueService.dispatchDueCommands();
    }
}
