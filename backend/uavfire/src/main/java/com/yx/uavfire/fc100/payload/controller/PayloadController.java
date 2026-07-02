package com.yx.uavfire.fc100.payload.controller;

import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.mission.model.param.SimpleOperatorParam;
import com.yx.uavfire.fc100.payload.model.param.PayloadConfirmReleaseParam;
import com.yx.uavfire.fc100.payload.service.PayloadService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/fire/missions/{no}/payload")
public class PayloadController {

    private final PayloadService service;

    public PayloadController(PayloadService s) {
        this.service = s;
    }

    @PostMapping("/mark-pending")
    @Idempotent("payload.mark-pending")
    public ApiResult<Void> markPending(@PathVariable("no") String no,
                                        @Valid @RequestBody SimpleOperatorParam p,
                                        HttpServletRequest req) {
        service.markReleasePending(no, p.getOperatorId(),
            req.getRemoteAddr(), req.getHeader("X-Request-Id"));
        return ApiResult.success(null);
    }

    @PostMapping("/confirm-release")
    @Idempotent("payload.confirm-release")
    public ApiResult<Void> confirmRelease(@PathVariable("no") String no,
                                           @RequestBody(required = false) PayloadConfirmReleaseParam p,
                                           HttpServletRequest req) {
        service.confirmRelease(no, p, req.getRemoteAddr(), req.getHeader("X-Request-Id"));
        return ApiResult.success(null);
    }

    @PostMapping("/mark-failed")
    @Idempotent("payload.mark-failed")
    public ApiResult<Void> markFailed(@PathVariable("no") String no,
                                       @Valid @RequestBody SimpleOperatorParam p,
                                       HttpServletRequest req) {
        service.markReleaseFailed(no, p.getOperatorId(), p.getReason(),
            req.getRemoteAddr(), req.getHeader("X-Request-Id"));
        return ApiResult.success(null);
    }

    @PostMapping("/retry-release")
    @Idempotent("payload.retry-release")
    public ApiResult<Void> retry(@PathVariable("no") String no,
                                  @Valid @RequestBody SimpleOperatorParam p,
                                  HttpServletRequest req) {
        service.retryRelease(no, p.getOperatorId(),
            req.getRemoteAddr(), req.getHeader("X-Request-Id"));
        return ApiResult.success(null);
    }
}
