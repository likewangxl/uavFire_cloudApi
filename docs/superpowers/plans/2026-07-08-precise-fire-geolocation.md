# 精确火点坐标（激光测距落地）改造方案 —— Round 3 / TC.1

> **执行方式：** Codex 编码（任务书 `work-records/codex/TC1A|TC1B|TC1C-task-prompt-20260708.md`），Claude 出任务书、评审 diff、独立复跑测试、验收提交。

**目标：** 把 T-C 已打通的激光测距（`LaserMeasureInformation.getLocation3D()` 直接返回目标点经纬度）变成可信、落库、驱动业务的精确火点坐标。

**原理澄清（用户问题的答案）：** MSDK 激光测距返回的是**激光打中的目标点坐标**（机内融合飞机位置+云台姿态+距离解算），不是飞机位置。前提：①激光开启；②激光光轴瞄准火点。T-C 已实现"读"，本轮补三块：**瞄得准**（云台按热点 ROI 对中）、**读得稳**（多次测距稳健统计+合理性门限）、**用得上**（坐标上报落库并驱动去重合并/自动建任务门禁）。

## 现状锚点（已核实）

- `ThermalMeasureRegion(x,y,width,height)` 为 **0–1 归一化**坐标（`CENTER=(0.35,0.35,0.30,0.30)`，`MsdkStreamBinder.kt:45-53`）→ 热点中心 `(x+w/2, y+h/2)`，画面中心 `(0.5,0.5)`。
- `GimbalActionClient.rotateGimbal(pitch, yaw, roll)`（`MsdkCommandExecutor.kt:88`）——绝对/相对语义需实现时在 `DjiFlightControlActionClient` 里核实并注释。
- 激光封装 `DjiLaserRangefinderClient`（`FireConfirmationProcessor.kt:366`）：OPEN_ON_DEMAND → enable → 500ms → 读 `KeyLaserMeasureInformation`，仅 NORMAL 采信；最小工作距离 3m。
- Agent 上报 `DualStreamEventRequest` 已有 `lat`/`relativeAlt` 字段（:39,42）；后端 `DualStreamEventDTO` 已有 `geoErrorRadiusM`（:60）；`FireEventCreateParam`/`FireEventEntity` 已有完整 geo 字段族（geoMethod/geoErrorRadiusM/geoQuality/geoSourceTs）。
- 后端自动建任务门禁：`RayDemFireGeoLocationService.AUTO_WAYPOINT_MAX_ERROR_RADIUS_M = 10.0`；空间去重合并按 `geoErrorRadiusM` 更小者覆盖坐标（T-B）——**激光坐标（误差 ~5m）落库后自动赢得合并择优并通过建任务门禁**，两处既有机制免费联动。
- `fire-confirmation-mission` 指令已支持 lat/lng params（T-C），走 urgent 通道。

## 任务拆分与顺序

| 任务 | 内容 | 端 | 依赖 |
| --- | --- | --- | --- |
| TC1-A | 云台按热点 ROI 迭代对中 + 激光多次测距稳健统计 + 合理性门限 | agent | — |
| TC1-C | 后端疑似事件自动派发抵近作业指令（默认关） | backend | —（与 A 并行） |
| TC1-B | 激光坐标上报落库：agent 报文扩展 + 后端映射/优先级 + 与去重/门禁联动测试 | 两端 | A、C 合入后 |

评审协议与验证命令同 `2026-07-07-fire-dwell-confirm-dedup-approach.md` 第 1 节（agent 测试走 uavfire-verify 副本，后端 396 基数全绿，逐条对照任务书，一任务一 commit）。

## 设计要点

### TC1-A 云台对中 + 稳健激光

- 对中闭环：测温得 ROI → 中心偏移 `(dx,dy)=(cx-0.5, cy-0.5)` → 角度修正 `Δyaw=dx×FOV_H`、`Δpitch=-dy×FOV_V`（红外 FOV 可配，默认 H=45°/V=37°，闭环迭代不要求精确）→ rotateGimbal → 重测温，迭代至偏移 <0.05 或最多 3 轮。对中失败不阻塞（激光仍测，结果标记 uncentered）。
- 稳健测距：N=5 次、间隔 300ms，仅计 NORMAL 读数；≥3 个才采信；经纬度取**分量中位数**；散布门限：任意两读数水平距 >15m → 判低置信丢弃。
- 合理性门限（防烟雾假回波）：激光距离必须落在 `[3m, 3×预期斜距]`，预期斜距由 standoff 与相对高度估算；越界读数剔除。
- 产出：`RobustLaserFix(lat, lng, alt, distanceM, samples, centered, confidence)` 挂到 `FireConfirmationResult`。

### TC1-C 后端自动派发（坐标权威在后端）

- T-C 的 agent 端自动触发用机体位置代火点，是权宜之计。正确架构：**后端**收到热确认疑似事件（fire_event 创建/合并成功且带坐标）后，若 `fc100.fire-event.auto-approach-enabled=true`（默认 false），向该机 urgent 队列投递 `fire-confirmation-mission(lat,lng,alt,taskId)` ——坐标用后端解算/合并后的最优值。
- 防重入：同一 fire_event 在 `auto-approach-cooldown-ms`（默认 10min）内只派发一次；事件已 MISSION_CREATED/IGNORED 不派发。

### TC1-B 坐标落库（A、C 后出详细任务书）

- Agent：作业完成后的热事件上报附 `fireLat/fireLng/fireAlt/geoMethod/geoErrorRadiusM`（laser 置信时 `LASER_RANGEFINDER`/5.0，否则不带）。
- 后端：报文映射进 `FireEventCreateParam`；创建流程中**带 geoMethod=LASER_RANGEFINDER 的坐标优先于 RayDem 解算**；配合 T-B 合并择优，同一火点事件坐标自动升级为激光值；`geoQuality` 按现有枚举语义赋值使自动建任务门禁（≤10m）放行。
- 联动验收测试：疑似(RayDem 30m 误差) → 抵近上报(laser 5m) → 合并后事件坐标=激光值、geoErrorRadiusM=5、可自动建任务。

## 风险

| 风险 | 缓解 |
| --- | --- |
| rotateGimbal 绝对/相对语义误用导致云台乱转 | 实现时先读 DjiFlightControlActionClient 现有用法/MSDK 文档确认并加注释与单测 |
| 激光在浓烟中全部 NO_SIGNAL/假回波 | ≥3 NORMAL + 散布/距离门限，不足即降级 standoff 坐标（T-C 已有），业务不中断 |
| 自动派发风暴 | 默认关 + 事件级冷却 + 状态过滤 |
| ZOOM 镜头绑定在实机上不出激光数据 | TC-laser-rf-findings 已标注待实机验证；封装在 LaserRangefinderClient 后可只改一处 |
