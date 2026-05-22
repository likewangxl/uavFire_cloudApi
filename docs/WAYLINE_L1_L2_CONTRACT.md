# 航线 L1+L2 改造契约（数据模型 + KMZ + API + 状态机）

> 版本：v0.1 (2026-05-19) — 草案，开工时定稿
> 目标范围：把"司空式"航点定制 + 任务编排能力做到 uavfire，覆盖 M4T 真机（MSDK Agent 路径全验证）+ Dock 路径代码完工 deferred 真机
> 单一事实源：本文档定义所有 L1+L2 涉及的字段、XML 片段、API 形态、状态机。前后端/DB migration/单测全部以此为准
>
> 2026-05-21 状态校准：本文是 L1+L2 目标契约，不代表全部已实现。当前 MSDK 数据面迁移、Pilot 2 PoC 和 agent runtime 状态见 `docs/CURRENT_PROJECT_STATUS_2026-05-21.md`、`docs/MSDK_MIGRATION_PLAN.md`、`docs/poc/pilot2-composite-stream.md`。

---

## 0. 不在本文档范围内

- Mapping2D / Mapping3D / MappingStrip（L3 范围）
- 仿地飞行、DEM 高程、禁飞区（L4 范围）
- L1 之前已有功能（CRUD、publish、generate-file、KMZ namespace 1.0.6 修复等）

---

## 1. 双执行路径（核心路由规则）

后端按 `planned_wayline.dock_sn` 是否为空自动选路：

| `dock_sn` | 路径 | 命令通道 | 进度上报通道 | M4T 真机验证 |
|---|---|---|---|---|
| 非空 | **Dock 路径**（Cloud SDK） | `AbstractWaylineService.flighttask_prepare/execute/pause/recovery` MQTT | `flighttask_progress` MQTT 事件 | ❌ 代码完工等机场 |
| 空 | **Agent 路径**（rcplus-msdk-agent） | `WaylineAgentService` HTTP poll `wayline_dispatch/pause/resume/stop/query_breakpoint` | `WaylineAgentEventListener` MQTT inbound | ✅ 当前可验 |

**前端无感知**：UI 调同一组 endpoint（`/prepare /execute /pause /recovery /stop /resume-breakpoint`），后端按 `dock_sn` 内部路由。

---

## 2. L1：数据模型变更

### 2.1 `PlannedWaypointDTO` 新增字段（全部可空，向后兼容）

```java
private Double speed;                          // 覆盖全局自动飞行速度，null = 用 max_speed
private Double gimbalPitch;                    // 云台俯仰角度（绝对，° -90~30），null = 0
private Double gimbalYaw;                      // 云台偏航角度（绝对，° -180~180），null = 跟随机头
private String headingMode;                    // followWayline | smoothTransition | fixed | towardPOI，null = followWayline
private Double headingAngle;                   // headingMode=fixed 时使用（°），null = 0
private Double poiLng, poiLat, poiAlt;         // headingMode=towardPOI 时使用
private String turnMode;                       // 5 个值之一，null = toPointAndPassWithContinuityCurvature
private Double turnDamping;                    // 转弯阻尼距离（米），null = 10
private List<WaypointActionDTO> actions;       // 见 2.2
```

`turnMode` 枚举：`coordinateTurn` | `toPointAndStopWithDiscontinuityCurvature` | `toPointAndStopWithContinuityCurvature` | `toPointAndPassWithContinuityCurvature` | `toPointAndPassWithContinuityCurvatureAndCustomDamping`

### 2.2 新建 `WaypointActionDTO`

```java
private Integer actionId;                      // 在该航点的 actionGroup 内的局部 id，0 起
private String actionTrigger;                  // reachPoint | betweenAdjacentPoints | multipleTiming，默认 reachPoint
private Double actionTriggerParam;             // multipleTiming 时间间隔（秒）
private String actuatorFunc;                   // takePhoto | startRecord | stopRecord | gimbalRotate | hover | focus | rotateYaw
private Map<String, Object> params;            // 见下方表
```

| `actuatorFunc` | params 字段 | 说明 |
|---|---|---|
| `takePhoto` | `fileSuffix` (String, optional), `payloadPositionIndex` (Int, default 0) | 拍照 |
| `startRecord` | `fileSuffix`, `payloadPositionIndex` | 开始录像 |
| `stopRecord` | `payloadPositionIndex` | 结束录像 |
| `gimbalRotate` | `gimbalRotateMode` (absoluteAngle/relativeAngle), `gimbalPitchRotateEnable` (0/1), `gimbalPitchRotateAngle` (°), `gimbalYawRotateEnable` (0/1), `gimbalYawRotateAngle` (°), `gimbalRotateTimeEnable` (0/1), `gimbalRotateTime` (秒), `payloadPositionIndex` | 云台旋转 |
| `hover` | `hoverTime` (秒) | 悬停 |
| `focus` | `payloadPositionIndex`, `isPointFocus` (0/1), `focusX` `focusY` `focusRegionWidth` `focusRegionHeight` (归一化 0-1) | 对焦 |
| `rotateYaw` | `aircraftHeading` (°), `aircraftPathMode` (clockwise/counterClockwise) | 飞机偏航 |

### 2.3 `PlannedWaylineEntity` 新增字段（migration 已写）

**L1 mission 配置**：
- `finishAction` (varchar, default goHome)
- `exitOnRcLost` (varchar, default goContinue)
- `rcLostAction` (varchar, default goBack)
- `takeoffSecurityHeight` (int, default 20)
- `globalTransitionalSpeed` (double, default 5)
- `rthAltitude` (int, nullable) — 仅 Dock 路径使用

**L2 任务进度**（agent / dock 共用）：
- `waylineMissionState` (tinyint) — DJI `WaylineMissionStateEnum` 0-9
- `currentWaypointIndex` (int)
- `totalWaypoints` (int)
- `mediaCount` (int)
- `breakPointJson` (text) — 存储原始断点 JSON
- `lastProgressTime` (bigint, ms)

---

## 3. L1：KMZ 生成器输出契约

> 文件：`PlannedWaylineServiceImpl.java`
> 命名空间保持 1.0.6（不变）

### 3.1 全局字段从 entity 读取（替换 hardcode）

`writeMissionConfig` (现在 line 644-656)：

| WPML 字段 | 来源 |
|---|---|
| `<wpml:flyToWaylineMode>` | 常量 `safely`（暂不开放） |
| `<wpml:finishAction>` | `entity.finishAction` |
| `<wpml:exitOnRCLost>` | `entity.exitOnRcLost` |
| `<wpml:executeRCLostAction>` | `entity.rcLostAction` |
| `<wpml:takeOffSecurityHeight>` | `entity.takeoffSecurityHeight` |
| `<wpml:globalTransitionalSpeed>` | `entity.globalTransitionalSpeed` |
| `<wpml:waylineAvoidLimitAreaMode>` | 常量 `0`（暂不开放） |

`buildTemplateKml` 全局段（现在 line 596-609）：

| WPML 字段 | 来源 |
|---|---|
| `<wpml:autoFlightSpeed>` | `entity.maxSpeed`（替换常量 `AUTO_FLIGHT_SPEED_MPS`） |
| `<wpml:globalHeight>` | `entity.defaultHeight`（替换 `globalAvgHeight()`） |
| `<wpml:globalWaypointTurnMode>` | 常量 `toPointAndStopWithDiscontinuityCurvature`（暂不开放） |

### 3.2 Per-waypoint 字段从 DTO 读取

`writeTemplatePlacemark` (line 678) / `writeWaylinePlacemark` (line 697)：

| WPML 字段 | DTO 字段 | 缺省值 |
|---|---|---|
| `<wpml:waypointSpeed>` (仅 waylines.wpml) | `wp.speed` | `entity.maxSpeed` |
| `<wpml:useGlobalSpeed>` | 派生：`wp.speed == null ? 1 : 0` | — |
| `<wpml:waypointGimbalPitchAngle>` | `wp.gimbalPitch` | `0` |
| `<wpml:waypointGimbalYawAngle>` | `wp.gimbalYaw` | `0` |
| `<wpml:waypointHeadingMode>` | `wp.headingMode` | `followWayline` |
| `<wpml:waypointHeadingAngle>` | `wp.headingAngle` | `0` |
| `<wpml:waypointPoiPoint>` | `"{poiLng},{poiLat},{poiAlt}"` 当 mode=`towardPOI` | `0.000000,0.000000,0.000000` |
| `<wpml:waypointTurnMode>` | `wp.turnMode` | `toPointAndPassWithContinuityCurvature` |
| `<wpml:waypointTurnDampingDist>` | `wp.turnDamping` | `10` |
| `<wpml:useGlobalHeadingParam>` | 派生：所有 wp.heading 字段为 null → `1`，否则 `0` | — |
| `<wpml:useGlobalTurnParam>` | 派生：同上 | — |

### 3.3 Action group 输出格式（每航点一组，仅当 `wp.actions` 非空时）

放在 placemark 内部、`<wpml:isRisky>` 之前：

```xml
<wpml:actionGroup>
  <wpml:actionGroupId>{wayppointIndex}</wpml:actionGroupId>
  <wpml:actionGroupStartIndex>{wayppointIndex}</wpml:actionGroupStartIndex>
  <wpml:actionGroupEndIndex>{wayppointIndex}</wpml:actionGroupEndIndex>
  <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
  <wpml:actionTrigger>
    <wpml:actionTriggerType>{action.actionTrigger}</wpml:actionTriggerType>
    <!-- multipleTiming 时多一行 <wpml:actionTriggerParam>{秒}</wpml:actionTriggerParam> -->
  </wpml:actionTrigger>
  <!-- 同一航点多个 action 时,多个 <wpml:action> 顺序排列 -->
  <wpml:action>
    <wpml:actionId>{action.actionId}</wpml:actionId>
    <wpml:actionActuatorFunc>{action.actuatorFunc}</wpml:actionActuatorFunc>
    <wpml:actionActuatorFuncParam>
      <!-- 按 actuatorFunc 类型展开,见 2.2 表 -->
      <wpml:fileSuffix>...</wpml:fileSuffix>
      <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
    </wpml:actionActuatorFuncParam>
  </wpml:action>
</wpml:actionGroup>
```

**实例 — 在第 0 个航点拍照 + 第 1 个航点云台-30° 朝下**：

```xml
<!-- wp[0] placemark 内 -->
<wpml:actionGroup>
  <wpml:actionGroupId>0</wpml:actionGroupId>
  <wpml:actionGroupStartIndex>0</wpml:actionGroupStartIndex>
  <wpml:actionGroupEndIndex>0</wpml:actionGroupEndIndex>
  <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
  <wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
  <wpml:action>
    <wpml:actionId>0</wpml:actionId>
    <wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>
    <wpml:actionActuatorFuncParam>
      <wpml:fileSuffix>wp0</wpml:fileSuffix>
      <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
    </wpml:actionActuatorFuncParam>
  </wpml:action>
</wpml:actionGroup>
<!-- wp[1] placemark 内 -->
<wpml:actionGroup>
  <wpml:actionGroupId>1</wpml:actionGroupId>
  <wpml:actionGroupStartIndex>1</wpml:actionGroupStartIndex>
  <wpml:actionGroupEndIndex>1</wpml:actionGroupEndIndex>
  <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
  <wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
  <wpml:action>
    <wpml:actionId>0</wpml:actionId>
    <wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>
    <wpml:actionActuatorFuncParam>
      <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
      <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
      <wpml:gimbalPitchRotateAngle>-30</wpml:gimbalPitchRotateAngle>
      <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>
      <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>
      <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>
      <wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>
      <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
    </wpml:actionActuatorFuncParam>
  </wpml:action>
</wpml:actionGroup>
```

---

## 4. L2：新增 API endpoints

> `PlannedWaylineController.java` 现有 9 个 endpoint 之外新增

| Method | Path | 用途 | 路由按 dock_sn |
|---|---|---|---|
| POST | `/{wid}/planned-waylines/{id}/pause` | 暂停 | 是 |
| POST | `/{wid}/planned-waylines/{id}/recovery` | 恢复 | 是 |
| POST | `/{wid}/planned-waylines/{id}/stop` | 中止（区别于 cancel：cancel 是 prepare 后未起飞的取消，stop 是飞行中的中止） | 是 |
| POST | `/{wid}/planned-waylines/{id}/query-breakpoint` | 查询当前断点（同步返回 breakPoint JSON） | 是 |
| POST | `/{wid}/planned-waylines/{id}/resume-from-breakpoint` | 从断点续飞 | 是 |
| GET | `/{wid}/planned-waylines/{id}/progress` | 拉取最新进度（前端轮询用，等 WS 推送做好可砍） | — 只读 DB |

**`/prepare` 请求体扩展**（`PreparePlannedWaylineTaskParam`）：

```java
private String dockSn;                         // 已有
private String droneSn;                        // 已有
private Long executeTime;                      // 新增, dock 路径下 Unix ms，定时调度，null = 立即
private Long beginTime, endTime;               // 新增, dock 路径下时间窗
private Integer minBattery;                    // 新增, ReadyConditions.batteryCapacity
private Boolean simulate;                      // 新增, dock 路径下是否启用仿真
private Double simulateLat, simulateLng;       // 新增, simulate 时起飞坐标
```

agent 路径下 `executeTime/beginTime/endTime/simulate/minBattery` 全部忽略，仅做 best-effort（前端 UI 在 agent 路径下灰掉这些控件）。

---

## 5. L2：状态机

`planned_wayline.task_status` 取值统一定义：

```
draft               # 已建,未生成 KMZ
file_generated      # 已生成 KMZ
publishing          # 已 prepare,等飞机 ready
ready               # 飞机 ready,可执行
executing           # 执行中
paused              # 暂停（agent or dock）
broken              # 断线/中断（dock WAYLINE_BROKEN, agent error）
canceled            # prepare 后未执行就取消 / undo
stopped             # 飞行中 stop 中止
finished            # 完成
failed              # 失败（带 task_status_reason）
```

转移图：

```
draft → file_generated → publishing → ready → executing → finished
                              ↓        ↓          ↓ ↘ pause
                          canceled  canceled  paused → executing
                                                ↓ ↘ stop
                                              stopped/broken → resume_from_breakpoint → executing
```

---

## 6. L2：事件 → DB 持久化映射

### 6.1 Agent 路径（`WaylineAgentEventListener` 已订阅，新增持久化层）

| Agent 事件 | DTO | 持久化到 `planned_wayline` 字段 |
|---|---|---|
| `wayline_progress` | `WaylineProgressDTO` | `task_progress` ← `percent`、`current_waypoint_index`、`total_waypoints`、`last_progress_time` ← now |
| `wayline_state_change` | `WaylineStateChangeDTO` | `task_status` ← 业务态映射、`task_status_reason` ← `error` |
| `wayline_breakpoint` (新增) | 待定 | `break_point_json` ← 原始 JSON |

**`businessState` → `task_status` 映射**（在 service 层做）：

| `businessState` | task_status |
|---|---|
| `Dispatching` | `publishing` |
| `Ready` | `ready` |
| `Executing` | `executing` |
| `Paused` | `paused` |
| `Stopped` | `stopped` |
| `Completed` | `finished` |
| `Error` | `failed` |

### 6.2 Dock 路径（继承 `AbstractWaylineService`，新建 `CloudWaylineEventHandler`）

| Cloud SDK 事件 | DTO | 持久化字段 |
|---|---|---|
| `flighttask_progress` | `FlighttaskProgress` | `task_progress` ← `progress.percent`、`current_waypoint_index` ← `progress.ext.current_waypoint_index`、`media_count` ← `progress.ext.media_count`、`wayline_mission_state` ← `progress.ext.wayline_mission_state` |
| `flighttask_progress.ext.break_point` | `ProgressExtBreakPoint` | `break_point_json` ← serialise |
| `flighttask_ready` | `FlighttaskReady` | `task_status` ← `ready` |

按 `flight_id` 反查 `planned_wayline` 行更新（已有 `flight_id` 列）。

---

## 7. 前端契约

### 7.1 新增 / 修改文件

| 文件 | 变更 |
|---|---|
| `frontend/src/types/wayline.ts` | `PlannedWaypoint` 加 8 个可选字段 + `WaypointAction` 类型 |
| `frontend/src/api/wayline.ts` | 加 6 个新 endpoint 函数 |
| `frontend/src/hooks/use-wayline-planning.ts` | 航点编辑器内部状态扩展（speed/gimbal/heading/turn/actions） |
| `frontend/src/pages/page-web/projects/wayline.vue` | 航点列表项可展开,展开内编辑高级字段;全局 mission 配置面板 |
| `frontend/src/components/WaypointActionEditor.vue` | **新建** action 列表编辑（添加 / 删除 / 改 actuatorFunc 与 params） |
| `frontend/src/components/WaylineMissionMonitor.vue` | **新建** 进度面板（进度条 + 地图高亮 + 暂停/恢复/中止按钮） |

### 7.2 UI 路径分支

prepare 表单中：
- 若选择了 `dockSn` → 显示 "定时调度 / 仿真 / 最低电量" 等 Dock 专属字段
- 未选 `dockSn` → 隐藏这些字段，按钮文案 "派发到遥控器"

监控面板：
- agent 路径下："仿真" / "断点续飞" 按钮置灰（agent 不支持仿真，断点支持但 UI 暂时不开放）
- dock 路径下：全开放（但功能 deferred 真飞验证）

---

## 8. 实施分阶段（落到 commit 粒度）

| Phase | 内容 | 估时 | 真机验证 |
|---|---|---|---|
| **P0** | 本文档定稿 + migration SQL | 0.5d | — |
| **P1.a** | `PlannedWaypointDTO` + `WaypointActionDTO` + Entity 字段;`PlannedWaylineServiceImpl` KMZ 生成器参数化(去 hardcode + 读 DTO);单测 | 2d | — |
| **P1.b** | Action group XML 输出 + 5 种 actuatorFunc 单测全覆盖 | 2d | — |
| **P1.c** | 前端航点编辑 UI + ActionEditor 组件 + 全局 mission 配置面板 | 2d | — |
| **P1.real** | M4T 真飞:`takePhoto` + `gimbalRotate` + `hover` 各跑一遍 | 1d | ✅ |
| **P2.a** | 后端新增 6 个 endpoint(pause/recovery/stop/query-breakpoint/resume-from-breakpoint/progress);agent 路径分支接通;状态机重构 | 2d | — |
| **P2.b** | `WaylineAgentEventListener` 加持久化层(progress / state_change → DB) | 1d | — |
| **P2.c** | 前端 `WaylineMissionMonitor.vue`(进度 / 地图高亮 / 控制按钮);状态徽章 | 2d | — |
| **P2.real** | M4T 真飞:派发 → 飞行中 pause → 等 5s → recovery → stop → 重派 + resume-from-breakpoint | 1d | ✅ |
| **P3** | `prepareTask` 真发 dock MQTT(按 dockSn 路由);simulate / executeTime / readyConditions 装配;`CloudWaylineEventHandler` 订阅 dock 进度;单测覆盖 | 3-4d | ❌ deferred |
| **P4** | 文档收尾 + memory 更新 + AGENTS.md 整合 | 0.5d | — |

总计 **约 3 周**(2 周真飞验证 + 0.5 周 dock 代码 + 缓冲),其中 P1.real 和 P2.real 是真机验证关卡。

---

## 9. 风险登记

| 编号 | 风险 | 缓解 |
|---|---|---|
| R1 | M4T 固件对 WPML 1.0.6 action group 解析失败(单航点多 action / 多个 actionGroup) | P1.b 单测后立刻 P1.real 验证基础 actions;复杂场景渐进 |
| R2 | `WaylineAgentEventListener` 现有事件结构与文档 6.1 不完全一致(breakpoint 事件可能没接) | P2.b 开工前先确认 agent 端是否真上报 breakpoint;若没,跳过续飞前端按钮 |
| R3 | Dock 路径代码缺少机场只能 mock 测,真飞 deferred 后可能发现集成问题 | 单测 + 代码 review 兜底;真机回归列入"机场到位后第一周"事项 |
| R4 | DB migration 在生产数据上执行慢(planned_wayline 行数增长后) | 11 个 ALTER COLUMN 都用 `ADD COLUMN IF NOT EXISTS` 模式,且单列 ALTER 在 MySQL 8 上为 INSTANT,无锁等待 |
| R5 | 用户已存的老 wayline 行,新字段为 NULL,生成 KMZ 时空指针 | DTO 字段都 nullable,生成器对 null 用 fallback 默认值(见 3.1/3.2 各表) |

---

## 10. Open questions(P0 前必须拍板)

- [x] 进度持久化方案 — **复用 `planned_wayline` 表新字段,不另起 event store**(用户选 default)
- [x] 航点存储 — **保留 `waypoints_json` 大 JSON,DTO 加字段**(用户选 default)
- [x] 执行路径选择 — **后端按 `dock_sn` 自动路由**(用户选 default)
- [x] P3/P4 范围 — **做完代码 + 单测,真机回归 deferred**(用户选 default)

定稿状态:本节关闭,实施按上述决定执行。
