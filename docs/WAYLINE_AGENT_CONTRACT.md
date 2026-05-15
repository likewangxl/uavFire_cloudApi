# 航线 Agent 通信契约（rcplus-msdk-agent ↔ uavfire backend）

> 版本:v0.1 (2026-05-15)
> 状态:草案,等 M4T 兼容性真机验证回报后定稿
> 适用范围:Matrice 4T(M4T)经 RC Plus 2 上的 `rcplus-msdk-agent` 执行 KMZ 航线任务

## 0. 一句话目标

后端把一份 KMZ 航线下发给 RC Plus 2 上的 `rcplus-msdk-agent`,由 agent 通过 DJI MSDK v5 的 `IWaypointMissionManager` 在 M4T 上执行;过程状态、进度、动作回报回后端。**不依赖 DJI Dock,不依赖 DJI 官方 Cloud API 任务下发协议**。

## 1. 整体架构

```
+--------------+   HTTP poll      +-----------------+    MSDK v5    +-----+
|              | <--------------- |                 | <-----------> | M4T |
|  uavfire     |  /command (long) | rcplus-msdk-    |  pushKMZ      +-----+
|  backend     |                  |   agent         |  startMission
|  (6789)      |  HTTP POST       | (Android, RC    |  pauseMission
|              | ---------------> |  Plus 2)        |  ...
|              |  /command/ack    |                 |
|              |                  |                 |
|              |  MQTT events ↑   |                 |
|              | <--------------- |                 |
+--------------+                  +-----------------+
       │
       └── KMZ 文件:agent 直接拿 dispatch payload 里的 kmzUrl
           走 HTTP GET 下载(已有 WaylineFileServiceImpl.getObjectUrl()
           返回的 OSS / local URL)
```

**控制面**用 HTTP(命令下发 + ack 走 poll 通道,复用现有 dual-stream 模式);**事件面**用 MQTT(状态变更、航线进度、航点动作触发,因频率高且需要推送)。**为什么不全 MQTT** — agent 当前没有 MQTT client,引入要新增 Paho 依赖 + JWT;短期 HTTP poll 已经够用,真要换 MQTT 也容易;长期分两层各取所长。

## 2. 配置默认值(已与业务对齐,2026-05-15)

KMZ 内 `wpml:missionConfig` 字段默认:

| 字段 | 默认 | 备注 |
|---|---|---|
| `wpml:finishAction` | `goHome` | 完成后返航 |
| `wpml:exitOnRCLost` | `goContinue` | 失联也飞完,不打断业务;飞完再执行 finishAction |
| `wpml:executeRCLostAction` | `goBack` | 占位(goContinue 时不触发,留备业务调整) |
| `wpml:takeOffSecurityHeight` | `20` (m) | DJI 规范要求范围[1.2, 1500] |
| `wpml:globalRTHHeight` | `50` (m) | 返航爬升高度 |
| `wpml:globalTransitionalSpeed` | `5` (m/s) | DJI 规范要求范围[1, 15] |
| `wpml:autoFlightSpeed` | `5` (m/s) | 同上 |
| `wpml:coordinateMode` | `WGS84` | |
| `wpml:heightMode`(template) | `relativeToStartPoint`(测试样本)/ `EGM96`(生产 KMZ) | 真机生产 KMZ 一律用 EGM96 |
| `wpml:executeHeightMode`(waylines) | `WGS84` | DJI 规定 |
| `wpml:droneEnumValue` | **暂用 67(M30T)** | 等 M4T 真机兼容性验证回报后定 |
| `wpml:droneSubEnumValue` | **暂用 1(M30T 三光)** | 同上 |
| `wpml:payloadEnumValue` | **暂用 53(M30T 三光相机)** | 同上 |
| `wpml:imageFormat` | `zoom,ir` | M30T 默认;M4T 实际相机类型待定 |

> ⚠ 三个 `*EnumValue` 是这次 M4T 兼容性测试的核心未知量,见第 9 节。

## 3. HTTP 接口

URL 前缀:`/wayline-agent/api/v1`(由 `application.yml` 中 `url.wayline-agent.prefix` 控制)。

### 3.1 端点表

| 方法 | 路径 | 调用方 | 用途 | 当前状态 |
|---|---|---|---|---|
| GET | `/agents/{drone_sn}/command` | agent | 拉取下一条命令(单槽,无命令返回 null) | ✅ 骨架 |
| POST | `/agents/{drone_sn}/command/ack` | agent | 命令收到回执(立即同步) | ✅ 骨架 |
| POST | `/agents/{drone_sn}/dispatch` | backend 内部 / 前端 | 入队 wayline_dispatch | ✅ 骨架 |
| POST | `/agents/{drone_sn}/pause` | backend 内部 | 入队 wayline_pause | ✅ 骨架 |
| POST | `/agents/{drone_sn}/resume` | backend 内部 | 入队 wayline_resume | ✅ 骨架 |
| POST | `/agents/{drone_sn}/stop` | backend 内部 | 入队 wayline_stop | ✅ 骨架 |
| POST | `/agents/{drone_sn}/query-breakpoint` | backend 内部 | 入队断点查询 | ✅ 骨架 |

骨架代码位置:`backend/uavfire/src/main/java/com/yx/uavfire/wayline/agent/`。
内存队列单槽:同一 `droneSn` 只保留最近一条未 ack 命令,重复 dispatch 会覆盖。**生产前需替换为持久化队列 + 幂等**。

### 3.2 信封(所有命令)

```json
{
  "tid": "uuid",
  "bid": "mission-uuid(或其他业务关联 id)",
  "timestamp": 1731686400000,
  "method": "wayline_dispatch | wayline_pause | wayline_resume | wayline_stop | wayline_query_breakpoint",
  "data": { ... }
}
```

DTO 类:`WaylineAgentCommandDTO`、`WaylineAgentCommandAckDTO`。

### 3.3 `wayline_dispatch` 的 `data`

```json
{
  "missionId":   "uuid-mission-id",
  "kmzUrl":      "http://backend/wayline/api/v1/.../url?token=...",
  "kmzFilename": "fire_mission_42.kmz",
  "kmzMd5":      "0123456789abcdef...",
  "waylineIds":  [0],
  "rthAltitude": 50
}
```

- `kmzUrl` 由后端调 `WaylineFileServiceImpl.getObjectUrl()` 得到,可能是 OSS 预签名 URL 或 local file URL
- `kmzMd5`:agent 下载后校验,不匹配立即用 `result != 0` ack 拒绝
- `waylineIds`:KMZ 内多条航线时用,空数组或缺省 = 全跑
- `rthAltitude`:可选,覆盖 KMZ 内 `globalRTHHeight`

DTO 类:`WaylineDispatchDataDTO`。

### 3.4 `wayline_pause` / `resume` / `stop` / `query_breakpoint` 的 `data`

```json
{ "missionId": "uuid-mission-id" }
```

DTO 类:`WaylineControlDataDTO`。

### 3.5 ack 格式

```json
{
  "tid":   "echo-the-command-tid",
  "result": 0,
  "output": "optional-message-or-error-detail"
}
```

`result`:0 = 接受;非 0 = 拒绝(原因见 `output`)。agent 在**收到命令瞬间立即 ack**,不等 MSDK 实际执行完——执行结果通过 MQTT `wayline_dispatch_result` 事件单独发回。

## 4. MQTT 主题(上行事件,设计中,尚未实现)

### 4.1 命名约定

- 命名空间:`uavfire/`(不占用 DJI `thing/product/` 命名)
- 主题模式:`uavfire/agent/{droneSn}/{kind}/{method}`
- broker:复用 `192.168.50.10:1883`(`application.yml` 中 mqtt 配置)

### 4.2 主题表

| 方向 | Topic | Method | QoS | 频率 |
|---|---|---|---|---|
| agent→backend | `uavfire/agent/{sn}/events/wayline_state_change` | wayline_state_change | 1 | 状态切换瞬间 |
| agent→backend | `uavfire/agent/{sn}/events/wayline_progress` | wayline_progress | 1 | 1-3s |
| agent→backend | `uavfire/agent/{sn}/events/wayline_action` | wayline_action | 1 | 航点动作触发 |
| agent→backend | `uavfire/agent/{sn}/events/wayline_dispatch_result` | wayline_dispatch_result | 1 | dispatch 命令实际执行后(成功 / KMZ 上传失败 / 机型不支持等) |
| agent→backend | `uavfire/agent/{sn}/state/osd` | osd | 0 | 1Hz(可选,通用 OSD) |

### 4.3 `wayline_state_change`

```json
{
  "tid":    "...",
  "method": "wayline_state_change",
  "timestamp": 1731686400000,
  "data": {
    "missionId": "...",
    "msdkState": "EXECUTING",
    "businessState": "EXECUTING",
    "previousMsdkState": "ENTER_WAYLINE",
    "error": null
  }
}
```

### 4.4 `wayline_progress`

```json
{
  "tid":    "...",
  "method": "wayline_progress",
  "timestamp": ...,
  "data": {
    "missionId": "...",
    "missionFileName": "fire_mission_42.kmz",
    "waylineId": 0,
    "currentWaypointIndex": 5,
    "totalWaypoints": 12,
    "percent": 41,
    "aircraft": {
      "lat": 22.50000, "lng": 113.90000,
      "altEllipsoid": 145.2, "altRelative": 30.0,
      "speed": 5.2, "yaw": 90.0
    },
    "batteryPercent": 73,
    "rcSignalDbm": -65,
    "rtkStatus": "FIX"
  }
}
```

字段映射:
- `missionFileName / waylineId / currentWaypointIndex` ← MSDK `WaylineExecutingInfo`
- `aircraft.*` ← MSDK `FlightControllerKey.KeyAircraftLocation3D` 等
- `totalWaypoints` ← agent 启动前解析 KMZ 得到(MSDK `IWPMZManager`)

### 4.5 `wayline_action`(MSDK 5.6+)

```json
{
  "tid":    "...",
  "method": "wayline_action",
  "data": {
    "missionId": "...",
    "waylineId": 0,
    "waypointIndex": 5,
    "actionId": 1,
    "actionType": "takePhoto",
    "phase": "START",
    "success": true,
    "error": null
  }
}
```

### 4.6 `wayline_dispatch_result`

```json
{
  "tid":    "<echo-dispatch-tid>",
  "method": "wayline_dispatch_result",
  "data": {
    "missionId": "...",
    "result": 0,
    "msdkErrorCode": null,
    "msdkErrorMsg":  null,
    "msdkMissionFileName": "fire_mission_42"
  }
}
```

## 5. 状态机

### 5.1 MSDK `WaypointMissionExecuteState`(10 态)

```
IDLE / NOT_SUPPORTED / READY / UPLOADING / PREPARING
ENTER_WAYLINE / EXECUTING / INTERRUPTED / RECOVERING / FINISHED
```

出处:`IWaypointMissionManager.html`(MSDK v5 5.0+)。

### 5.2 业务态(对外暴露)

```
[INIT]
   │ dispatch (HTTP)
   ▼
[PENDING] ── ack(result=0) ──> [UPLOADING] ──┬─ error ──> [FAILED]
                                              └─ done  ──> [READY]
                                                              │ start (隐含 in dispatch)
                                                              ▼
                                                        [TAKING_OFF]
                                                              │
                                              ┌────── pause ──┴──> [PAUSED] ──resume──┐
                                              │                                       │
                                              ▼                                       │
                                          [EXECUTING] <─────────────────────────────  ┘
                                              │
                                              ├── stop ──> [STOPPED]
                                              ├── error ──> [FAILED]
                                              └── done ──> [FINISHED]
```

终态:`FINISHED` / `FAILED` / `STOPPED`。

### 5.3 MSDK → 业务态映射(agent 内执行)

| MSDK | 业务 | 备注 |
|---|---|---|
| IDLE | INIT | |
| UPLOADING | UPLOADING | |
| READY | READY | KMZ 已上飞机 |
| PREPARING | TAKING_OFF | startMission 后,实际起飞 |
| ENTER_WAYLINE | EXECUTING | 飞往首航点 |
| EXECUTING | EXECUTING | 航线段内 |
| INTERRUPTED | **PAUSED 或 FAILED** | 3s 窗口内有用户 `pauseMission` 调用 → PAUSED;否则 FAILED |
| RECOVERING | EXECUTING | resume 后过渡 |
| FINISHED | **FINISHED 或 STOPPED** | 看是不是 `stopMission` 触发 |
| NOT_SUPPORTED | FAILED | 应在 dispatch 前用 `getAvailableWaylineIDs` 探测并拦截 |

## 6. 错误码命名

骨架阶段沿用 DJI `CloudSDKErrorEnum`(在 cloud-sdk 中)。等真要落业务码再起独立枚举 `WaylineAgentErrorEnum`。

约定:
- `0` = 成功
- `1xxxx` = HTTP 协议错(请求格式 / auth)
- `2xxxx` = agent 本地错(MSDK 未就绪 / KMZ 下载失败 / md5 不匹配)
- `3xxxx` = MSDK 执行错(透传 MSDK errorCode,加偏移)
- `4xxxx` = 业务态机错(已 EXECUTING 又收到 dispatch 等)

## 7. 安全 & 合规

1. RC Plus 2 必须**视距内有人持机**,不做无人值守
2. dispatch 不会**自动起飞**——agent 接到 dispatch 只做 `pushKMZFileToAircraft`,起飞需要用户在 agent UI 上点确认。`autoTakeoff` 字段当前忽略
3. `wpml:exitOnRCLost = goContinue` 是业务选定的失联策略:**失联也飞完航线**;到达 `finishAction=goHome` 后返航。若途中电量低于 `wpml:rcLostBatteryRTHThreshold`(若设置)飞机自己返航
4. agent 上线前需:DJI 实名登记、SDK key 校验、用户登录态(MSDK `IUserAccountManager`)
5. JWT auth(`WaylineAgentAuthFilter`)**尚未实现**,所有端点目前裸跑(仅依赖网络隔离)

## 8. 工作量与里程碑

| # | 模块 | 端 | 估时 | 状态 |
|---|---|---|---|---|
| 1 | KMZ 生成器对齐 WPML 规范(`PlannedWaylineServiceImpl.buildPublishedKmz`) | backend | 2-3 天 | 未开始 |
| 2 | HTTP 端点骨架 | backend | 1 天 | **✅ 已交付**(2026-05-15) |
| 3 | M30T 测试 KMZ 样本(M4T 兼容性探针) | - | 0.5 天 | **✅ 已交付**(2026-05-15) |
| 4 | M4T 真机兼容性测试(`droneEnumValue=67` 接不接) | 你 | 1 天 | **⏳ 等你回报** |
| 5 | MQTT 订阅 + 事件解码 + 状态机持久化 | backend | 2 天 | 未开始 |
| 6 | JWT 签发 + auth filter | backend | 1 天 | 未开始 |
| 7 | agent:Paho MQTT 接入 + 重连 + QoS | agent | 2 天 | 未开始 |
| 8 | agent:`WaypointMissionExecutor`(包 MSDK API + 状态映射) | agent | 3-4 天 | 未开始 |
| 9 | agent:对接现有 `CommandPollingCoordinator` | agent | 1 天 | 未开始 |
| 10 | 真机联调 | both | 5-7 天 | 未开始 |

## 9. 关键未决事项

1. **M4T 的 `droneEnumValue` 真实值**——DJI WPML v1.11.3 不列 M4 系列。本契约暂用 67(M30T)。等用户执行 `samples/wayline/m30t_min_v1.kmz` 在 M4T 上的兼容性探针,反馈结果后定稿。
2. **M4T 相机的 `payloadEnumValue`**——同上,暂用 53(M30T 三光相机)
3. **完成后悬停的真实业务诉求**——业务最终选 `finishAction=goHome`,但若后续需要"完成后悬停等指令",WPML 上只能用 `noAction`(进入手动模式),那时再改
4. **多任务并发**——MSDK 限制单机同一时刻 1 个任务。前端 / 业务层是否需要排队?当前 backend 单槽内存队列直接覆盖,**不安全**,生产前必改
5. **JWT auth**——目前端点裸奔,只靠网络隔离;上线前必须加
6. **MQTT broker auth**——agent 用什么用户名 / 密码 / clientId?和现有 DJI cloud-sdk DRC 通道是否共用?

## 10. 参考资料

- DJI Cloud-API-Doc v1.11.3 (2024-11-07): `60.api-reference/00.dji-wpml/`(`template-kml.md`、`waylines-wpml.md`、`common-element.md`)
- DJI Mobile-SDK-Android-V5 (最新):`Docs/Android_API/cn/Components/IWaypointMissionManager/*.html`
- DJI Mobile-SDK-Android-V5 README:支持机型清单含"Matrice 4 行业系列"
- 本项目代码:
  - `backend/uavfire/src/main/java/com/yx/uavfire/wayline/agent/`(HTTP 骨架)
  - `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java`(现有 KMZ 生成器,需重构)
  - `backend/uavfire/src/main/java/com/yx/uavfire/fc100/route/builder/Fc100WpmlBuilder.java`(火情任务 KMZ 生成器,字段更全,可参考)
  - `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/device/DeviceEnum.java`(`M4T = 0-99-1`,自定义占位)
  - `samples/wayline/m30t_min_v1.kmz`(M4T 兼容性探针 KMZ)
  - `rcplus-msdk-agent/`(MSDK Android 工程,目前无 wayline 模块)

## 变更记录

- 2026-05-15 v0.1 草稿。基于审计 + 用户业务决策(finishAction=goHome, 失联 goContinue, 直接 M4T 测试)。HTTP 骨架已 commit。
