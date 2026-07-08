# Codex 任务书 TC1-B：激光精确坐标上报落库与后端优先级

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线含 TC1-A `5f6eba6`、TC1-C `7f3506c`；**不要执行 git commit**）

## 背景

TC1-A 已产出稳健激光坐标（`FireConfirmationResult.laserFix: RobustLaserFix`，含 lat/lng/alt/confidence=HIGH），但只留在 agent 结果对象里没有上报。本任务打通"激光坐标 → 上报 → fire_event 落库 → 驱动既有机制"的最后一公里。落库后两个既有机制自动联动：T-B 空间合并的定位择优（`geoErrorRadiusM` 更小者覆盖坐标）会把同一火点事件坐标升级为激光值；自动建任务门禁（`RayDemFireGeoLocationService.AUTO_WAYPOINT_MAX_ERROR_RADIUS_M=10.0`）会因激光误差半径 5m 而放行。

## 现状锚点

Agent 端：
- `FireConfirmationProcessor.run()`：MEASURE_CLOSE 成功后调用 `client.recordThermalHotspotEvent(taskId, droneSn, sourceTs, temperatureC, thermalMeasureRoi, thermalMeasurements, thermalImageUrl)`（当前不带坐标）；此时 `laserFix`/`geoMethod` 已就绪。
- `AgentBackendClient.recordThermalHotspotEvent(...)` → `DualStreamEventRequest`（`api/DualStreamEventRequest.kt`，已有 `lat`、`relativeAlt` 等字段，先通读确认全部字段与序列化名）→ HTTP 上报后端。
- `ThermalHotspotMonitor` 也调用同一上报方法——**Monitor 的常规上报不带激光字段（传 null），不得改变其行为**。

后端：
- 接收链路：查 `DualStreamEventRequest` 对应的后端 Controller/DTO（`manage/model/dto/DualStreamEventDTO.java` 已有 `geoErrorRadiusM` :60）→ `DualStreamServiceImpl` 处理热事件 → 组装 `FireEventCreateParam` 调 `FireEventService.create`（先通读这段链路，列出映射点）。
- `FireEventCreateParam`/`FireEventEntity` 已有 `lat/lng/alt/geoMethod/geoErrorRadiusM/geoQuality/geoSourceTs` 完整字段族。
- `FireEventServiceImpl.create` 开头 `resolveFirePointFromGeoSnapshot(param)`——现有 RayDem 解算路径。**当 param 已带 geoMethod=LASER_RANGEFINDER 的坐标时必须跳过/不覆盖**（激光优先于 RayDem）。
- 自动建任务门禁与 `isPrecise(event)`：`FireEventServiceImpl` 内（T-B 评审时见 :165 附近），确认其判定依据（geoErrorRadiusM/geoQuality）。

## 实现要求

### Agent 端

1. `recordThermalHotspotEvent` 及 `DualStreamEventRequest` 增加**可空**字段：`fireLat`、`fireLng`、`fireAlt`、`geoMethod`、`geoErrorRadiusM`（序列化名与后端 DTO 对齐，先看后端已有命名惯例）。
2. `FireConfirmationProcessor` 上报时：`laserFix != null && confidence == "HIGH"` → 带 `fireLat/fireLng/fireAlt = laserFix 值`、`geoMethod = "LASER_RANGEFINDER"`、`geoErrorRadiusM = laserGeoErrorRadiusM`（构造参数，默认 5.0）；否则五个字段全 null（不上报 standoff 坐标——它误差大，落库反而污染择优）。
3. `ThermalHotspotMonitor` 调用处只补 null 参数，行为零变化。

### 后端

4. 事件 DTO/映射链路补齐同名可空字段，透传到 `FireEventCreateParam`。
5. `FireEventServiceImpl.create`：param 带完整激光坐标（lat/lng 非空且 geoMethod=LASER_RANGEFINDER）时，跳过 RayDem 覆盖，`geoQuality` 赋值为使 `isPrecise` 放行的既有取值（对照 isPrecise 实现选择，不发明新枚举值）；`geoSourceTs` 取事件时间戳。
6. 无激光字段的上报走原路径逐字不变。

### 禁改范围

- 不改 T-B 合并择优逻辑、TC1-C dispatcher、`ThermalDwellConfirmer`、对中/激光统计逻辑本身。
- 不改数据库表结构（字段已存在）。

## 测试要求

Agent（扩展 `FireConfirmationProcessorTest`）：
1. `report_carries_laser_fix_when_high_confidence`：HIGH → 上报请求含 fireLat/fireLng/geoMethod=LASER_RANGEFINDER/geoErrorRadiusM=5.0（fake client 捕获断言）。
2. `report_omits_geo_when_laser_unavailable`：laserFix=null → 五字段全 null。
3. Monitor 既有上报测试全部保持通过（字段默认 null）。

后端（扩展现有相应测试类）：
4. `thermal_event_with_laser_geo_creates_precise_fire_event`：带激光字段 → fire_event 的 lat/lng/geoMethod/geoErrorRadiusM 为激光值，RayDem 未覆盖，isPrecise 判定通过。
5. `thermal_event_without_geo_uses_existing_resolution`：不带 → 原 RayDem 路径（与基线一致）。
6. **联动验收**：先创建 RayDem 事件（geoErrorRadiusM=30），再来一条 40m 内的激光上报（=5）→ 合并进同一事件且坐标/geoMethod 升级为激光值（复用 T-B 合并测试的构造方式）。

## 验证方式（必须实际执行并粘贴数字）

```bash
# agent（基数 180）
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
# backend（基数 404）
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\backend" && mvn -pl uavfire test
```

## 验收标准

- [ ] 上述用例全绿，双端全量无回归、无跳过。
- [ ] 无激光字段时前后端行为与基线完全一致（有测试证明）。
- [ ] 映射链路的每个改动文件在完成报告中列出并说明。
- [ ] 不执行 git commit；完成后输出改动文件列表、双端测试数字、偏差说明。
