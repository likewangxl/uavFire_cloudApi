# Codex 任务书 T-C：Agent 抵近确认作业 FireConfirmationProcessor

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线含 T-A `2f26e81`、T-B `8bf9873`；**不要执行 git commit**）

## 背景

悬停趋势确认（T-A）通过后系统立即上报"疑似火情"，但坐标是巡航高度上的投影推算（误差几十米）、可见光确认是远距拍摄。本任务实现**抵近确认作业**：飞近疑似火点，近距重测温+尝试激光测距获取精确坐标，切可见光拍摄确认，然后强制复位设备状态并恢复巡逻。设计原型是"车辆取证处理器"四阶段模式，为火场改造（保持安全距离、失败必复位）。

## 现状锚点（已核实）

| 事实 | 位置 |
| --- | --- |
| 飞控动作接口：`flyToPoint(lat, lng, altitude, ...)`（KeyFlyToPointEx）、`stopFlyToPoint()`、`hover()` | `rcplus-msdk-agent/.../api/MsdkCommandExecutor.kt:53-82`，实现 `DjiFlightControlActionClient`:113 |
| 云台接口：`rotateGimbalToPitch(pitch)`、`resetGimbal()` | 同文件 :85-95 |
| 相机接口：`setStreamSource(source)`、`setZoom(ratio)` | 同文件 :97-110 |
| 机体实时位置：`FlightControllerKey.KeyAircraftLocation3D` | `sdk/OsdReporter.kt:89` 已有用法 |
| 航线暂停/恢复：`MissionHoldControl`（T-A 新增）+ `WaypointMissionHoldControl`（AppServices.kt 内） | `api/MissionHoldControl.kt`、`AppServices.kt:273-310` |
| 热监测互斥开关：`sessionManager.thermalMonitoringEnabled`（false 时 Monitor 不探测） | `session/DualStreamSessionManager.kt:45,214,219` |
| Agent 命令分发：`DualStreamSessionManager` 按 action 字符串分发（`"thermal-monitor-on"` :213、`"focus-thermal"` :250） | `session/DualStreamSessionManager.kt` |
| 测温：`sessionManager.measureThermalHotspot(droneSn, seedRegion)`；可见光快照：`sessionManager.captureVisibleSnapshot(droneSn)` | `ThermalHotspotMonitor.kt` 现有用法 |
| 事件上报：`AgentBackendClient.recordThermalHotspotEvent(...)`；可见光确认 `VisibleSnapshotConfirmer` | `api/ThermalHotspotMonitor.kt:278-376` |
| 后端 urgent 通道：`URGENT_ACTIONS` 列表 | `backend/uavfire/.../DualStreamServiceImpl.java:73-78` |
| 后端已支持空间合并：同一火点 40m 内复报自动合并进已有 fire_event（T-B）——**抵近作业的上报无需新事件类型，复用现有上报即可被合并** | `FireEventServiceImpl.mergeIntoExisting` |

## 子任务 0（先做）：激光测距可用性调研

调研 DJI MSDK v5（本项目所用版本，见 gradle 依赖）在 M4T 上的激光测距能力：`CameraKey.KeyLaserMeasureEnabled`、`KeyLaserMeasureInformation`（或相近命名，检查 SDK jar/文档中实际存在的 key 与所属 lens/component）。结论写入 `work-records/codex/TC-laser-rf-findings-20260707.md`：key 名称、启用条件（视频源/镜头要求）、返回数据结构（距离/目标经纬度）、以及"可用/不可用"判断依据。
- **可用** → MEASURE_CLOSE 阶段实现测距并用返回的目标位置（或距离+云台姿态解算）作为精确坐标；
- **不可用/不确定** → 实现降级路径（用 FlyTo 悬停点坐标 + 记录 geoMethod 标记），接口留好，勿硬编码猜测的 key。

## 实现要求

### 新建 `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/FireConfirmationProcessor.kt`

四阶段状态机（枚举 `FireConfirmationPhase`：`IDLE, FLY_TO, MEASURE_CLOSE, VISIBLE_CONFIRM, RESET`）：

```kotlin
class FireConfirmationProcessor(
    private val sessionManager: DualStreamSessionManager,
    private val flightControl: FlightControlActionClient,
    private val gimbalControl: GimbalActionClient,
    private val cameraControl: CameraActionClient,
    private val missionHold: MissionHoldControl,
    private val client: AgentBackendClient,
    private val aircraftLocationProvider: () -> AircraftLocation?,   // KeyAircraftLocation3D 封装,可注入 fake
    private val enabled: Boolean = DEFAULT_ENABLED,                  // false！实飞验证前不自动触发
    private val standoffHorizontalM: Double = DEFAULT_STANDOFF_M,    // 60.0
    private val flyToTimeoutMs: Long = DEFAULT_FLY_TO_TIMEOUT_MS,    // 90_000
    private val measureGimbalPitchDeg: Double = DEFAULT_MEASURE_PITCH_DEG,  // -45.0
    private val visibleZoomRatio: Double = DEFAULT_VISIBLE_ZOOM,     // 5.0
    private val resetRetryCount: Int = DEFAULT_RESET_RETRIES,        // 2
    ...
) {
    /** 幂等：已在执行时返回 busy 拒绝。suspend 全程串行执行四阶段。 */
    suspend fun run(request: FireConfirmationRequest): FireConfirmationResult
}

data class FireConfirmationRequest(
    val droneSn: String,
    val taskId: String,          // 沿用疑似事件的 taskId,保证后端合并到同一 fire_event
    val fireLat: Double,
    val fireLng: Double,
    val fireAlt: Double?,        // 可空
)
```

阶段行为：

1. **进入**：置 `sessionManager.thermalMonitoringEnabled = false`（记住原值），`missionHold.holdForConfirmation()`。
2. **FLY_TO**：取当前机体位置，沿"火点→机体"方位线计算距火点 `standoffHorizontalM` 的悬停点（简单平面近似即可：1° lat ≈ 111320m，lng 乘 cos(lat)；机体位置不可得时直接失败转 RESET）；高度取当前飞行高度（不低于当前值）。调用 `flyToPoint`，轮询机体位置直到水平距目标 ≤ 5m 或超时 `flyToTimeoutMs`（超时 → `stopFlyToPoint()` + 失败转 RESET）。
3. **MEASURE_CLOSE**：确保红外源（复用现有 focus-thermal 路径）→ `rotateGimbalToPitch(measureGimbalPitchDeg)` → `measureThermalHotspot` 重测温；若激光测距可用（子任务 0 结论），启用并读取，得到精确火点坐标。把结果通过 `client.recordThermalHotspotEvent(taskId=request.taskId, ...)` 上报（温度、ROI、快照走现有字段；坐标信息如现有 payload 无字段则**不要扩 payload**，在 result 里带回并记日志——后端定位链路自会用 OSD/云台姿态解算，扩传精确坐标留给后续任务）。
4. **VISIBLE_CONFIRM**：切可见光（focus-visible 路径）→ `setZoom(visibleZoomRatio)` → `sessionManager.captureVisibleSnapshot` + 现有 `VisibleSnapshotConfirmer.confirm(...)`（thermalSourceEventId 用 MEASURE_CLOSE 上报的 eventId）。失败不阻塞进 RESET（可见光确认本来就是异步增强语义）。
5. **RESET（无条件必达，任何阶段失败/异常都要走到）**：切回红外源 → `resetGimbal()` → zoom 回 1.0 → 恢复 `thermalMonitoringEnabled` 原值 → `missionHold.resumeAfterConfirmation()`。RESET 内任一步失败重试 `resetRetryCount` 次；重试后仍失败，`Log.e` 告警并继续执行剩余复位步骤（**绝不因单步失败跳过后续复位步骤**）。
6. 返回 `FireConfirmationResult(phaseReached, success, failureReason, closeMeasureTemperatureC, preciseLat/Lng（如有）, resetCompleted)`。

### 触发接入（两条，改 `AppServices.kt` 与 `DualStreamSessionManager.kt`）

1. **自动触发（默认关）**：`ThermalHotspotMonitor` 悬停确认通过并完成上报后，若 processor `enabled=true`，异步启动 `run(...)`（火点坐标暂用请求侧已知信息组装；Monitor 侧只加一个可空回调 `onConfirmedReport: ((taskId, droneSn) -> Unit)?`，坐标由 AppServices 层从 OSD 取机体位置代入——保持 Monitor 不依赖 processor）。
2. **手动指令（联调用）**：`DualStreamSessionManager` 命令分发新增 action `"fire-confirmation-mission"`（params: lat/lng/alt），分发到 processor。后端 `DualStreamServiceImpl.URGENT_ACTIONS` 列表加入 `"fire-confirmation-mission"`（backend 仅此一行 + 对应测试，不改其他后端逻辑）。

### 禁改范围

- 不改 `ThermalDwellConfirmer`、`FireEventServiceImpl`、`ThermalFrameProbe`。
- `ThermalHotspotMonitor` 只允许加可空回调，不改既有上报逻辑。
- 不改现有上报 payload 结构。
- 不引入新第三方依赖。

## 测试要求（`FireConfirmationProcessorTest.kt`，fake 全部依赖，风格对齐现有测试）

失败注入矩阵是重点：

1. `run_completes_all_phases_happy_path`：四阶段顺序执行，RESET 完成，thermalMonitoringEnabled 恢复，resume 被调用。
2. `flyto_timeout_triggers_reset`：位置始终不到 → stopFlyToPoint 被调用 + RESET 完整执行。
3. `measure_failure_triggers_reset`。
4. `visible_confirm_failure_still_resets_and_reports_partial_success`：MEASURE_CLOSE 已上报 → 结果 success（thermal 部分），reset 完成。
5. `reset_step_failure_retries_then_continues`：切红外源连续失败 → 重试 2 次后继续执行 resetGimbal/zoom/resume（验证不中断）。
6. `run_rejected_when_already_running`：并发第二次 run → busy 拒绝，不影响第一次。
7. `disabled_processor_never_autotriggers`：Monitor 回调挂钩在 enabled=false 时零调用。
8. `missing_aircraft_location_fails_to_reset`。
9. 后端：URGENT_ACTIONS 包含 `fire-confirmation-mission` 的既有分类测试模式补一条。
10. 现有 agent 全量单测无回归。

## 验证方式（必须实际执行并粘贴结果）

```bash
# agent
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
# backend
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\backend" && mvn -pl uavfire test
```

## 验收标准

- [ ] 失败注入矩阵全绿；agent 全量 + 后端全量（396 基数）无回归。
- [ ] `enabled=false`（默认）时除新增命令注册外系统行为与基线完全一致。
- [ ] RESET 必达性有测试证明（任意阶段失败路径）。
- [ ] 激光测距调研结论已落档 `TC-laser-rf-findings-20260707.md`。
- [ ] 不执行 git commit；完成后输出改动文件列表、测试数字、偏差说明。
