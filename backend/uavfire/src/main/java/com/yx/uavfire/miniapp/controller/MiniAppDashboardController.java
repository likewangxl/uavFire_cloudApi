package com.yx.uavfire.miniapp.controller;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.miniapp.aircraft.AircraftCapabilityProfile;
import com.yx.uavfire.miniapp.aircraft.AircraftCapabilityProfileResolver;
import com.yx.uavfire.miniapp.configuration.MiniAppProperties;
import com.yx.uavfire.miniapp.web.MiniAppResponse;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/miniapp/api/v1/dashboard")
public class MiniAppDashboardController {

    private final MiniAppProperties properties;
    private final MsdkDeviceStateService deviceStateService;
    private final AircraftCapabilityProfileResolver capabilityProfileResolver;

    public MiniAppDashboardController(MiniAppProperties properties,
                                      MsdkDeviceStateService deviceStateService,
                                      AircraftCapabilityProfileResolver capabilityProfileResolver) {
        this.properties = properties;
        this.deviceStateService = deviceStateService;
        this.capabilityProfileResolver = capabilityProfileResolver;
    }

    @GetMapping("/summary")
    public MiniAppResponse<Map<String, Object>> summary(
            HttpServletRequest request, HttpServletResponse response) {
        MiniAppControllerSupport.requireEnabled(properties);
        CustomClaim claim = MiniAppControllerSupport.requireClaim(request);

        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> conclusion = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String dataStatus = "UNKNOWN";

        if (properties.getDashboard().isLiveDeviceSummaryEnabled()) {
            List<MsdkDeviceStateDTO> onlineDevices = deviceStateService.listOnline();
            List<AircraftCapabilityProfile> profiles = onlineDevices.stream()
                    .map(capabilityProfileResolver::resolve)
                    .collect(Collectors.toList());
            long recognizedAircraft = profiles.stream().filter(AircraftCapabilityProfile::isKnownModel).count();
            long controlEligibleAircraft = profiles.stream()
                    .filter(AircraftCapabilityProfile::isFlightControlEligible)
                    .count();
            Map<String, Long> modelFamilies = new LinkedHashMap<>();
            profiles.forEach(profile -> modelFamilies.merge(profile.getFamily().name(), 1L, Long::sum));

            metrics.put("onlineAircraft", onlineDevices.size());
            metrics.put("recognizedAircraft", recognizedAircraft);
            metrics.put("unknownAircraft", onlineDevices.size() - recognizedAircraft);
            metrics.put("controlEligibleAircraft", controlEligibleAircraft);
            metrics.put("modelFamilies", modelFamilies);
            dataStatus = onlineDevices.isEmpty() ? "UNKNOWN" : "FRESH";
            conclusion.put("headline", onlineDevices.isEmpty()
                    ? "当前未发现在线航空器"
                    : "当前有 " + onlineDevices.size() + " 架航空器在线");
            if (recognizedAircraft < onlineDevices.size()) {
                warnings.add("存在未识别机型：仅展示遥测，禁止航线和飞行控制");
            }
        } else {
            conclusion.put("headline", "移动驾驶舱聚合数据接入中");
            warnings.add("在线设备统计未启用：现有设备状态尚未建立工作空间归属映射");
        }

        warnings.add("任务、事件、待办和报告聚合器将在后续迭代接入");
        conclusion.put("workspaceId", claim.getWorkspaceId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conclusion", conclusion);
        data.put("metrics", metrics);
        data.put("activeTasks", Collections.emptyList());
        data.put("urgentEvents", Collections.emptyList());
        data.put("topTodos", Collections.emptyList());
        data.put("latestReport", null);
        data.put("dataStatus", dataStatus);
        data.put("partial", true);
        data.put("warnings", warnings);
        data.put("generatedAt", Instant.now().toString());
        return MiniAppControllerSupport.success(data, request, response);
    }
}
