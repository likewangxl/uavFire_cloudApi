# 火点精确坐标（亚米级定位）— 设计文档

日期：2026-06-12
分支：`feature/fire-precision-and-realtime-detection`
状态：已与用户逐节确认（待评审修订）

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
- `rtkHAccM` / `rtkVAccM`：RTK 水平/垂直精度数值（MSDK `RTKLocation` std 系列字段，取不到置空）
- `frameTs` 与 `osdTs` 分离：识别帧时间戳与姿态采样时间戳（现仅一个 `sourceTs`；5m/s 飞行下 100ms 偏差 = 0.5m）

**后端观测积累：**
- 新表 `fc100_fire_observation`：`id`、`fire_event_id`、`snapshot_json`（原文）、单帧地面解（`lat`/`lng`/`alt`）、单帧误差 `sigma_m`、射线方向向量（`ray_e`/`ray_n`/`ray_u`）、飞机位置、`created_at`
- **去重路径改造**：`FireEventServiceImpl.create()` 同 eventId 去重时，从"直接返回旧事件"改为"返回旧事件 + 仍存观测"；不同 eventId 但地面解与已有火点距离 < 30m 的，按空间聚类归同一火点的观测集
- 每存一条观测触发重解算；坐标更新超过 0.5m 或精度档位变化时回写火点事件

## 第②节：自适应解算器（核心）

新服务 `FireGeoSolverService`（纯函数核心 + Spring 装配），输入某火点的观测集合：

**模式选择**：观测射线两两最大夹角 ≥ 15°（可配）→ 交会模式；否则 → 加权均值模式。

- **交会模式**：最小二乘求空间点 P 使 Σ‖(P−Oᵢ)×dᵢ‖² 最小（Oᵢ 飞机位置、dᵢ 单位射线方向；法方程 3×3 闭式解，无迭代）。**不依赖 DEM**。解算后丢弃到射线距离 > 3σ 的离群观测重解一次（防误识别污染）。
- **加权均值模式**：逐帧射线-DEM 求交（复用现有迭代逻辑），按 wᵢ = σᵢ⁻² 加权平均；随机项按有效样本数收敛 σ_avg = σ/√n_eff。

**误差模型重写**（替换 `estimateErrorRadiusMeters` 的 3m 硬底限，方差传播）：

```
σ² = σ_RTK²            // 有数值用数值；FIXED 无数值取 0.03m；FLOAT 0.5m；其他 3m
   + (slant × σ_att)²  // σ_att 默认 0.8°（弧度计），可配
   + (σ_DEM / tanθ)²   // θ=俯角；GLO-30 取 4m；交会模式此项为 0
   + (roiFootprint/2)² // ROI 中心不确定性
```

**输出**：`lat`/`lng`/`alt`、误差半径（协方差主轴）、解算模式、观测数、视角多样性（最大夹角）、`geoQuality`。`AUTO_WAYPOINT_READY ≤10m` 门槛逻辑不变；新增字段记录估计精度供前端展示。

**配置** `uavfire.fire-geo.*`：`cluster-radius-m=30`、`intersection-min-angle-deg=15`、`sigma-attitude-deg=0.8`、`sigma-dem-m=4.0`、`outlier-sigma=3.0`。

## 第③节：正下视巡检默认

前端监测航线规划：保存体构建处（`buildPagePlannedWaypointBody`）`gimbalPitch` 未显式设置时填 `-90`；任务参数面板（`MissionParamsPanel`）显示该默认值并可改。零后端改动。

## 第④节：全自动主动观测

**触发条件**（全部满足）：火点已有解算结果且视角多样性 < 15°；`uavfire.fire-observation.auto-execute=true`；监测机正在执行监测航线或悬停；无进行中的观测任务；该火点自动观测次数 = 0（每火点最多 1 次，防循环）。

**`ObservationRouteBuilder`**：圆心=火点估计，半径 R = max(60m, 3×当前误差半径)；取 2 个观测位（与首次观测方位夹角约 ±60°，保证交会角）；高度=当前巡检相对高度（不变高）；每观测位动作 = `gimbalRotate`（俯角 atan(高度/R) 指向火点）+ `hover 5s`；产出标准规划航线（`火点观测-{eventId}`），走现有 WPML 链路，**零新增飞控代码**。

**`FireObservationOrchestrator` 状态机**：

```
IDLE → PAUSING_PATROL → EXECUTING_OBSERVATION → WAITING_OBSERVATIONS → RESUMING_PATROL → DONE
                                    ↓ 任一阶段失败/超时
                                  FAILED（标记 OBSERVATION_NEEDED + 尽力恢复巡检）
```

- 阶段超时：暂停 15s / 观测执行 120s / 等观测 90s / 恢复 30s
- 复用现有 API：暂停（pause）、断点查询与续飞（query-breakpoint/recovery）、规划航线 生成→下发→prepare→execute
- 失败路径：放弃自动流程、火点标 `OBSERVATION_NEEDED`（人工补观测提示）、**始终尝试恢复巡检**，绝不留飞机无主悬空
- 状态变迁全量日志 + 事件流，前端可见

## 第⑤节：回放与代码级验收

- 观测中间量落盘 JSON：`data/fire-observations/{eventId}/obs-{n}.json`（输入快照、射线、单帧解、误差分项、模式、当前融合解），实飞标定可离线重放
- 合成几何测试（已知真值构造）：
  - 交会模式：3 条带高斯噪声射线 → 解算误差 < 注入噪声水平；离群射线被剔除
  - 近平行射线（夹角 < 15°）自动切加权均值
  - 方差传播逐项校验（RTK/姿态/DEM/ROI 各项单独置零对比）
  - 聚类归并：30m 内归簇、外开新簇
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
