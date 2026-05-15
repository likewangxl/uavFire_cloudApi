package com.yx.uavfire.fc100.review.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.ApiResult;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.idempotency.Idempotent;
import com.yx.uavfire.fc100.mission.dao.FireMissionMapper;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import com.yx.uavfire.fc100.mission.model.enums.FireMissionEvent;
import com.yx.uavfire.fc100.mission.service.MissionStateMachine;
import com.yx.uavfire.fc100.mission.service.TransitCommand;
import com.yx.uavfire.fc100.review.dao.FireReviewMapper;
import com.yx.uavfire.fc100.review.model.entity.FireReviewEntity;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/fire/missions/{no}/review")
public class FireReviewController {

    private final FireMissionMapper missionMapper;
    private final FireReviewMapper reviewMapper;
    private final MissionStateMachine sm;
    private final Clock clock;

    public FireReviewController(FireMissionMapper m, FireReviewMapper r,
                                 MissionStateMachine sm, Clock c) {
        this.missionMapper = m;
        this.reviewMapper = r;
        this.sm = sm;
        this.clock = c;
    }

    @Data
    public static class ReviewParam {
        @NotBlank private String reviewerId;
        private Double afterTemperature;
        private String temperatureUnit;
        private String afterThermalImageUrl;
        private String afterVisibleImageUrl;
        private Boolean fireSuppressed;
        private Boolean needSecondDrop;
        private String remark;
    }

    @PostMapping
    @Idempotent("review.submit")
    public ApiResult<Void> submit(@PathVariable("no") String no,
                                   @Valid @RequestBody ReviewParam p,
                                   HttpServletRequest req) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);

        FireReviewEntity r = new FireReviewEntity();
        r.setMissionId(m.getId());
        r.setAfterTemperature(p.getAfterTemperature());
        r.setTemperatureUnit(p.getTemperatureUnit() != null ? p.getTemperatureUnit() : "K");
        r.setAfterThermalImageUrl(p.getAfterThermalImageUrl());
        r.setAfterVisibleImageUrl(p.getAfterVisibleImageUrl());
        r.setFireSuppressed(Boolean.TRUE.equals(p.getFireSuppressed()) ? 1 : 0);
        r.setNeedSecondDrop(Boolean.TRUE.equals(p.getNeedSecondDrop()) ? 1 : 0);
        r.setReviewerId(p.getReviewerId());
        r.setRemark(p.getRemark());
        r.setSuggestion(buildSuggestion(p));
        r.setDeleted(0);
        long now = clock.now();
        r.setCreateTime(now);
        r.setUpdateTime(now);
        reviewMapper.insert(r);

        sm.transit(TransitCommand.builder()
            .missionNo(no).event(FireMissionEvent.SUBMIT_REVIEW)
            .operatorId(p.getReviewerId())
            .clientIp(req.getRemoteAddr())
            .requestId(req.getHeader("X-Request-Id"))
            .idempotencyKey(req.getHeader("X-Idempotency-Key"))
            .build());
        return ApiResult.success(null);
    }

    @GetMapping
    public ApiResult<FireReviewEntity> get(@PathVariable("no") String no) {
        FireMissionEntity m = missionMapper.selectOne(
            new QueryWrapper<FireMissionEntity>().eq("mission_no", no).eq("deleted", 0));
        if (m == null) throw new Fc100BusinessException(Fc100ErrorCode.MISSION_NOT_FOUND, no);
        return ApiResult.success(reviewMapper.selectOne(
            new QueryWrapper<FireReviewEntity>().eq("mission_id", m.getId())));
    }

    private String buildSuggestion(ReviewParam p) {
        if (Boolean.TRUE.equals(p.getFireSuppressed())) return "fire suppressed, mission can complete";
        if (Boolean.TRUE.equals(p.getNeedSecondDrop())) return "consider second drop (manual approval)";
        return "uncertain - manual decision required";
    }
}
