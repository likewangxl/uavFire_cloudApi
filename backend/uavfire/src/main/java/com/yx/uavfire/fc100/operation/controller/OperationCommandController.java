package com.yx.uavfire.fc100.operation.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.operation.command.CommandQueueService;
import com.yx.uavfire.fc100.operation.model.entity.OperationCommandEventEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations/commands")
public class OperationCommandController {

    private final CommandQueueService service;

    public OperationCommandController(CommandQueueService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<Page<OperationCommandEventEntity>> list(
            @RequestParam(value = "targetSn", required = false) String targetSn,
            @RequestParam(value = "missionNo", required = false) String missionNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResult.success(service.list(targetSn, missionNo, page, size));
    }
}
