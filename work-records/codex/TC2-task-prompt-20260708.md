# Codex 任务书 TC.2：两段递进抵近 + 离散环绕激光交会 + 上风向接近

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线 `d14e004`，agent 测试基数 182；**不要执行 git commit**）
- **只改 agent 端**：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/FireConfirmationProcessor.kt`（及可选新拆文件）、`MsdkCommandExecutor.kt`（仅限新增风信息读取封装）、对应测试。**后端零改动。**

## 背景

当前抵近作业是"单跳"：按疑似坐标（误差几十米）飞到 60m 悬停位，测一次就走。三个问题：①悬停位依据的是粗坐标，激光测出精确坐标后作业已结束，没有利用；②单方位观测，姿态系统偏差无法消除；③接近方位不看风，下风侧观测会被烟柱干扰激光和可见光。

## 现状锚点（已核实）

- `FireConfirmationProcessor.run()`：FLY_TO（`flyToStandoffPoint` 计算悬停位：沿火点→机体方位线取 standoff 距离，:209-225）→ MEASURE_CLOSE（`measureClose` 对中 + `measureRobustLaserFix` 多次激光中位数）→ VISIBLE_CONFIRM → RESET（必达）。
- 悬停位方位选择：`flyToStandoffPoint` 用当前机体相对火点的单位向量（无风考虑）。
- `RobustLaserFix(latitude, longitude, altitude, distanceM, normalSampleCount, centered, confidence)`。
- 激光采样参数：`laserSampleCount=5`、`laserSampleIntervalMs=300`、`laserMinNormalSamples=3`、`laserScatterLimitM=15`。
- 到达判定 `waitUntilArrived`：水平 ≤5m，超时 `flyToTimeoutMs=90s`（当前整个 FLY_TO 一个超时）。
- 风信息：MSDK v5 FlightControllerKey 有风速/风向相关 key（如 `KeyWindSpeed`/`KeyWindDirection`，**先在 SDK jar 里核实确切名称、单位与枚举语义**，参考 `OsdReporter.kt` 的 key 读取写法；调研结论写代码注释）。
- 测试：`FireConfirmationProcessorTest.kt`，fake 风格齐全（RecordingLaserRangefinder 支持 vararg 序列等）。

## 实现要求

### 1. 两段递进抵近（approach legs）

构造参数 `approachLegsM: List<Double> = DEFAULT_APPROACH_LEGS`（默认 `[100.0, 60.0]`）。执行语义：

```
target = 请求坐标(疑似粗坐标)
for (leg in approachLegsM):
    悬停位 = 距 target 为 leg 的接近点(方位见第3条)
    flyToPoint + waitUntilArrived(每段独立超时 flyToTimeoutMs)
    对中 + measureRobustLaserFix
    若得到 HIGH 置信 fix → target = fix 坐标   // 下一段朝精确坐标飞
    (无 fix → target 不变,继续下一段)
```

- 任一段飞行超时/失败 → 停止递进转 RESET（已上报的测温结果保留，语义同现状）。
- 每段的测温上报只在**最后一段**做（避免中间段重复上报刷事件）；中间段的激光 fix 只用于修正 target，全部记日志。
- `phaseReached` 语义保持（FLY_TO/MEASURE_CLOSE 循环使用，结果加 `legsCompleted: Int`）。

### 2. 离散环绕交会（orbit bearings）

构造参数：`orbitBearingCount: Int = 3`（0=禁用）、`orbitPerPointLaserSamples: Int = 3`、`orbitRadiusM` 默认取 `approachLegsM.last()`。

- 最后一段测量完成后执行：以 target（此时应为激光坐标）为圆心、`orbitRadiusM` 为半径，从当前方位角起按 `360/orbitBearingCount` 间隔依次 flyToPoint 到各方位点；每点对中 + 采 `orbitPerPointLaserSamples` 次激光（复用现有过滤：NORMAL/合理性/散布）。
- **交会融合**：所有方位点的有效样本合并取分量中位数 → `orbitFix`；有效样本总数 ≥ `laserMinNormalSamples` 才产出。`orbitFix` 优于单点 fix：最终上报/结果中 `preciseLat/Lng` 用 orbitFix（无 orbitFix 回退单点 fix）。
- 任一方位点飞行超时 → 跳过该点继续下一点（环绕是增强，不因单点失败放弃整体）；全部点失败 → 用已有单点 fix。
- 上报时机改为**环绕完成后**（一次上报带最终坐标），VISIBLE_CONFIRM 在最后一个方位点位置执行。
- `FireConfirmationResult` 增加 `orbitFix: RobustLaserFix?`、`orbitPointsVisited: Int`。

### 3. 上风向接近偏置

- 新增 `WindProvider`（接口 + Dji 实现读 MSDK 风向/风速 key + fake），构造注入。
- 接近方位角选择：风速 ≥ `upwindMinWindSpeedMps`（默认 2.0）且风向可得 → 接近点取火点的**上风侧**（从风的来向接近，即接近点方位角 = 风向的来向）；否则回退现状（火点→机体方位）。
- 环绕起始方位同样从上风点开始（烟柱在下风,前几个观测点先占干净视角）。
- 调研结论（key 名、单位、风向语义是吹来向还是吹去向）写在 `DjiWindProvider` 注释里,拿不准就在注释中明确假设并标注"待实机验证"。

### 禁改范围

- RESET 语义、`ThermalDwellConfirmer`、`ThermalHotspotMonitor`、上报 payload 结构、后端一律不动。
- 对中与稳健激光的既有实现只允许复用/参数化,不允许改判定规则。

## 测试要求（扩展 FireConfirmationProcessorTest，可拆新测试类）

1. `legs_progress_toward_laser_fix`：第一段得到 HIGH fix → 第二段 flyToPoint 目标为 fix 坐标（fake 捕获断言）。
2. `legs_keep_original_target_without_fix`：第一段无 fix → 第二段仍朝原坐标。
3. `leg_flyto_timeout_triggers_reset_preserving_report`。
4. `orbit_visits_configured_bearings`：3 点 → flyToPoint 共 2(legs)+3(orbit) 次,方位角间隔 ~120°。
5. `orbit_fix_median_across_points`：跨点样本合并中位数正确。
6. `orbit_point_timeout_skipped_not_fatal`：1 点超时 → orbitPointsVisited=2,流程完成。
7. `orbit_disabled_by_zero_count`：=0 → 行为等价"仅递进"。
8. `upwind_bias_selects_wind_source_bearing`：风 3m/s → 接近点在火点上风侧（方位断言）。
9. `no_wind_falls_back_to_aircraft_bearing`：风不可得/风速 1m/s → 现状方位。
10. `report_happens_once_after_orbit`：全程仅一次 recordThermalHotspotEvent,坐标为 orbitFix。
11. 既有用例全部保持通过（单跳语义的用例改造为 `approachLegsM=[60.0]`+`orbitBearingCount=0` 以保持旧断言,逐条说明）。

## 验证方式（必须实际执行并粘贴数字）

```bash
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
```

## 验收标准

- [ ] 上述用例全绿,agent 全量（182 基数）无回归、无跳过;后端 `mvn -pl uavfire test`（409 基数）确认零影响。
- [ ] 风 key 调研结论落在代码注释。
- [ ] 所有新参数有默认值,`approachLegsM=[60.0]`+`orbitBearingCount=0`+无风时行为与基线一致（有测试证明）。
- [ ] 不执行 git commit;完成后输出改动文件列表、测试数字、偏差说明。
