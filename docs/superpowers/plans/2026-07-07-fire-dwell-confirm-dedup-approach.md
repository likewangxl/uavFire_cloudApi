# 火情检测"悬停趋势确认 + GPS 去重 + 抵近确认作业"开发方案与实施计划

> **执行方式：** 本计划的编码由 Codex 执行（每个任务一份独立任务书，见各任务的"Codex 任务书"节），Claude 作为大脑负责任务书下发、进度跟踪、diff 评审、测试验证和验收放行。不使用 subagent-driven-development 流程。

**目标：** 在现有"红外帧检测 → 测温 → 上报 → 可见光异步确认"流水线上，增加三个机制：①上报前的悬停温度持续性确认（压误报）；②后端火情事件 GPS 空间去重合并（防重复报警，集群协同前提）；③疑似确认后的抵近精测作业（坐标从几十米级收到米级）。

**架构原则：** 首报延迟不牺牲超过 15 秒；所有新机制可配置开关（演示模式可回退到现行为）;抵近作业失败必须自动复位设备状态；agent 端自主执行作业序列，后端只下发触发与接收结果。

**技术栈：** Kotlin（rcplus-msdk-agent，DJI MSDK v5）、Java 11 / Spring Boot 2.7（backend uavfire 模块）、JUnit + 现有测试风格。

---

## 0. 现状锚点（任务书引用的事实依据，已逐一核实）

### Agent 端（rcplus-msdk-agent）

| 事实 | 位置 |
| --- | --- |
| 帧检测 500ms 一次，回调 `ThermalHotspotCandidateListener.onThermalHotspotCandidate(regions, timestampMs)` | `app/src/main/java/com/yinxin/uavfir/stream/ThermalFrameProbe.kt:21-27, 221-252` |
| 热点监测当前逻辑：单次测温 ≥ 阈值即上报，**无持续性/趋势判定**；阈值 `DEFAULT_REPORT_THRESHOLD_C = 45.0`，probe 间隔 2s，帧触发去抖 2s | `app/src/main/java/com/yinxin/uavfir/api/ThermalHotspotMonitor.kt:455-469` |
| `pollOnceLocked`（轮询路径）与 `measureAndReportHotspot`（帧触发路径）两段近乎重复的"测温→阈值→上报"代码 | `ThermalHotspotMonitor.kt:51-115, 143-193` |
| 测温入口 `sessionManager.measureThermalHotspot(droneSn, seedRegion)`，返回 status/thermalCenterTemperatureC/thermalMeasureRegion/thermalMeasurements/thermalSnapshotPath | `ThermalHotspotMonitor.kt:66-77` |
| 航线暂停/恢复已存在：`pauseMission()` / `resumeMission()` | `app/src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt:136,140` |
| 飞控/云台/相机动作封装已存在：`DjiFlightControlActionClient : FlightControlActionClient, GimbalActionClient, CameraActionClient`，含 `flyToPoint`（KeyFlyToPointEx）、`stopFlyToPoint`、`sendVirtualStick`、hover | `app/src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt:68-179` |
| 尚无激光测距（laser rangefinder）封装（`laserFillLight` 是补光灯，不是测距）；尚无 POI 环绕封装 | 全局搜索确认 |
| 组件装配点 | `app/src/main/java/com/yinxin/uavfir/AppServices.kt` |
| 测试：`ThermalHotspotMonitorTest.kt` 已有 fake sessionManager/client 模式可复用 | `app/src/test/java/com/yinxin/uavfir/api/ThermalHotspotMonitorTest.kt` |

### 后端（backend/uavfire）

| 事实 | 位置 |
| --- | --- |
| fire_event 创建入口目前**只有同 eventId 去重**，无空间去重 | `fc100/event/service/impl/FireEventServiceImpl.java:302-316` |
| `FireEventEntity` 已预留合并字段：`lastSeenTime`、`reportCount`、`lastSourceEventId`、`notificationVersion`、`status`、`confirmedStatus`、`workspaceId`、`lat/lng`、`geoErrorRadiusM` | `fc100/event/model/entity/FireEventEntity.java` |
| 状态枚举：NEW / CANDIDATE / LOW_CONFIDENCE / MISSION_CREATED / IGNORED | `fc100/event/model/enums/FireEventStatus.java` |
| 响应 DTO 已有 reason 机制（如 `"EXISTING_EVENT_ID"`） | `FireEventServiceImpl.java:315`，`FireEventCreateResponse` |
| 配置块：`dual-stream.thermal-measurement-cooldown-ms: 5000`、`thermal-measurement-timeout-ms: 20000` | `uavfire/src/main/resources/application.yml:228-230` |
| 定位质量常量参考：`AUTO_WAYPOINT_MAX_ERROR_RADIUS_M = 10.0` | `fc100/event/service/impl/RayDemFireGeoLocationService.java:16` |

### 环境与基线

- 分支：`feature/fire-precision-and-realtime-detection`，当前有 ~20 个未提交脏文件（2026-07-06 T1/T2/T3 成果，测试已验证通过）。**Round 0 必须先提交基线。**
- Agent 单测：中文路径下 AGP 跑不了，须同步到 `C:\Users\51799\uavfire-verify` 副本执行 `gradlew :app:testDebugUnitTest`（含本地 uxsdk stub，见 2026-07-06 记录）。
- 后端测试：`mvn -pl uavfire test`，基线 385 个测试全绿（2026-07-06），含 `SpringContextSmokeTest` 防装配回归。
- 后端契约：无 `thermalImageUrl` 不建 fire_event；异步补图后必须重报（已有约定，勿破坏）。

---

## 1. 实施计划总览

| 轮次 | 任务 | 执行者 | 依赖 | 预估 |
| --- | --- | --- | --- | --- |
| Round 0 | 提交脏文件基线 + 双端测试复验全绿 | Claude（需用户确认提交） | — | 0.5h |
| Round 1a | T-A：Agent 悬停温度趋势确认（DwellConfirm） | Codex | Round 0 | 3-5h |
| Round 1b | T-B：后端 fire_event GPS 空间去重合并 | Codex（与 1a 并行） | Round 0 | 2-4h |
| 评审门 1 | diff 对照任务书 + 双端测试 + 验收提交 | Claude | 1a/1b 完成 | 1h |
| Round 2 | T-C：Agent 抵近确认作业 FireConfirmationProcessor | Codex | T-A 合入 | 5-8h |
| 评审门 2 | 同上 + 状态复位路径专项审查 | Claude | T-C 完成 | 1.5h |
| Round 3（暂缓） | T-D：POI 环绕多角度投票 + 红外 YOLO 接入 | — | 红外 YOLO 模型训练完成 | 另行规划 |

**评审协议（每个评审门执行）：**
1. `git diff` 与任务书验收标准逐条对照，确认无越界改动（Karpathy 准则：每行改动可追溯到任务书条目）；
2. Agent 侧：同步 `uavfire-verify` 副本跑全量单测；后端侧：`mvn -pl uavfire test`，385 基线 + 新增测试全绿；
3. 检查新配置项有默认值、开关关闭时行为与现状完全一致（用测试证明）；
4. 通过 → 提交（一个任务一个 commit）；不通过 → 写补充指令返给 Codex 修正，不自己动手改（保持职责边界）。

---

## 2. Round 0：基线固化（Claude 执行）

1. 与用户确认后提交当前 20 个脏文件（T1/T2/T3 成果），commit message 概述事件驱动测温 + urgent 通道 + 异步补图重报。
2. 复验：agent 全量单测（uavfire-verify 副本）+ 后端 `mvn -pl uavfire test` 全绿后才允许 Round 1 开工。
3. 若有测试失败：回到 T1/T3 记录排查，禁止带病开工。

---

## 3. Task T-A：Agent 悬停温度趋势确认

### 设计

现状是"单次测温 ≥45°C 立即上报"。改为：触发阈值后进入**悬停确认窗口**——暂停航线、稳定、连续采样多次测温，以"持续性"作为上报门槛，并附带趋势统计供后续调参；不通过则恢复航线继续巡逻。

判定规则刻意保守（Karpathy 最小化）：**只用持续性（K/N 次超阈值）做门槛**；温度趋势/波动统计（区分火与晒热岩石的特征）本期只采集记录、不参与判定——需要实飞数据标定后再启用，避免拍脑袋阈值反而漏火。

**失败语义为 fail-open**：首次触发已 ≥ 阈值、但确认窗口内测温接连出错时，按现行为直接上报（宁误报不漏火），并在日志标注 `dwell-degraded`。

### 文件

- 新建：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/ThermalDwellConfirmer.kt`
- 新建：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/MissionHoldControl.kt`（接口）
- 修改：`ThermalHotspotMonitor.kt`（上报路径接入确认器；顺手合并 `pollOnceLocked` 与 `measureAndReportHotspot` 的重复段为一个私有方法——这两段本就该合）
- 修改：`AppServices.kt`（装配：`WaypointMissionExecutor` 适配为 `MissionHoldControl` 注入）
- 修改：`wayline/WaypointMissionExecutor.kt`（仅当需要暴露"当前是否有执行中航线"的查询时小改）
- 新建测试：`ThermalDwellConfirmerTest.kt`；修改：`ThermalHotspotMonitorTest.kt`

### 关键接口（Codex 按此实现，签名可微调但语义不变）

```kotlin
interface MissionHoldControl {
    /** 暂停当前航线；无执行中航线时返回 false，此时确认流程仍继续（悬停由飞控自然保持） */
    suspend fun holdForConfirmation(): Boolean
    /** 恢复航线；与 holdForConfirmation 配对，幂等 */
    suspend fun resumeAfterConfirmation()
}

class ThermalDwellConfirmer(
    private val sessionManager: DualStreamSessionManager,
    private val missionHold: MissionHoldControl,
    private val enabled: Boolean = DEFAULT_ENABLED,                  // true；false 时 confirm() 直接返回通过（现行为）
    private val stabilizeMs: Long = DEFAULT_STABILIZE_MS,            // 2_000
    private val sampleCount: Int = DEFAULT_SAMPLE_COUNT,             // 4
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS, // 1_500
    private val confirmMinHits: Int = DEFAULT_CONFIRM_MIN_HITS,      // 3（K/N 规则的 K）
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * 前置条件：调用方已测得一次 temperature ≥ threshold（该次计入第 1 个样本）。
     * 返回确认结果；无论结果如何，退出前必须 resumeAfterConfirmation()（finally 保证）。
     */
    suspend fun confirm(
        droneSn: String,
        thresholdC: Double,
        firstSample: DwellSample,
        seedRegion: ThermalMeasureRegion,
    ): DwellConfirmResult
}

data class DwellSample(val temperatureC: Double, val region: ThermalMeasureRegion, val atMs: Long)

data class DwellConfirmResult(
    val confirmed: Boolean,
    val degraded: Boolean,          // 测温故障 fail-open 时为 true
    val samples: List<DwellSample>, // 全部样本（含失败占位除外）
    val bestSample: DwellSample?,   // 温度最高的样本，上报用它的 temperature/region/该轮 snapshot
    val minC: Double?, val maxC: Double?, val spreadC: Double?,  // 趋势统计，只记日志
)
```

### 行为规格

1. `ThermalHotspotMonitor` 中现有两条路径测得 `temperature >= reportThresholdC` 后，不再直接上报，改为调用 `dwellConfirmer.confirm(...)`：
   - `confirmed || degraded` → 走现有上报流程（快照上传、异步补图、可见光确认全部不动），上报温度/区域取 `bestSample`；
   - 否则 → 记一条 `Log.i` 拒绝日志（含全部样本温度），**不上报**，`lastReportedRegion` 不更新。
2. `confirm()` 内部时序：`holdForConfirmation()` → `delay(stabilizeMs)` → 循环 `sampleCount - 1` 次（首样本已计入）：`measureThermalHotspot(seedRegion)` + `delay(sampleIntervalMs)` → 统计命中数 → finally `resumeAfterConfirmation()`。
3. 采样中单次测温失败（status 非 applied 或温度为 null）不计入命中也不中止；**连续 2 次失败**则终止采样进入 fail-open 判定（已触发过阈值 → degraded=true 上报）。
4. `enabled=false` 时 `confirm()` 立即返回 `confirmed=true, samples=[firstSample]`，且**不得**调用 missionHold——用测试锁死"开关关闭 = 现行为零变化"。
5. 并发：确认窗口期间 `probeMutex` 保持持有（现有互斥已天然满足，两条触发路径都在锁内），确保不会并发两个确认窗口。
6. 确认窗口全程 ≤ `stabilizeMs + (sampleCount-1) × (sampleIntervalMs + 测温耗时)` ≈ 10~12s，注释中写明这是首报延迟预算的一部分。

### 测试要求（全部为 JVM 单测，复用现有 fake 风格）

- `confirm_passes_when_min_hits_reached`：4 样本 3 命中 → confirmed。
- `confirm_rejects_when_hits_insufficient`：4 样本 1 命中 → 不上报，且 resumeAfterConfirmation 被调用。
- `confirm_fail_open_on_consecutive_measure_failures`：连续 2 次测温失败 → degraded=true。
- `confirm_disabled_bypasses_hold`：enabled=false → 不调 missionHold、立即通过。
- `resume_called_even_when_measure_throws`：采样抛异常 → resume 仍被调用（finally）。
- `monitor_reports_best_sample_temperature`：Monitor 集成：上报温度 = 样本最大值。
- `monitor_suppresses_report_when_dwell_rejects`：Monitor 集成：拒绝时 client 无上报调用。
- 现有 `ThermalHotspotMonitorTest` 全部用例保持通过（必要时注入 enabled=false 的确认器保持旧断言语义，但至少 2 个旧用例改造为走确认路径）。

### 验收标准

- [ ] 上述测试全绿 + agent 全量单测无回归（uavfire-verify 副本执行）。
- [ ] `enabled=false` 行为与 HEAD 完全一致（专项测试证明）。
- [ ] `pollOnceLocked` / `measureAndReportHotspot` 重复段已合并，行为不变。
- [ ] 除任务书列出的文件外零改动；无新增依赖。

---

## 4. Task T-B：后端 fire_event GPS 空间去重合并

### 设计

创建入口在"同 eventId 去重"之后增加**空间去重**：同 workspace、活跃窗口内、距离 ≤ 去重半径的已有事件 → 合并更新而非新建。去重是"合并"不是"丢弃"——复报刷新 `lastSeenTime`、`reportCount`，温度取 max，定位择优（`geoErrorRadiusM` 更小者胜）。这同时是多机集群不重复报警的基础（去重表天然在后端库里）。

### 文件

- 修改：`backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`（创建入口 :300-318 之后插入空间去重分支；新增私有方法 `findNearbyActiveEvent` 与 `mergeIntoExisting`）
- 修改：`backend/uavfire/src/main/resources/application.yml`（`fc100` 块下新增配置）
- 修改（如需）：`fc100/event/dao/FireEventMapper.java`（若用 XML/注解 SQL；用 MyBatis-Plus QueryWrapper 内联查询则不需要）
- 测试：跟随现有 fire event 相关测试文件的风格新增用例（Codex 先定位现有测试；若无则新建 `FireEventSpatialDedupTest`，风格对齐 `DualStreamServiceImplTest`）

### 配置（含默认值，环境变量可覆盖，风格对齐 application.yml:228-230）

```yaml
fc100:
  fire-event:
    dedup-radius-m: ${FIRE_EVENT_DEDUP_RADIUS_M:40}
    dedup-active-window-ms: ${FIRE_EVENT_DEDUP_WINDOW_MS:1800000}   # 30min
    dedup-enabled: ${FIRE_EVENT_DEDUP_ENABLED:true}
```

### 行为规格

1. 位置：`FireEventServiceImpl` 创建路径，在 eventId 去重（:302-316）返回之后、`long now = clock.now()`（:318）附近，新事件落库之前。
2. 前置：`dedup-enabled=true` 且 param 的 lat/lng 均非 null，否则跳过空间去重走原路径。
3. 候选查询：`workspace_id` 相同、`deleted=0`、`status != 'IGNORED'`、`last_seen_time >= now - window`、lat/lng 非空；先用边界盒预筛（`lat BETWEEN ±radius/111320`，lng 按 `cos(lat)` 修正），再对候选算 Haversine，取距离最近且 ≤ `dedup-radius-m` 者。
4. 命中合并（`mergeIntoExisting`）：
   - `lastSeenTime = now`；`reportCount += 1`（null 视为 1）；`lastSourceEventId = param.eventId`；
   - `thermalTemperature = max(旧, 新)`（null 安全）；
   - 定位择优：新报 `geoErrorRadiusM` 更小（null 视为最大）→ 覆盖 lat/lng/alt/geoMethod/geoErrorRadiusM/geoQuality/geoSourceTs；
   - `thermalImageUrl`/`visibleImageUrl` 仅在旧值为空时补上新值；
   - 状态不动（NEW/CANDIDATE/MISSION_CREATED 保持原状态机推进逻辑）；`updateTime = now`。
   - 响应复用 `FireEventCreateResponse`，reason = `"MERGED_NEARBY"`，返回已有事件 id 及其活跃任务号（对齐 :306-315 的 EXISTING_EVENT_ID 分支结构）。
5. 未命中：原创建路径逐字不动。
6. 并发说明：单实例部署，方法级同步或依赖现有事务即可，不引入分布式锁（YAGNI，注释注明前提）。

### 测试要求

- `create_merges_into_nearby_active_event`：30m 内活跃事件 → 合并，reportCount+1，reason=MERGED_NEARBY，库中事件数不变。
- `create_skips_merge_beyond_radius`：60m 外（默认半径 40m）→ 新建。
- `create_skips_merge_when_window_expired`：lastSeenTime 超窗 → 新建。
- `create_skips_merge_for_ignored_event`：附近事件 status=IGNORED → 新建。
- `merge_keeps_better_geo`：旧 geoErrorRadiusM=5、新=20 → 坐标不被覆盖；反向 → 覆盖。
- `merge_takes_max_temperature`。
- `dedup_disabled_preserves_current_behavior`：开关关 → 与 HEAD 行为一致。
- `create_without_coordinates_skips_spatial_dedup`。
- 385 基线全绿，`SpringContextSmokeTest` 通过（新配置项须有默认值防装配失败）。

### 验收标准

- [ ] 上述测试全绿 + `mvn -pl uavfire test` 无回归。
- [ ] eventId 去重优先级高于空间去重（顺序不变）。
- [ ] 除列出文件外零改动；SQL 无全表扫（有 bbox 预筛；数据量小，暂不加索引，注释标注后续可加 `idx_fire_event_workspace_lastseen`）。

---

## 5. Task T-C：Agent 抵近确认作业 FireConfirmationProcessor（Round 2，T-A 合入后启动）

### 设计

悬停确认通过、"疑似"已上报后，异步执行四阶段抵近作业（借鉴车辆取证处理器模式，为火场改造）：

```
IDLE → FLY_TO → MEASURE_CLOSE → VISIBLE_CONFIRM → RESET → IDLE
任一阶段失败 → RESET（强制，等价车辆取证方案的 Phase D 约束）
```

- **FLY_TO**：`flyToPoint`（MsdkCommandExecutor.kt:167 已有封装）飞至疑似点上风侧留出安全距离的悬停位（水平 standoff 默认 60m、相对高度默认不低于当前巡航高度）；超时 `flyToTimeoutMs`（默认 90s）未到达 → 失败转 RESET。
- **MEASURE_CLOSE**：确保红外源 → 近距重测温（复用 `measureThermalHotspot`）→ **激光测距子任务**：调研 MSDK v5 `KeyLaserMeasureEnabled` / `KeyLaserMeasureInformation`（CameraKey，THERMAL/ZOOM lens）在 M4T 上的可用性；可用则取距离+姿态解算精确火点坐标并随事件上报（geoMethod=`LASER_RANGEFINDER`，geoErrorRadiusM 按测距精度填），不可用则上报 FlyTo 悬停点投影坐标并在能力上报中标记。
- **VISIBLE_CONFIRM**：切可见光 → 复用现有 `captureVisibleSnapshot` + `VisibleSnapshotConfirmer` 流程，`sourceTs`/taskId 沿用原疑似事件 → 后端经 T-B 合并进同一 fire_event（天然协同，不新建事件）。
- **RESET**：切回红外源 → 云台回巡航角 → `resumeAfterConfirmation()`（航线恢复）→ ThermalHotspotMonitor 恢复监测。**RESET 自身出错要重试 2 次并上报告警事件**——设备卡在错误状态比作业失败严重得多。
- 触发：`approach-confirm-enabled` 配置默认 **false**（实飞验证前不自动触发），另提供后端手动指令 `fire-confirmation-mission`（走 urgent 通道）供联调。
- 全程互斥：作业执行中 ThermalHotspotMonitor 暂停探测（避免作业中的相机源切换互相打架）。

### 文件（初列，任务书下发前由 Claude 按 T-A 合入后的代码再校准）

- 新建：`api/FireConfirmationProcessor.kt`、`api/FireConfirmationPhase.kt`
- 修改：`MsdkCommandExecutor.kt`（激光测距 key 封装，若调研可用）、`AppServices.kt`（装配+触发挂钩）、`AgentRuntimeLoop.kt`（新命令注册）、后端 `DualStreamServiceImpl` URGENT_ACTIONS（加 `fire-confirmation-mission`）
- 测试：`FireConfirmationProcessorTest.kt`（重点：每个阶段注入失败 → RESET 必达；RESET 幂等）

### 验收标准（详细任务书在评审门 1 通过后由 Claude 出）

- [ ] 四阶段状态机 + 任意阶段失败必 RESET 的测试矩阵全绿。
- [ ] 开关默认关闭，关闭时系统行为与 T-A 合入态完全一致。
- [ ] 激光测距调研结论落档（可用/不可用 + 依据）。

---

## 6. Round 3（暂缓）：T-D POI 环绕多角度投票 + 红外 YOLO

- 前置：红外 YOLO 模型训练完成（FLAME2 预训练 + M4T 实拍微调，训练中）。
- 内容：MEASURE_CLOSE/VISIBLE_CONFIRM 阶段间插入环绕采集（MSDK POI 或 flyToPoint 多方位点位），多角度 YOLO 投票；ai-service 接入红外 YOLO 检测器替换亮度启发式。
- 此轮到时另出计划，本文档不展开。

---

## 7. 风险与回退

| 风险 | 缓解 |
| --- | --- |
| 悬停确认拉高首报延迟 ~10s | `dwell-confirm` 开关可回退现行为；样本数/间隔可配 |
| 误触发悬停过频（晒热地物多的区域巡逻速度崩） | 帧检测层已有 2s 去抖 + 阈值门槛；实飞后按拒绝日志统计调 confirmMinHits/阈值 |
| 空间去重错误合并两个真实相邻火点 | 半径默认 40m 保守取值;合并保留 reportCount/lastSourceEventId 可追溯；可配 0 关闭 |
| 抵近作业设备状态卡死 | RESET 强制 + 重试 + 告警；开关默认关闭直到实飞验证 |
| Codex 越界改动 | 每任务书含"禁改范围"；评审门逐行对照 |
