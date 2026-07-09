# Codex 任务书 TC.3：模拟测试发现修复（F1-F8）

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线 `5ec8bdb`，agent 测试基数 192；**不要执行 git commit**）
- **只改 agent 端**（`rcplus-msdk-agent/`）。后端零改动。
- 依据文档：`docs/superpowers/plans/2026-07-09-simulation-findings.md`（先通读，本任务书是其修复版）。

## 修复项（按文档编号）

### F1 🔴 抵近作业上报必须带热图（否则被后端 DualStreamServiceImpl:798 热图门丢弃）

- `FireConfirmationProcessor` 构造新增 `snapshotUploader: ThermalSnapshotUploader = AiServiceThermalSnapshotUploader()`（对齐 `ThermalHotspotMonitor.kt:19` 的注入方式）。
- 上报前：取**末段合格测量**的 `thermalSnapshotPath`（measure 结果中已有，当前被忽略），非空则 `snapshotUploader.upload(eventId, path)`，重试 2 次/间隔 250ms（对齐 Monitor 的 `THERMAL_SNAPSHOT_UPLOAD_ATTEMPTS` 常量语义），成功 URL 填入 `recordThermalHotspotEvent(thermalImageUrl=...)`。
- 上传失败或路径为空 → 仍上报（现行为）但 `Log.w` 标注 `approach-report-missing-thermal-image`（后端会丢弃，日志留证）。

### F2 🔴 激光坐标位移门限 + 飞行高度不采用激光/事件海拔

- 新构造参数 `maxFixDisplacementM: Double = 80.0`：每次产出 `RobustLaserFix`（单点与 orbitFix）后，计算 fix 与 `request.fireLat/fireLng` 的水平距离，> 门限 → 该 fix 作废（不更新 target、不用于上报，`Log.w` 记录位移值）。
- 飞行高度策略改为：**一律保持机体当前高度**——`flyToStandoffPoint` 与环绕点的 `altitudeM` 只用 `current.altitudeM`，删除 `max(current, target.altitudeM/fireAlt)` 逻辑；递进 target 更新时 `AircraftLocation(fix.lat, fix.lng, target.altitudeM)`（**不用 fix.altitude**）。相关既有测试断言按新策略更新并逐条说明。

### F7 🔴 作业安全守卫（电量 / 总时长 / 位置背离）

- 新增 `BatteryProvider` 接口（`fun currentPercent(): Int?`）+ Dji 实现（调研 MSDK v5 电量 key，优先看 `OsdReporter.kt` 已有读取；拿不到返回 null）+ fake。
- 构造参数：`minBatteryPercentToStart: Int = 30`、`maxMissionDurationMs: Long = 360_000`、`divergenceAbortM: Double = 40.0`。
- run() 开始：电量非 null 且 < 门槛 → 立即失败（`failureReason="battery-below-threshold"`，不进 FLY_TO，仍走 RESET 恢复监测/航线）。
- 总时长：run() 起点记 `clockMs()`，每段/每环绕点开始前检查，超时 → `failureReason="mission-duration-exceeded"` 转 RESET（已有测量结果按 F5 兜底上报）。
- 背离检测：`waitUntilArrived` 记录出现过的最小距离，当前距离 > 最小距离 + `divergenceAbortM` → 返回 false（视为被接管/RTH，`Log.w` 标注 `diverging-from-target`）。

### F3 🟠 激光散布改中位数修剪（废除全弃制）

- `toRobustFix`：先算分量中位数得中心点 → 剔除距中心 > `laserScatterLimitM` 的样本 → 剩余 ≥ `laserMinNormalSamples` 则用**剩余样本**重算中位数产出 fix；否则 null。orbit 跨点合并同规则。

### F4 🟠 dwell 采样失败不占预算

- `ThermalDwellConfirmer`：采样循环改为"直到收集满 `sampleCount-1` 个有效样本或总尝试数达 `sampleCount-1 + failureGraceAttempts`（新参数，默认 2）"；连续 2 次失败 fail-open 语义**保持不变**。

### F5 🟠 末段测量失败用前段结果兜底

- 递进循环中末段 `measureClose` 失败时：若前段已有合格 `finalThermalResult` → 不 abort，跳过环绕、用前段结果继续上报流程（`Log.w` 标注 `final-leg-measure-degraded`）；前段也没有 → 维持现行 abort。

### F6 🟡 可见光确认回上风点拍摄

- 环绕完成后、VISIBLE_CONFIRM 前：若环绕执行过（visited ≥1）且最后点不是起始（上风）方位点 → flyToPoint 回到起始方位点（超时按现有语义，失败不阻塞确认）。新参数 `visibleConfirmAtUpwind: Boolean = true`（false 保持现行为）。

### F8 🟢 环绕点与当前位置重合则跳过飞行

- 环绕点距当前机体位置 ≤ 5m → 不发 flyToPoint 直接测量（visited 照常 +1）。

## 探针转正（在 `docs/superpowers/plans/2026-07-09-simulation-findings.md` 描述的 5 个场景基础上写**修复后语义**的正式回归测试）

1. `background_hit_rejected_by_displacement_gate`：222m 偏移山坡 fix → 作废，target 不更新，上报不带坐标。
2. `smoke_echo_trimmed_fix_survives`：2 烟回波 + 3 真实 → 修剪后 fix = 真实点中位数，HIGH。
3. `dwell_failures_do_not_consume_budget`：交替失败（含 2 次宽限）→ 有效样本收满、确认通过。
4. `final_leg_failure_reports_first_leg_result`：末段测温故障 → 仍上报（前段温度+fix），success=true。
5. `visible_confirm_returns_to_upwind_point`：北风环绕 3 点 → 确认前回到 0° 方位点。

## 新增测试（守卫与快照）

6. `run_rejected_below_battery_threshold`（含 RESET 仍执行、电量 null 不拦）。
7. `mission_duration_guard_aborts_to_reset`。
8. `waituntil_diverging_position_aborts`。
9. `report_uploads_thermal_snapshot_and_carries_url`；`report_logs_when_snapshot_upload_fails`（仍上报）。
10. 高度策略：`flight_altitude_keeps_current_never_laser_altitude`。
11. 既有测试按新语义修订，逐条在完成报告中说明修改原因。

## 禁改范围

- 后端零改动；`ThermalHotspotMonitor` 上报路径不动（F1 只动 processor）；`MissionHoldControl`、对中闭环逻辑不动。
- 不引入新第三方依赖。

## 验证方式（必须实际执行并粘贴数字）

```bash
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
```

（注意：镜像副本的两个测试文件可能残留评审探针 `probe_*` 测试——robocopy /MIR 会用你的新版本覆盖，属预期。）

## 验收标准

- [ ] F1-F8 全部落实；上述 11 组测试全绿；agent 全量（192 基数 + 新增）无回归、无跳过。
- [ ] 电量 key 调研结论写在 `DjiBatteryProvider` 注释。
- [ ] 改动文件严格限于 agent 端；不执行 git commit。
- [ ] 完成报告输出：改动文件列表、测试数字、每条既有测试修改的原因、偏差说明。

## 进度可见性要求（新增）

工作过程中，每完成一个修复项（F1、F2、F7...），向 stdout 打印一行进度标记，格式：
`[TC3-PROGRESS] <编号> <一句话状态>`（例如 `[TC3-PROGRESS] F1 快照上传已实现,开始写测试`）。这些标记会被实时读取展示给用户,请务必输出。