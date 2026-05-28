package com.yx.uavfire.fc100.waypoint.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yx.uavfire.fc100.common.Clock;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.common.GeoUtils;
import com.yx.uavfire.fc100.waypoint.config.Fc100WaypointProperties;
import com.yx.uavfire.fc100.waypoint.dao.MissionWaypointMapper;
import com.yx.uavfire.fc100.waypoint.model.dto.MissionWaypointDTO;
import com.yx.uavfire.fc100.waypoint.model.entity.MissionWaypointEntity;
import com.yx.uavfire.fc100.waypoint.model.enums.WaypointType;
import com.yx.uavfire.fc100.waypoint.model.param.WaypointGenerateParam;
import com.yx.uavfire.fc100.waypoint.policy.DropPointPolicy;
import com.yx.uavfire.fc100.waypoint.service.WaypointPlannerService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WaypointPlannerServiceImpl implements WaypointPlannerService {

    private final DropPointPolicy dropPolicy;
    private final Fc100WaypointProperties props;
    private final MissionWaypointMapper mapper;
    private final Clock clock;

    public WaypointPlannerServiceImpl(DropPointPolicy d, Fc100WaypointProperties p,
                                       MissionWaypointMapper m, Clock c) {
        this.dropPolicy = d;
        this.props = p;
        this.mapper = m;
        this.clock = c;
    }

    @Override
    public List<MissionWaypointDTO> plan(WaypointGenerateParam param) {
        double speed = param.getSpeed() != null ? param.getSpeed() : props.getDefaultCruiseSpeed();
        double cruiseAlt = param.getCruiseAlt() != null ? param.getCruiseAlt()
            : Math.max(param.getFireAlt() + 40, param.getTakeoffAlt() + 50);
        double dropAltAgl = param.getDropAltAgl() != null ? param.getDropAltAgl()
            : props.getDropAltitudeAglM();
        double approachDist = param.getApproachDistance() != null ? param.getApproachDistance() : 200.0;
        double exitDist = param.getExitDistance() != null ? param.getExitDistance() : 60.0;
        double offset = dropPolicy.dropOffset(dropAltAgl, param.getWindSpeed());
        double theta = param.getWindDirectionDeg();   // 上风方向

        // 距离上限
        double straight = GeoUtils.distance(
            param.getTakeoffLat(), param.getTakeoffLng(),
            param.getFireLat(), param.getFireLng());
        if (straight > props.getMaxDistanceFromTakeoffM()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "takeoff-to-fire distance " + (int) straight + "m exceeds max "
                    + (int) props.getMaxDistanceFromTakeoffM());
        }

        if (straight <= props.getShortRouteMaxDistanceM()) {
            var dropPoint = GeoUtils.offset(param.getFireLat(), param.getFireLng(), offset, theta);
            List<MissionWaypointDTO> shortest = new ArrayList<>(2);
            shortest.add(wp(0, WaypointType.TAKEOFF,
                param.getTakeoffLat(), param.getTakeoffLng(),
                param.getTakeoffAlt(), speed, "takeoff"));
            shortest.add(wp(1, WaypointType.DROP,
                dropPoint.lat(), dropPoint.lng(),
                param.getFireAlt() + dropAltAgl, speed, "drop-ready"));
            return shortest;
        }

        List<MissionWaypointDTO> wps = new ArrayList<>(7);

        wps.add(wp(0, WaypointType.TAKEOFF,
            param.getTakeoffLat(), param.getTakeoffLng(),
            param.getTakeoffAlt(), speed, "takeoff"));
        wps.add(wp(1, WaypointType.CLIMB,
            param.getTakeoffLat(), param.getTakeoffLng(),
            cruiseAlt, speed, "climb"));

        var p2 = GeoUtils.offset(param.getFireLat(), param.getFireLng(), approachDist, theta);
        wps.add(wp(2, WaypointType.APPROACH, p2.lat(), p2.lng(), cruiseAlt, speed, "approach"));

        var p3 = GeoUtils.offset(param.getFireLat(), param.getFireLng(), 60.0, theta);
        wps.add(wp(3, WaypointType.HOLD_UPWIND, p3.lat(), p3.lng(), cruiseAlt, speed, "hold"));

        var p4 = GeoUtils.offset(param.getFireLat(), param.getFireLng(), offset, theta);
        wps.add(wp(4, WaypointType.DROP, p4.lat(), p4.lng(),
            param.getFireAlt() + dropAltAgl, speed, "drop-ready"));

        var p5 = GeoUtils.offset(param.getFireLat(), param.getFireLng(), exitDist, theta + 180);
        wps.add(wp(5, WaypointType.EXIT, p5.lat(), p5.lng(), cruiseAlt, speed, "exit"));

        wps.add(wp(6, WaypointType.RETURN,
            param.getTakeoffLat(), param.getTakeoffLng(),
            param.getTakeoffAlt() + 5, speed, "return"));

        // 航线总长度上限
        double total = 0;
        for (int i = 1; i < wps.size(); i++) {
            total += GeoUtils.distance(wps.get(i - 1).getLat(), wps.get(i - 1).getLng(),
                wps.get(i).getLat(), wps.get(i).getLng());
        }
        if (total > props.getMaxRouteLengthM()) {
            throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM,
                "total route length " + (int) total + "m exceeds max "
                    + (int) props.getMaxRouteLengthM());
        }
        return wps;
    }

    private MissionWaypointDTO wp(int idx, WaypointType t, double lat, double lng,
                                   double alt, double speed, String action) {
        MissionWaypointDTO d = new MissionWaypointDTO();
        d.setWaypointIndex(idx);
        d.setWaypointType(t.name());
        d.setLat(lat);
        d.setLng(lng);
        d.setAlt(alt);
        d.setAltitudeReference("ELLIPSOID");
        d.setSpeed(speed);
        d.setAction(action);
        return d;
    }

    @Override
    public int persistForMission(Long missionId, List<MissionWaypointDTO> waypoints) {
        Integer maxVer = currentMaxVersion(missionId);
        int next = maxVer + 1;
        long now = clock.now();
        int inserted = 0;
        for (MissionWaypointDTO d : waypoints) {
            MissionWaypointEntity e = new MissionWaypointEntity();
            BeanUtils.copyProperties(d, e);
            e.setMissionId(missionId);
            e.setWaypointVersion(next);
            e.setCreateTime(now);
            e.setUpdateTime(now);
            inserted += mapper.insert(e);
        }
        return inserted;
    }

    @Override
    public List<MissionWaypointDTO> listLatest(Long missionId) {
        Integer maxVer = currentMaxVersion(missionId);
        if (maxVer == 0) return List.of();
        return mapper.selectList(new QueryWrapper<MissionWaypointEntity>()
                .eq("mission_id", missionId)
                .eq("waypoint_version", maxVer)
                .orderByAsc("waypoint_index"))
            .stream().map(e -> {
                MissionWaypointDTO d = new MissionWaypointDTO();
                BeanUtils.copyProperties(e, d);
                return d;
            }).collect(Collectors.toList());
    }

    private Integer currentMaxVersion(Long missionId) {
        // MyBatis-Plus 简化：直接查行后取 max
        List<MissionWaypointEntity> all = mapper.selectList(
            new QueryWrapper<MissionWaypointEntity>().eq("mission_id", missionId)
                .select("waypoint_version"));
        return all.stream().map(MissionWaypointEntity::getWaypointVersion)
            .max(Integer::compareTo).orElse(0);
    }
}
