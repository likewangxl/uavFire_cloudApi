# Codex 任务书 TC1-A：激光测距瞄准与稳健化（云台对中 + 多次测距统计）

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线含 T-C `c98ad67`；**不要执行 git commit**）
- 只改 `rcplus-msdk-agent/` 下本任务书列出的文件。

## 背景

T-C 的 MEASURE_CLOSE 阶段已能读激光测距（`DjiLaserRangefinderClient`，仅 NORMAL 态采信目标经纬度）。但有两个缺陷使坐标不可信：①云台只固定 pitch=-45°，激光沿光轴打，**打中的很可能是火点旁边的地面**；②单次测距在烟雾/热对流环境会产生假回波（读数偏近）。本任务实现"瞄准后稳健测距"。

## 现状锚点（已核实）

- `FireConfirmationProcessor.kt`：`measureClose()`（:237-247，focus-thermal → rotateGimbalToPitch(-45°) → measureThermalHotspot）；激光调用在 run() 内 :162-170（单次，NORMAL 才取坐标）；`LaserRangefinderClient` 接口 :75-81；`DjiLaserRangefinderClient` :366-413。
- `ThermalMeasureRegion(x, y, width, height)` 为 **0–1 归一化**画面坐标（`MsdkStreamBinder.kt:45-53`，CENTER=(0.35,0.35,0.30,0.30)）。热点中心 = `(x+width/2, y+height/2)`，画面中心 = (0.5, 0.5)。
- `GimbalActionClient.rotateGimbal(pitch, yaw, roll)` 与 `rotateGimbalToPitch(pitch)`（`MsdkCommandExecutor.kt:85-95`）。**先读 `DjiFlightControlActionClient` 的实现确认 rotateGimbal 是绝对角还是相对增量**（看它构造的 GimbalAngleRotation/相关 MSDK 值对象的 mode 字段），在代码注释里写明结论；若现有封装只支持绝对角而对中需要相对增量，允许在接口上新增 `rotateGimbalBy(pitchDelta, yawDelta)` 方法并在 Dji 实现中补齐。
- `sessionManager.measureThermalHotspot(droneSn, seedRegion)` 返回 `thermalMeasureRegion`（测温命中区域）。
- 测试参考：`FireConfirmationProcessorTest.kt` 的 fake 风格。

## 实现要求

### 1. 云台对中闭环（新增私有逻辑或独立类 `GimbalAimController`，放同包）

在 `measureClose` 的测温之后、激光测距之前插入对中环节：

```
repeat(最多 aimMaxIterations=3 次):
    roi = 最近一次测温的 thermalMeasureRegion
    cx = roi.x + roi.width/2; cy = roi.y + roi.height/2
    dx = cx - 0.5; dy = cy - 0.5
    若 |dx| < aimToleranceFrac(0.05) 且 |dy| < aimToleranceFrac → 对中成功，结束
    Δyaw   = dx * fovHorizontalDeg(默认 45.0，构造参数可配)
    Δpitch = -dy * fovVerticalDeg(默认 37.0)
    云台按增量旋转(Δpitch, Δyaw)（绝对/相对语义按你核实的结论正确实现）
    重新 measureThermalHotspot 取新 roi
```

- 对中失败（迭代耗尽仍超差、或中途测温失败）**不阻塞流程**：继续激光测距，结果标记 `centered=false`。
- 所有新常量走构造参数默认值模式（对齐现有风格）。

### 2. 稳健激光测距（改 `run()` 中的单次调用为多次统计）

新增数据类与逻辑（可放 `FireConfirmationProcessor.kt` 或拆新文件）：

```kotlin
data class RobustLaserFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val distanceM: Double?,
    val normalSampleCount: Int,
    val centered: Boolean,
    val confidence: String,   // "HIGH" | "LOW"
)
```

规则：

1. 连续测 `laserSampleCount`（默认 5）次，间隔 `laserSampleIntervalMs`（默认 300ms），只保留 state=NORMAL 且 lat/lng 非空的读数。
2. **合理性门限**：预期斜距 `expected = hypot(standoffHorizontalM, 机体相对目标高差(不可得时取 standoffHorizontalM))`；剔除 `distance < 3.0` 或 `distance > 3 × expected` 的读数（烟雾假回波/超距）。
3. 有效读数 ≥ `laserMinNormalSamples`（默认 3）→ 经纬度取**分量中位数**，confidence=HIGH；
4. 有效读数中任意两个的水平距离 > `laserScatterLimitM`（默认 15.0）→ 全部丢弃（confidence 不给 HIGH）；
5. 不满足 3 → 返回 null（沿用 T-C 的 standoff 降级路径，geoMethod 保持 `standoff-hover-point-fallback`）。
6. HIGH 时：`preciseLat/preciseLng` 取中位数值，`geoMethod = "laser-rangefinder"`；`FireConfirmationResult` 增加 `laserFix: RobustLaserFix?` 字段（不改动既有字段语义）。

### 禁改范围

- 不改 `ThermalDwellConfirmer`、`ThermalHotspotMonitor`、`DualStreamSessionManager`、后端任何文件。
- 不改上报 payload（`recordThermalHotspotEvent` 调用原样）——坐标落库是后续 TC1-B 的事。
- `LaserRangefinderClient` 接口保持单次 `measure()` 语义（多次采样在 Processor 层循环调用），必要时接口可加带说明的默认方法但不得破坏现有 fake。

## 测试要求（扩展 `FireConfirmationProcessorTest.kt`，新增对中/统计的独立测试类亦可）

1. `aim_converges_within_iterations`：fake 测温序列 ROI 从偏移逐步回中 → rotateGimbal 调用次数与角度方向正确（dx>0 → yaw 正向等，按你核实的语义断言）。
2. `aim_failure_does_not_block_laser`：对中永不收敛 → 激光仍执行，centered=false。
3. `laser_median_of_normal_samples`：5 次读数含 1 次 NO_SIGNAL、1 次超距假回波 → 取余下 3 个的中位数，confidence=HIGH。
4. `laser_insufficient_normal_falls_back`：仅 2 个 NORMAL → laserFix=null，geoMethod 仍为 standoff 降级。
5. `laser_scatter_rejected`：3 个 NORMAL 但两两相距 >15m → 不给 HIGH。
6. `laser_plausibility_gate_rejects_near_echo`：distance=2m 的读数被剔除。
7. 既有 `FireConfirmationProcessorTest` 用例全部保持通过（fake laser 返回单次可复用，注意新循环语义下的调用次数断言更新要逐条说明）。

## 验证方式（必须实际执行并粘贴数字）

```bash
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
```

## 验收标准

- [ ] 上述测试全绿，agent 全量（173 基数）无回归、无跳过。
- [ ] rotateGimbal 绝对/相对语义的核实结论写在代码注释中。
- [ ] 改动文件严格限于 `FireConfirmationProcessor.kt`（及可选新拆文件、`MsdkCommandExecutor.kt` 仅限新增 rotateGimbalBy）与测试文件。
- [ ] 不执行 git commit；完成后输出改动文件列表、测试数字、偏差说明。
