# 火点精确坐标（亚米级定位）— 设计文档

日期：2026-06-12
分支：`feature/fire-precision-and-realtime-detection`
状态：已与用户逐节确认；双视角评审（代码库对照 REVISE + 外部事实 7/7✅）修订 v2

修订记录 v2（2026-06-12）：
- §④ 自动观测仅在巡检以"已下发规划航线任务"（存在 flightId，后端可 pause/断点续飞）运行时启动；前端 DRC 点选巡检场景无法被后端暂停，降级为标记 OBSERVATION_NEEDED
- §① 观测存储挂接点改为同 eventId 去重分支 + 空间归并分支（FireEventServiceImpl 约 L344-378，agent 每次探测 eventId 均为新值，归并是主路径）；聚类半径与现有 MERGE_RADIUS_METERS=10m 对齐
- §② σ_DEM 默认 2.4m（GLO-30 LE90 4m ÷1.645）；交会解增加数值安全网（法矩阵条件数 κ>100 或 λ_min<1e-6·λ_max 时强制切加权均值）；RTK std 字段判空降级
- §② 加权均值模式前置重构：把 RayDemFireGeoLocationService.resolve() 内联的射线-DEM 迭代抽成可复用方法
- §③ 目标函数名以实现时核实（hook buildPlannedWaypointBody 或页面 buildPagePlannedWaypointBody）；MissionParamsPanel 需新增"云台俯仰默认"控件（现无此项）
- §④ 云台俯角为负值 -atan(高度/R)；断点续飞行为=先飞回断点再继续（超时余量已含）；投放门槛是 geoQuality==AUTO_WAYPOINT_READY 且 geoErrorRadiusM≤10 双条件，均不变

## 背景与目标

现有 `RayDemFireGeoLocationService` 单帧射线-DEM 求交曾因误差太大暂停使用。子项目1已落地真实 DEM 高程服务（GLO-30，`HgtTerrainElevationService`，瓦片待 `scripts/fetch-dem.sh` 下载）。本子项目目标：**冲亚米级火点定位**，路线 = 被动观测积累 + 自适应解算（交会/加权均值）+ 全自动主动补观测。

物理量级依据：单帧射线法在斜距 50-150m 下，云台姿态 ±1° ≈ ±1-2.6m，GLO-30 高程误差 ≈4m 经斜视角放大；正下视(-90°)时 DEM 误差的水平投影趋零、杠杆臂最短，80m 高度单帧 ≈0.7-1.4m；亚米级靠多帧收敛与多视角交会（交会不依赖 DEM）。

## 已确认的产品决策

| 决策点 | 结论 |
|---|---|
| 精度目标 | 亚米级（多视角交会路线） |
| 观测来源 | 被动积累为主（飞行中连续识别自然形成基线）+ 全自动主动补观测 |
| 巡检云台 | 监测航线新建航点默认 `gimbalPitch=-90`（正下视） |
| 主动观测触发 | 全自动执行（带配置总开关与安全护栏；用户知晓与 WAITING_REVIEW 审批文化的取向差异后选定） |
| 验收方式 | 本期代码级（合成几何用例），实飞标定后补；观测中间量落盘支持回放 |

## 第①节：观测数据链路

**agent 端（rcplus-msdk-agent，Kotlin）`GeoSnapshot` 补字段：**
- `rtkHAccM` / `rtkVAccM`：RTK 水平/垂直精度数值（MSDK 5.0.0+ `RTKLocation.getStdLatitude()/getStdLongitude()/getStdAltitude()`，返回 Double 可空，判空降级）
- `frameTs` 与 `osdTs` 分离：识别帧时间戳与姿态采样时间戳（现仅一个 `sourceTs`；5m/s 飞行下 100ms 偏差 = 0.5m）

**后端观测积累：**
- 新表 `fc100_fire_observation`：`id`、`fire_event_id`、`snapshot_json`（原文）、单帧地面解（`lat`/`lng`/`alt`）、单帧误差 `sigma_m`、射线方向向量（`ray_e`/`ray_n`/`ray_u`）、飞机位置、`created_at`
- **存储挂接点**（评审修订）：`FireEventServiceImpl.create()` 的同 eventId 去重分支与**空间归并分支**（约 L344-378，现有 `MERGE_RADIUS_METERS=10.0`，这是主路径——agent 每次探测的 eventId 都是新值）都改为"归并/返回旧事件 + 仍存观测"；聚类即复用该 10m 归并半径（配置 `cluster-radius-m` 默认 10）
- 每存一条观测触发重解算；坐标更新超过 0.5m 或精度档位变化时回写火点事件

## 第②节：自适应解算器（核心）

新服务 `FireGeoSolverService`（纯函数核心 + Spring 装配），输入某火点的观测集合：

**模式选择**：观测射线两两最大夹角 ≥ 15°（可配）→ 交会模式；否则 → 加权均值模式。交会解内置数值安全网：法矩阵 κ(A)>100 或 λ_min<1e-6·λ_max 时强制切加权均值（几何判据之外的防御）。

- **交会模式**：最小二乘求空间点 P 使 Σ‖(P−Oᵢ)×dᵢ‖² 最小（Oᵢ 飞机位置、dᵢ 单位射线方向；法方程 3×3 闭式解，无迭代）。**不依赖 DEM**。解算后丢弃到射线距离 > 3σ 的离群观测重解一次（防误识别污染）。
- **加权均值模式**：逐帧射线-DEM 求交，按 wᵢ = σᵢ⁻² 加权平均；随机项按有效样本数收敛 σ_avg = σ/√n_eff。**前置重构**：`RayDemFireGeoLocationService.resolve()` 内联的射线-DEM 迭代（约 L62-83）先抽成可复用方法供解算器调用。

**误差模型重写**（替换 `estimateErrorRadiusMeters` 的 3m 硬底限，方差传播）：

```
σ² = σ_RTK²            // 有数值用数值；FIXED 无数值取 0.03m；FLOAT 0.5m；其他 3m
   + (slant × σ_att)²  // σ_att 默认 0.8°（弧度计），可配
   + (σ_DEM / tanθ)²   // θ=俯角；GLO-30 默认 2.4m（LE90 4m÷1.645）；交会模式此项为 0
   + (roiFootprint/2)² // ROI 中心不确定性
```

**输出**：`lat`/`lng`/`alt`、误差半径（协方差主轴）、解算模式、观测数、视角多样性（最大夹角）、`geoQuality`。投放航点生成门槛（`geoQuality==AUTO_WAYPOINT_READY` **且** `geoErrorRadiusM≤10` 双条件，DeliveryController 约 L713）逻辑不变；新增字段记录估计精度/观测数/解算模式供前端展示（geoQuality 为自由字符串，新增 `OBSERVATION_NEEDED` 值无 switch 冲突）。

**配置** `uavfire.fire-geo.*`：`cluster-radius-m=10`、`intersection-min-angle-deg=15`、`sigma-attitude-deg=0.8`、`sigma-dem-m=2.4`、`outlier-sigma=3.0`。

## 第③节：正下视巡检默认

前端监测航线规划：保存体构建处（实现时核实——hook `buildPlannedWaypointBody` 或页面 `buildPagePlannedWaypointBody`，两处取实际生效者）`gimbalPitch` 未显式设置时填 `-90`；`MissionParamsPanel` **新增**"云台俯仰默认"控件（默认 -90，可改）。零后端改动。

## 第④节：全自动主动观测

**触发条件**（全部满足）：火点已有解算结果且视角多样性 < 15°；`uavfire.fire-observation.auto-execute=true`；**巡检以"已下发规划航线任务"运行（记录存在 flightId）**——前端 DRC 点选巡检无法被后端暂停（执行循环在浏览器 store，`PlannedWaylineServiceImpl` 的 pause 硬性要求 flightId），该场景直接标记 `OBSERVATION_NEEDED` 提示人工；无进行中的观测任务；该火点自动观测次数 = 0（每火点最多 1 次，防循环）。

**`ObservationRouteBuilder`**：圆心=火点估计，半径 R = max(60m, 3×当前误差半径)；取 2 个观测位（与首次观测方位夹角约 ±60°，保证交会角）；高度=当前巡检相对高度（不变高）；每观测位动作 = `gimbalRotate`（俯仰角 **-atan(高度/R)**，负值下俯，指向火点）+ `hover 5s`（M4T 规划航线动作体系已支持这两种动作）；产出标准规划航线（`火点观测-{eventId}`），走现有 WPML 链路，**零新增飞控代码**。

**`FireObservationOrchestrator` 状态机**：

```
IDLE → PAUSING_PATROL → EXECUTING_OBSERVATION → WAITING_OBSERVATIONS → RESUMING_PATROL → DONE
                                    ↓ 任一阶段失败/超时
                                  FAILED（标记 OBSERVATION_NEEDED + 尽力恢复巡检）
```

- 阶段超时：暂停 15s / 观测执行 120s / 等观测 90s / 恢复 60s（断点续飞=先飞回断点再继续执行，时序比直觉长，留足余量）
- 复用现有 API：暂停（pause）、断点查询与续飞（query-breakpoint/recovery）、规划航线 生成→下发→prepare→execute
- 失败路径：放弃自动流程、火点标 `OBSERVATION_NEEDED`（人工补观测提示）、**始终尝试恢复巡检**，绝不留飞机无主悬空
- 状态变迁全量日志 + 事件流，前端可见

## 第⑤节：回放与代码级验收

- 观测中间量落盘 JSON：`data/fire-observations/{eventId}/obs-{n}.json`（输入快照、射线、单帧解、误差分项、模式、当前融合解），实飞标定可离线重放
- 合成几何测试（已知真值构造）：
  - 交会模式：3 条带高斯噪声射线 → 解算误差 < 注入噪声水平；离群射线被剔除
  - 近平行射线（夹角 < 15°）自动切加权均值
  - 方差传播逐项校验（RTK/姿态/DEM/ROI 各项单独置零对比）
  - 聚类归并：10m 内归簇、外开新簇（与现有 MERGE_RADIUS_METERS 一致）
  - 状态机全路径：每阶段成功/失败/超时分支（外部 API 全 stub）

## 第⑥节：前端展示（轻量）

火点详情与事件列表补显示：观测数 n、估计精度 ±X m、解算模式（交会/均值）；自动观测进行中状态条。不动审批/任务流。

## 明确不做（本期范围外）

- 实飞精度标定与参数整定（实飞窗口后做，回放工具已备）
- 相机畸变模型与亚像素 ROI 定位（正下视+图像中心场景收益低）
- 人工引导补观测 UI 流程（自动失败时仅提示 `OBSERVATION_NEEDED`）
- FABDEM/高精 DEM 替换（交会模式已不依赖 DEM）
- 可见光（ai-service RTSP 链路）识别事件的观测接入——无 geo 快照，仅热成像旁路事件参与解算

## 依赖与风险

- DEM 瓦片需先 `scripts/fetch-dem.sh` 下载（加权均值模式依赖；交会模式不依赖）
- RTK std 字段依赖 MSDK API 可得性（取不到时误差模型退回档位常数，不阻塞）
- 全自动观测的实飞行为（暂停/续飞时序）有真机不确定性——状态机超时与降级路径覆盖，首次实飞建议开关先置 false 观察解算质量，再开自动
