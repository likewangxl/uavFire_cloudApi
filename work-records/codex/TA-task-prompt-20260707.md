# Codex 任务书 T-A：Agent 悬停温度趋势确认（ThermalDwellConfirmer）

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（已有干净基线 commit，你的所有改动将整体作为一个任务提交，**你自己不要执行 git commit**）
- 只允许改动 `rcplus-msdk-agent/` 下本任务书列出的文件。

## 背景

无人机森林火情巡逻中，当前逻辑是"单次红外测温 ≥ 阈值（45°C）立即上报火情事件"。太阳晒热的岩石/裸地会造成误报。本任务引入**悬停确认窗口**：触发阈值后暂停航线悬停，连续采样多次测温，以 K/N 次持续超阈值作为上报门槛；不通过则恢复航线。趋势统计（min/max/spread）本期只记日志，不参与判定。

## 现状锚点（已核实，直接引用）

- `app/src/main/java/com/yinxin/uavfir/api/ThermalHotspotMonitor.kt`
  - 常量：`DEFAULT_REPORT_THRESHOLD_C = 45.0`、`DEFAULT_PROBE_INTERVAL_MS = 2_000`、`DEFAULT_FRAME_TRIGGER_DEBOUNCE_MS = 2_000`（:455-469）
  - 两条触发路径 `pollOnceLocked`（:51-115）与 `measureAndReportHotspot`（:143-193）含近乎重复的"测温→阈值→上报"段，**本任务须把重复段合并为一个私有方法**（行为不变）。
  - 测温调用：`sessionManager.measureThermalHotspot(droneSn, seedRegion)` 返回对象含 `status`（"applied" 为成功）、`thermalCenterTemperatureC`、`thermalMeasureRegion`、`thermalMeasurements`、`thermalSnapshotPath`。
  - 两条路径都在 `probeMutex` 锁内执行——确认窗口天然互斥，无需新增锁。
- `app/src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt`：已有 `pauseMission()`（:136）、`resumeMission()`（:140）。
- 装配点：`app/src/main/java/com/yinxin/uavfir/AppServices.kt`。
- 测试参考：`app/src/test/java/com/yinxin/uavfir/api/ThermalHotspotMonitorTest.kt` 的 fake sessionManager/client 模式。

## 实现要求

### 新建 `app/src/main/java/com/yinxin/uavfir/api/MissionHoldControl.kt`

```kotlin
interface MissionHoldControl {
    /** 暂停当前航线；无执行中航线时返回 false，确认流程仍继续（悬停由飞控自然保持） */
    suspend fun holdForConfirmation(): Boolean
    /** 恢复航线；与 holdForConfirmation 配对，幂等，恢复失败只记日志不抛出 */
    suspend fun resumeAfterConfirmation()
}
```

在 `AppServices.kt` 中用 `WaypointMissionExecutor` 的 `pauseMission()/resumeMission()` 做一个适配实现注入（若 AppServices 中获取 executor 实例的方式不直接，选择最小改动路径并在 PR 说明中写明）。

### 新建 `app/src/main/java/com/yinxin/uavfir/api/ThermalDwellConfirmer.kt`

```kotlin
class ThermalDwellConfirmer(
    private val sessionManager: DualStreamSessionManager,
    private val missionHold: MissionHoldControl,
    private val enabled: Boolean = DEFAULT_ENABLED,                  // true
    private val stabilizeMs: Long = DEFAULT_STABILIZE_MS,            // 2_000
    private val sampleCount: Int = DEFAULT_SAMPLE_COUNT,             // 4（含首样本）
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS, // 1_500
    private val confirmMinHits: Int = DEFAULT_CONFIRM_MIN_HITS,      // 3
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
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
    val degraded: Boolean,
    val samples: List<DwellSample>,
    val bestSample: DwellSample?,   // 温度最高样本
    val minC: Double?, val maxC: Double?, val spreadC: Double?,
)
```

行为：

1. `enabled=false` → 立即返回 `confirmed=true, degraded=false, samples=[firstSample], bestSample=firstSample`，**不得调用** missionHold 与 sessionManager。
2. 否则：`holdForConfirmation()` → `delay(stabilizeMs)` → 循环 `sampleCount - 1` 次：`measureThermalHotspot(droneSn, seedRegion)`，成功且温度非空则记为样本（命中判据 `temperatureC >= thresholdC`），每次之间 `delay(sampleIntervalMs)`。
3. 命中数（含首样本）`>= confirmMinHits` → confirmed=true。
4. 单次测温失败（status 非 applied 或温度 null）不计样本、不中止；**连续 2 次失败**则终止采样，返回 `confirmed=false, degraded=true`（fail-open：调用方按上报处理，日志标 `dwell-degraded`。宁误报不漏火）。
5. `resumeAfterConfirmation()` 必须在 finally 中调用（含异常路径）。
6. 快照来源：确认通过后上报仍走调用方现有流程；`bestSample` 只提供温度与区域。

### 修改 `ThermalHotspotMonitor.kt`

1. 构造器新增 `private val dwellConfirmer: ThermalDwellConfirmer` 参数（放在 `clockMs` 之前，默认值可为基于同 sessionManager 的实例；测试注入 fake）。
2. 合并 `pollOnceLocked` 与 `measureAndReportHotspot` 的重复"测温→阈值→上报"段为一个私有 suspend 方法。
3. 在该方法内：温度 ≥ 阈值后构造 `firstSample` 调用 `dwellConfirmer.confirm(...)`：
   - `confirmed || degraded` → 现有上报流程原样执行（快照上传、异步补图重报、可见光确认**逻辑一律不动**），上报温度/区域改用 `bestSample`（degraded 时即 firstSample）；
   - 否则 → `Log.i` 记录拒绝（含各样本温度），不上报，`lastReportedRegion` 不更新，直接返回。

### 禁改范围

- 不改 `ThermalFrameProbe.kt`、`DjiMsdkStreamBinder.kt`、`RealMsdkStreamProvider.kt`、后端任何文件。
- 不改现有上报 payload 结构（`recordThermalHotspotEvent` 签名不动）。
- 不引入新第三方依赖。

## 测试要求

新建 `app/src/test/java/com/yinxin/uavfir/api/ThermalDwellConfirmerTest.kt`，并更新 `ThermalHotspotMonitorTest.kt`。风格对齐现有测试（fake 对象、无 robolectric）。用例（名字可微调，语义必须覆盖）：

1. `confirm_passes_when_min_hits_reached`：4 样本 3 命中 → confirmed=true。
2. `confirm_rejects_when_hits_insufficient`：4 样本 1 命中 → confirmed=false，且 resume 被调用。
3. `confirm_fail_open_on_consecutive_measure_failures`：连续 2 次失败 → degraded=true。
4. `confirm_disabled_bypasses_hold`：enabled=false → missionHold 零调用、立即通过。
5. `resume_called_even_when_measure_throws`：measure 抛异常 → resume 仍被调用。
6. `best_sample_is_max_temperature`。
7. Monitor 集成：`monitor_reports_best_sample_temperature`、`monitor_suppresses_report_when_dwell_rejects`（client 零上报调用）。
8. 现有 `ThermalHotspotMonitorTest` 用例全部保持通过：默认给测试注入 `enabled=false` 的确认器以保持旧断言，但**至少 2 个用例**改造为注入启用态 fake 确认器走确认路径。

## 验证方式（必须实际执行并粘贴结果）

原仓库路径含中文无法跑 AGP。执行：

```bash
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
```

（robocopy 退出码 ≤7 为成功。）

## 验收标准

- [ ] 全量 agent 单测通过（含新增用例），无跳过、无 @Ignore。
- [ ] enabled=false 时行为与基线完全一致（有专项测试证明）。
- [ ] 重复代码段已合并且行为不变。
- [ ] 改动文件严格限于本任务书清单；不执行 git commit。
- [ ] 完成后输出：改动文件列表、测试运行摘要（通过数）、设计取舍说明（若与任务书有偏差，逐条说明原因）。
