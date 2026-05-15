package com.yx.uavfire.fc100.event.controller;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.event.model.dto.FireEventCreateResponse;
import com.yx.uavfire.fc100.event.model.dto.FireEventDTO;
import com.yx.uavfire.fc100.event.model.param.FireEventCreateParam;
import com.yx.uavfire.fc100.event.service.FireEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/fire/events")
public class FireEventController {

    private final FireEventService service;

    public FireEventController(FireEventService s) {
        this.service = s;
    }

    @GetMapping
    public ApiResult<List<FireEventDTO>> list(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.success(service.list(workspaceId, status, limit));
    }

    @PostMapping
    public ApiResult<FireEventCreateResponse> create(@Valid @RequestBody FireEventCreateParam param) {
        return ApiResult.success(service.create(param));
    }

    @GetMapping("/{eventId}")
    public ApiResult<FireEventDTO> get(@PathVariable String eventId) {
        FireEventDTO dto = service.get(eventId);
        if (dto == null) {
            throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND,
                "fire event not found: " + eventId);
        }
        return ApiResult.success(dto);
    }
}
