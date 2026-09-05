# 无人机火情智巡微信小程序——API 接口文档

## 1. 文档信息

| 项目 | 值 |
| --- | --- |
| API 版本 | v1 |
| Base Path | `/miniapp/api/v1` |
| 协议 | HTTPS + JSON；实时事件使用 WSS |
| 字符集 | UTF-8 |
| 时间 | UTC ISO-8601，精确到毫秒 |
| 坐标 | 事实坐标 WGS84，地图坐标 GCJ-02 |
| 认证 | `Authorization: Bearer <access-token>` |
| 机器契约 | [openapi.yaml](openapi.yaml) |

本文定义目标接口。现有 `/manage/api/v1`、`/wayline/api/v1` 和 `/api/fire`
由 BFF 内部适配，小程序不得依赖其内部 DTO。

## 2. 通用约定

### 2.1 请求头

| 请求头 | 必填 | 说明 |
| --- | --- | --- |
| `Authorization` | 除公开接口外 | Bearer access token |
| `X-Request-Id` | 否 | 客户端生成 UUID；缺省由服务端生成 |
| `Idempotency-Key` | 创建/命令类接口 | UUID，建议每次业务动作生成一次 |
| `X-Client-Version` | 是 | 小程序版本，例如 `1.0.0` |
| `X-Device-Id` | 是 | 小程序生成的随机设备安装 ID，不使用硬件唯一标识 |
| `If-Match` | 更新状态时 | 资源版本，例如 `"18"` |

### 2.2 成功响应

```json
{
  "requestId": "1a2abf43-0aae-4af7-9af4-72cf972e6c45",
  "code": "OK",
  "message": "success",
  "data": {},
  "serverTime": "2026-09-03T07:30:00.123Z"
}
```

异步操作返回 `HTTP 202`，`data.status` 通常为 `QUEUED` 或 `PENDING`；这不代表设备执行成功。

### 2.3 错误响应

```json
{
  "requestId": "1a2abf43-0aae-4af7-9af4-72cf972e6c45",
  "code": "TASK_VERSION_CONFLICT",
  "message": "任务状态已变化，请刷新后重试",
  "details": {
    "expectedVersion": 17,
    "actualVersion": 18
  },
  "serverTime": "2026-09-03T07:30:00.123Z"
}
```

客户端只根据 `code` 判断业务分支，`message` 可调整且不作为程序条件。

### 2.4 游标分页

请求：

```text
?limit=20&cursor=eyJjcmVhdGVkQXQiOi...
```

响应：

```json
{
  "items": [],
  "nextCursor": "eyJjcmVhdGVkQXQiOi...",
  "hasMore": true
}
```

- `limit` 默认 20，最大 100。
- cursor 为不透明字符串，客户端不得解析。
- 排序字段相同时以稳定 ID 作为第二排序键。

### 2.5 字段规则

- 对外业务 ID 使用字符串，不暴露数据库自增 ID。
- 金额、长度、面积等必须有单位后缀，例如 `distanceM`、`areaM2`。
- 百分比使用 `0–100` 数值；比率使用 `0–1` 时字段名以 `Ratio` 结尾。
- 未知值返回 `null`，不使用 `-1`、空字符串或 `(0,0)` 冒充数据。
- 枚举未知值客户端按 `UNKNOWN` 展示，不崩溃。

### 2.6 幂等

以下接口必须携带 `Idempotency-Key`：

- 账号绑定、审批决定、问题创建和状态动作。
- 指令提交、报告生成、报告签阅和分享创建。
- 通知订阅登记。

同一用户、workspace、接口和幂等键：

- 请求体哈希一致：返回首次响应。
- 请求体哈希不一致：`409 IDEMPOTENCY_KEY_REUSED`。
- 默认保存 24 小时；飞行指令审计永久保留业务键。

### 2.7 乐观锁

可变资源响应包含 `version`。更新时使用：

```http
If-Match: "18"
```

版本不一致返回 `409 RESOURCE_VERSION_CONFLICT` 或领域专用冲突码。

## 3. 权限与接口映射

| 权限 | 接口范围 |
| --- | --- |
| `dashboard:read` | 首页、待办、态势摘要 |
| `task:read` | 航线和任务查询 |
| `task:approve` | 审批任务 |
| `task:execute/pause/resume/stop/return-home` | 对应飞行指令 |
| `event:read/review` | 火情与 AI 异常查询/复核 |
| `incident:assign` | 创建/分派处置流程 |
| `issue:create/handle/recheck` | 问题督办闭环 |
| `report:read/generate/sign/share` | 报告能力 |
| `device:read` | 设备状态和直播 |
| `audit:read` | 审计查询 |
| `admin:manage` | 绑定审核、模板和灰度配置 |

## 4. 接口总览

### 4.1 认证与用户

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/auth/wechat/login` | 微信 code 登录 | 否 |
| POST | `/auth/bindings` | 绑定现有系统账号 | Binding Ticket |
| POST | `/auth/refresh` | 轮换刷新令牌 | Refresh Token |
| POST | `/auth/logout` | 注销当前会话 | 是 |
| GET | `/me` | 当前用户、组织、权限 | 是 |
| GET | `/me/workspaces` | 可访问工作空间 | 是 |
| POST | `/me/workspace-switches` | 切换工作空间并换发令牌 | 是 |
| GET | `/me/sessions` | 登录设备列表 | 是 |
| DELETE | `/me/sessions/{sessionId}` | 撤销指定会话 | 是 |

### 4.2 首页与待办

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/dashboard/summary` | 首页聚合摘要 |
| GET | `/dashboard/situation` | 地图态势图层 |
| GET | `/todos` | 当前用户待办 |
| POST | `/todos/{todoId}/read` | 标记已读 |

### 4.3 航线、任务与审批

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/waylines` | 已发布/可用航线列表 |
| GET | `/waylines/{waylineId}` | 航线详情与地图几何 |
| GET | `/inspection-tasks` | 巡检任务列表 |
| GET | `/inspection-tasks/{taskId}` | 任务详情 |
| GET | `/inspection-tasks/{taskId}/timeline` | 任务时间线 |
| GET | `/inspection-tasks/{taskId}/track` | 实际航迹 |
| GET | `/inspection-tasks/{taskId}/media` | 任务媒体 |
| POST | `/inspection-tasks/{taskId}/approval-submissions` | 提交审批 |
| GET | `/approvals` | 审批列表 |
| GET | `/approvals/{approvalId}` | 审批详情 |
| POST | `/approvals/{approvalId}/decisions` | 批准/驳回 |

### 4.4 飞行指令

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/flight-command-previews` | 权限、状态与风险预检 |
| POST | `/flight-commands` | 二次确认后提交指令 |
| GET | `/flight-commands/{commandId}` | 查询指令生命周期 |
| GET | `/inspection-tasks/{taskId}/flight-commands` | 任务指令历史 |
| POST | `/flight-commands/{commandId}/cancel-requests` | 对尚未派发的指令请求取消 |

### 4.5 火情与异常

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/events` | 火情/AI异常列表 |
| GET | `/events/{eventId}` | 事件详情、证据和坐标 |
| GET | `/events/{eventId}/timeline` | 事件时间线 |
| POST | `/events/{eventId}/reviews` | 确认、疑似或误报复核 |
| POST | `/events/{eventId}/incident-links` | 创建或关联处置事件 |
| POST | `/events/{eventId}/issue-links` | 创建或关联督办问题 |
| GET | `/incidents/{incidentId}` | 处置事件详情 |
| POST | `/incidents/{incidentId}/assignments` | 分派资源/责任人 |
| POST | `/incidents/{incidentId}/comments` | 批示与反馈 |

### 4.6 问题督办

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/issues` | 问题列表 |
| POST | `/issues` | 创建问题 |
| GET | `/issues/{issueId}` | 问题详情 |
| POST | `/issues/{issueId}/assignments` | 分派/转派 |
| POST | `/issues/{issueId}/comments` | 反馈或批示 |
| POST | `/issues/{issueId}/attachments` | 获取上传凭证/登记附件 |
| POST | `/issues/{issueId}/attachments/{uploadId}/complete` | 完成附件校验与登记 |
| POST | `/issues/{issueId}/completion-submissions` | 提交处理完成 |
| POST | `/issues/{issueId}/recheck-decisions` | 复检通过/重新打开 |
| POST | `/issues/{issueId}/reminders` | 催办 |

### 4.7 报告

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/reports` | 报告列表 |
| POST | `/reports` | 手工触发报告生成/重生成 |
| GET | `/reports/{reportId}` | 报告结构化详情 |
| GET | `/reports/{reportId}/versions` | 历史版本 |
| GET | `/reports/{reportId}/download-ticket` | PDF 短时下载凭证 |
| POST | `/reports/{reportId}/signatures` | 签阅报告 |
| POST | `/reports/{reportId}/shares` | 创建短时分享 |
| DELETE | `/reports/{reportId}/shares/{shareId}` | 撤销分享 |

### 4.8 设备、直播与实时事件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/devices` | 设备列表和战备摘要 |
| GET | `/devices/{deviceSn}` | 设备详情、载荷、HMS、遥测 |
| GET | `/devices/{deviceSn}/timeline` | 设备关键事件 |
| POST | `/live-sessions` | 创建短时播放会话 |
| GET | `/live-sessions/{liveSessionId}` | 播放状态 |
| DELETE | `/live-sessions/{liveSessionId}` | 结束播放会话 |
| POST | `/realtime-tickets` | 创建一次性 WSS ticket |
| GET | `/events/since` | 断线后按游标补拉关键事件 |

### 4.9 通知与设置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/notification-preferences` | 通知偏好和模板状态 |
| PUT | `/notification-preferences` | 更新站内/备用通道偏好 |
| POST | `/notification-subscriptions` | 登记微信授权结果 |
| GET | `/notifications` | 站内消息列表 |
| POST | `/notifications/{notificationId}/read` | 标记已读 |

### 4.10 审计与管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/audit-logs` | 按资源/用户/动作检索审计 |
| GET | `/audit-logs/{auditId}` | 审计详情 |
| GET | `/admin/user-bindings` | 绑定审核列表 |
| POST | `/admin/user-bindings/{bindingId}/decisions` | 批准/拒绝绑定 |
| GET | `/admin/feature-flags` | 灰度能力 |
| PUT | `/admin/feature-flags/{flagKey}` | 更新灰度开关 |

### 4.11 服务端回调

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/callbacks/wechat/subscribe-message` | 微信消息发送结果事件 | 微信签名校验 |
| POST | `/callbacks/media/playback` | 媒体网关播放状态 | mTLS/HMAC |

## 5. 核心接口详情

### 5.1 微信登录

`POST /auth/wechat/login`

请求：

```json
{
  "code": "wx-login-temporary-code",
  "deviceId": "install_01J8R9...",
  "clientVersion": "1.0.0"
}
```

已绑定响应：

```json
{
  "requestId": "req-001",
  "code": "OK",
  "message": "success",
  "data": {
    "loginStatus": "AUTHENTICATED",
    "accessToken": "eyJ...",
    "accessTokenExpiresIn": 900,
    "refreshToken": "rt_...",
    "refreshTokenExpiresIn": 604800,
    "user": {
      "userId": "usr-001",
      "displayName": "张三",
      "roles": ["LEADER"]
    },
    "workspace": {
      "workspaceId": "ws-001",
      "name": "森林防火指挥中心"
    }
  },
  "serverTime": "2026-09-03T07:30:00.123Z"
}
```

未绑定响应：

```json
{
  "requestId": "req-001",
  "code": "OK",
  "message": "binding required",
  "data": {
    "loginStatus": "BINDING_REQUIRED",
    "bindingTicket": "bt_...",
    "expiresIn": 600,
    "bindingMethods": ["ACCOUNT_PASSWORD", "SMS_CODE", "ADMIN_APPROVAL"]
  },
  "serverTime": "2026-09-03T07:30:00.123Z"
}
```

安全要求：code 只能使用一次；服务端不返回 OpenID、UnionID 或 session_key。

### 5.2 账号绑定

`POST /auth/bindings`

请求头：

```http
Authorization: Binding bt_...
Idempotency-Key: 4ba5ad1b-d70e-4bd0-89c3-889d97a966e1
```

请求：

```json
{
  "method": "ACCOUNT_PASSWORD",
  "workspaceCode": "FOREST-001",
  "username": "leader01",
  "password": "<仅在 TLS 请求体中传输>"
}
```

响应可能为：

- `AUTHENTICATED`：直接返回登录令牌。
- `PENDING_APPROVAL`：返回 `bindingId`，等待管理员审核。
- `BINDING_REJECTED`：账号状态或组织策略不允许绑定。

密码只传给现有认证服务验证，不写入 miniapp 表、日志或异步事件。

### 5.3 刷新令牌

`POST /auth/refresh`

```json
{
  "refreshToken": "rt_...",
  "deviceId": "install_01J8R9..."
}
```

成功后旧 refresh token 立即失效并返回新 token。检测到旧 token 再次使用时返回
`AUTH_REFRESH_TOKEN_REPLAYED`，撤销同 token family 的全部会话。

### 5.4 首页摘要

`GET /dashboard/summary`

响应 `data`：

```json
{
  "conclusion": {
    "level": "ATTENTION",
    "title": "3 项需要关注",
    "description": "1 个高等级火情，2 个任务异常"
  },
  "metrics": {
    "tasks": {"planned": 8, "executing": 2, "completed": 5, "failed": 1},
    "fireEvents": {"active": 2, "pendingReview": 1, "highestLevel": "HIGH"},
    "devices": {"online": 3, "total": 4, "minimumBatteryPercent": 36},
    "todos": {"total": 6, "overdue": 1}
  },
  "activeTasks": [],
  "urgentEvents": [],
  "topTodos": [],
  "latestReport": null,
  "dataStatus": "FRESH",
  "partial": false,
  "warnings": [],
  "generatedAt": "2026-09-03T07:30:00.123Z"
}
```

### 5.5 态势图层

`GET /dashboard/situation?bbox=lng1,lat1,lng2,lat2&layers=aircraft,tasks,events`

```json
{
  "requestId": "req-001",
  "code": "OK",
  "message": "success",
  "data": {
    "coordinateSystem": "GCJ02",
    "aircraft": [],
    "taskTracks": [],
    "events": [],
    "areas": [],
    "generatedAt": "2026-09-03T07:30:00.123Z",
    "dataStatus": "FRESH"
  },
  "serverTime": "2026-09-03T07:30:00.123Z"
}
```

地图对象必须包含 `updatedAt`、`dataStatus`；事件位置额外包含 `source` 和 `accuracyRadiusM`。

### 5.6 任务列表

`GET /inspection-tasks?status=EXECUTING&keyword=&from=&to=&limit=20&cursor=`

任务摘要：

```json
{
  "taskId": "task-001",
  "name": "东山林区例行巡检",
  "status": "EXECUTING",
  "version": 18,
  "progressPercent": 42,
  "wayline": {"waylineId": "pwl-001", "name": "东山网格 A"},
  "aircraft": {
    "deviceSnMasked": "1581****AEK3P",
    "modelKey": "M3T",
    "aircraftFamily": "MAVIC_3_ENTERPRISE"
  },
  "payload": {"model": "M3T_CAMERA", "positionIndex": 0},
  "currentWaypointIndex": 5,
  "totalWaypoints": 12,
  "batteryPercent": 63,
  "dataStatus": "FRESH",
  "scheduledAt": "2026-09-03T07:00:00.000Z",
  "updatedAt": "2026-09-03T07:29:58.000Z"
}
```

### 5.7 任务详情

`GET /inspection-tasks/{taskId}`

响应至少包含：

- 基本信息、状态、版本、计划和审批。
- 航线、飞机、遥控器、载荷和飞手。
- 预检摘要、实时进度、最近遥测和数据新鲜度。
- 事件、问题、媒体和报告计数。
- 当前用户 `availableActions`；客户端只用于展示，提交时服务端再次鉴权。

```json
{
  "taskId": "task-001",
  "name": "东山林区例行巡检",
  "status": "EXECUTING",
  "version": 18,
  "progress": {
    "percent": 42,
    "currentWaypointIndex": 5,
    "totalWaypoints": 12,
    "lastProgressAt": "2026-09-03T07:29:58.000Z"
  },
  "telemetry": {
    "batteryPercent": 63,
    "rtkStatus": "FIXED_POINT",
    "satelliteCount": 24,
    "signalQuality": "GOOD",
    "location": {
      "wgs84": {"longitude": 107.123, "latitude": 34.456},
      "gcj02": {"longitude": 107.129, "latitude": 34.454},
      "accuracyRadiusM": 1.5,
      "source": "MSDK_RTK",
      "capturedAt": "2026-09-03T07:29:58.000Z"
    }
  },
  "availableActions": ["PAUSE", "STOP", "RETURN_HOME_REQUEST"],
  "dataStatus": "FRESH"
}
```

### 5.8 提交审批

`POST /inspection-tasks/{taskId}/approval-submissions`

```json
{
  "expectedTaskVersion": 7,
  "approverUserIds": ["usr-leader-001"],
  "comment": "请审批 9 月 3 日东山巡检任务"
}
```

返回 `201`，任务进入 `PENDING_APPROVAL`，并生成待办和通知 Outbox。

### 5.9 审批决定

`POST /approvals/{approvalId}/decisions`

```json
{
  "decision": "APPROVE",
  "comment": "同意，按预定时段执行",
  "expectedApprovalVersion": 3
}
```

`decision`：`APPROVE`、`REJECT`。审批已结束时返回 `409 APPROVAL_ALREADY_DECIDED`。

### 5.10 飞行指令预检

`POST /flight-command-previews`

权限：动作对应的 `task:*`。

```json
{
  "taskId": "task-001",
  "action": "EXECUTE",
  "expectedTaskVersion": 8,
  "reason": "执行已审批的东山林区巡检任务"
}
```

成功响应：

```json
{
  "requestId": "req-001",
  "code": "OK",
  "message": "preflight passed",
  "data": {
    "allowed": true,
    "confirmToken": "ct_...",
    "expiresAt": "2026-09-03T07:31:00.000Z",
    "taskVersion": 8,
    "action": "EXECUTE",
    "riskLevel": "MEDIUM",
    "summary": "将由 M350 RTK/H30T 执行 12 个航点，完成后返航",
    "checks": [
      {"code": "TASK_APPROVED", "status": "PASS", "message": "任务已审批"},
      {"code": "AGENT_ONLINE", "status": "PASS", "message": "Agent 状态 2 秒前更新"},
      {"code": "AIRCRAFT_MATCH", "status": "PASS", "message": "M350_RTK 匹配"},
      {"code": "COMPATIBILITY_VERIFIED", "status": "PASS", "message": "飞机/RC/负载/固件组合已验收"},
      {"code": "WAYLINE_CAPABILITY", "status": "PASS", "message": "Agent 已显式上报航线执行能力"},
      {"code": "PAYLOAD_MATCH", "status": "PASS", "message": "H30T/0 匹配"},
      {"code": "BATTERY", "status": "PASS", "message": "电量 86%"},
      {"code": "RTK", "status": "WARN", "message": "当前 FLOAT，现场飞手需确认"}
    ],
    "requiredConfirmationText": "我已确认任务、设备、航线和风险信息"
  },
  "serverTime": "2026-09-03T07:30:00.123Z"
}
```

不允许响应仍返回 `200`，但 `allowed=false` 且不返回 `confirmToken`。客户端展示全部阻断项。

### 5.11 提交飞行指令

`POST /flight-commands`

请求头：

```http
Idempotency-Key: 96955fd1-4797-4a5c-b8b4-b4f6745211b1
```

请求：

```json
{
  "taskId": "task-001",
  "action": "EXECUTE",
  "expectedTaskVersion": 8,
  "confirmToken": "ct_...",
  "confirmationTextAccepted": true,
  "reason": "执行已审批的东山林区巡检任务"
}
```

响应：`202 Accepted`

```json
{
  "requestId": "req-001",
  "code": "OK",
  "message": "command accepted",
  "data": {
    "commandId": "cmd-001",
    "status": "QUEUED",
    "action": "EXECUTE",
    "taskId": "task-001",
    "queuedAt": "2026-09-03T07:30:10.000Z",
    "expiresAt": "2026-09-03T07:32:10.000Z"
  },
  "serverTime": "2026-09-03T07:30:10.050Z"
}
```

动作枚举：

- `EXECUTE`
- `PAUSE`
- `RESUME`
- `STOP`
- `RETURN_HOME_REQUEST`
- `RETURN_HOME`（首版默认关闭）

### 5.12 指令查询

`GET /flight-commands/{commandId}`

```json
{
  "commandId": "cmd-001",
  "taskId": "task-001",
  "action": "EXECUTE",
  "status": "ACKED",
  "statusMessage": "Agent 已接收，等待 MSDK 执行结果",
  "requestedBy": {"userId": "usr-001", "displayName": "张三"},
  "reason": "执行已审批的东山林区巡检任务",
  "queuedAt": "2026-09-03T07:30:10.000Z",
  "dispatchedAt": "2026-09-03T07:30:11.000Z",
  "ackedAt": "2026-09-03T07:30:12.000Z",
  "finishedAt": null,
  "result": null,
  "version": 4
}
```

### 5.13 事件列表

`GET /events?type=FIRE&status=PENDING_REVIEW&level=HIGH&from=&to=&limit=20&cursor=`

事件摘要包含 `eventId`、类型、等级、复核状态、坐标质量、首次/最近发现时间、复报数、缩略图、关联任务/问题和处置状态。

### 5.14 事件复核

`POST /events/{eventId}/reviews`

```json
{
  "decision": "CONFIRMED",
  "comment": "现场视频可见明火，立即处置",
  "expectedEventVersion": 12
}
```

`decision`：`CONFIRMED`、`SUSPECTED`、`FALSE_ALARM`。复核不会自动执行飞行或灭火投放。

### 5.15 创建问题

`POST /issues`

```json
{
  "sourceType": "FIRE_EVENT",
  "sourceId": "fire-event-001",
  "taskId": "task-001",
  "level": "HIGH",
  "title": "东山林区疑似明火",
  "description": "请立即核查并反馈处置结果",
  "assigneeOrganizationId": "org-fire-001",
  "assigneeUserId": "usr-operator-001",
  "dueAt": "2026-09-03T08:30:00.000Z"
}
```

返回 `201`，同时创建首条时间线和通知 Outbox。

### 5.16 提交处理完成

`POST /issues/{issueId}/completion-submissions`

```json
{
  "expectedIssueVersion": 6,
  "result": "已完成现场处置，明火熄灭",
  "attachmentIds": ["att-001", "att-002"],
  "requestRecheck": true
}
```

问题进入 `PENDING_RECHECK`。不能由处理人直接关闭需要复检的问题。

### 5.17 复检决定

`POST /issues/{issueId}/recheck-decisions`

```json
{
  "decision": "PASS",
  "comment": "复检未发现复燃",
  "attachmentIds": ["att-003"],
  "expectedIssueVersion": 7
}
```

`decision`：`PASS`、`REOPEN`。

### 5.18 创建/重生成报告

`POST /reports`

```json
{
  "taskId": "task-001",
  "reportType": "INSPECTION",
  "regenerate": false,
  "reason": "任务完成自动生成"
}
```

响应 `202`：

```json
{
  "reportId": "rpt-001",
  "reportNo": "XJ-20260903-0001",
  "version": 1,
  "status": "PENDING"
}
```

相同任务、类型已有生成中任务时，返回同一报告，不重复创建。

### 5.19 报告详情

`GET /reports/{reportId}`

```json
{
  "reportId": "rpt-001",
  "reportNo": "XJ-20260903-0001",
  "reportType": "INSPECTION",
  "status": "READY",
  "version": 1,
  "task": {"taskId": "task-001", "name": "东山林区例行巡检"},
  "conclusion": {"level": "ATTENTION", "summary": "发现 1 个高等级问题，已处置并通过复检"},
  "metrics": {
    "distanceM": 4210,
    "durationSeconds": 1320,
    "coverageAreaM2": 186000,
    "completionPercent": 100
  },
  "issues": [],
  "signatures": [],
  "file": {"sha256": "...", "sizeBytes": 2097152, "pageCount": 12},
  "templateVersion": "v1",
  "generatedAt": "2026-09-03T08:10:00.000Z"
}
```

### 5.20 报告下载凭证

`GET /reports/{reportId}/download-ticket`

```json
{
  "downloadUrl": "https://media.example.com/report/...?token=...",
  "expiresAt": "2026-09-03T08:12:00.000Z",
  "sha256": "...",
  "watermark": "张三 2026-09-03"
}
```

有效期建议 120 秒，不返回对象存储永久地址。

### 5.21 报告签阅

`POST /reports/{reportId}/signatures`

```json
{
  "decision": "ACKNOWLEDGED",
  "comment": "已阅，请跟踪重点区域复巡",
  "expectedReportVersion": 1,
  "fileSha256": "客户端当前查看文件的哈希"
}
```

若文件哈希或报告版本变化，返回 `409 REPORT_VERSION_CHANGED`。

### 5.22 设备摘要与兼容能力

`GET /devices`、`GET /devices/{deviceSn}`

设备对象不得只返回展示名称；必须同时返回规范机型族、真实上报能力、验收状态和
最终操作策略：

```json
{
  "deviceSnMasked": "1581****AEK3P",
  "model": "Matrice 4T",
  "modelKey": "M4T",
  "aircraftFamily": "MATRICE_4_ENTERPRISE",
  "compatibilityStatus": "PARTIAL",
  "operationPolicy": "READ_ONLY",
  "online": true,
  "batteryPercent": 82,
  "capabilityProfile": {
    "combinationKey": "M4T|RCPLUS2ENTERPRISE|M4T_CAMERA|0",
    "knownModel": true,
    "acceptanceVerified": false,
    "telemetryReadable": true,
    "visibleStreamReported": true,
    "thermalReported": true,
    "laserReported": true,
    "waylineControlReported": false,
    "flightControlEligible": false,
    "blockingReasons": ["WAYLINE_CAPABILITY_NOT_REPORTED", "COMBINATION_NOT_VERIFIED"]
  },
  "dataStatus": "FRESH",
  "updatedAt": "2026-09-03T07:30:00.123Z"
}
```

`compatibilityStatus` 表示证据级别，不等同于 SDK 厂商支持清单：

- `VERIFIED`：当前飞机、控制端、负载、固件和适配器组合已完成项目验收。
- `PARTIAL`：只验证部分能力，例如遥测和直播，未验证飞行控制。
- `UNVERIFIED`：已有适配代码或枚举，但没有当前组合的设备/飞行证据。
- `UNKNOWN`：无法规范识别型号或控制拓扑。

### 5.23 创建直播会话

`POST /live-sessions`

```json
{
  "deviceSn": "1581F7K3D249C00AEK3P",
  "camera": "VISIBLE",
  "quality": "ADAPTIVE",
  "taskId": "task-001"
}
```

响应：

```json
{
  "liveSessionId": "live-001",
  "status": "READY",
  "protocol": "RTMP",
  "playUrl": "rtmp://media.example.com/live/...?token=...",
  "expiresAt": "2026-09-03T07:32:00.000Z",
  "fallbackSnapshotUrl": "https://media.example.com/snapshot/...?token=...",
  "telemetryChannel": "task-001"
}
```

播放 URL 不得写日志或用于分享。若微信类目/组件权限未开通，返回 `MEDIA_LIVE_PLAYER_NOT_ENABLED` 并提供快照。

### 5.24 登记微信订阅授权

`POST /notification-subscriptions`

```json
{
  "results": [
    {"templateId": "tmpl-fire-high", "eventType": "FIRE.HIGH", "decision": "ACCEPT"},
    {"templateId": "tmpl-task-failed", "eventType": "TASK.FAILED", "decision": "REJECT"}
  ],
  "requestedAt": "2026-09-03T07:30:00.000Z"
}
```

该接口只登记客户端 `wx.requestSubscribeMessage` 的结果；不能代替微信授权动作。

### 5.25 创建实时连接 Ticket

`POST /realtime-tickets`

```json
{
  "channels": ["dashboard", "task:task-001", "notifications"],
  "lastEventId": "evt-0009"
}
```

```json
{
  "ticket": "wst_...",
  "websocketUrl": "wss://api.example.com/miniapp/ws?ticket=wst_...",
  "expiresAt": "2026-09-03T07:31:00.000Z",
  "heartbeatSeconds": 25
}
```

ticket 一次性使用并绑定当前会话、workspace 和 channels。

## 6. WebSocket 契约

### 6.1 服务端事件

```json
{
  "eventId": "evt-0010",
  "eventType": "FLIGHT_COMMAND.STATUS_CHANGED",
  "workspaceId": "ws-001",
  "occurredAt": "2026-09-03T07:30:12.000Z",
  "resourceType": "FLIGHT_COMMAND",
  "resourceId": "cmd-001",
  "version": 4,
  "data": {
    "taskId": "task-001",
    "action": "EXECUTE",
    "status": "ACKED",
    "message": "Agent 已接收"
  }
}
```

事件类型：

```text
DASHBOARD.SUMMARY_INVALIDATED
TASK.STATUS_CHANGED
TASK.PROGRESS_UPDATED
TASK.TELEMETRY_UPDATED
FLIGHT_COMMAND.STATUS_CHANGED
FIRE_EVENT.CREATED
FIRE_EVENT.UPDATED
ISSUE.STATUS_CHANGED
REPORT.STATUS_CHANGED
NOTIFICATION.CREATED
DEVICE.STATUS_CHANGED
LIVE_SESSION.STATUS_CHANGED
```

### 6.2 客户端消息

```json
{"type":"PING","sentAt":"2026-09-03T07:30:25.000Z"}
```

服务端：

```json
{"type":"PONG","serverTime":"2026-09-03T07:30:25.050Z"}
```

客户端不通过 WebSocket 提交飞行操作，所有写操作使用 HTTPS + 幂等键。

## 7. 错误码

### 7.1 通用与认证

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 参数校验失败 |
| 400 | `INVALID_CURSOR` | 游标无效或过期 |
| 401 | `AUTH_REQUIRED` | 未登录 |
| 401 | `AUTH_TOKEN_EXPIRED` | access token 过期 |
| 401 | `AUTH_REFRESH_TOKEN_INVALID` | refresh token 无效 |
| 401 | `AUTH_REFRESH_TOKEN_REPLAYED` | 发现轮换令牌重放，会话族已撤销 |
| 403 | `PERMISSION_DENIED` | 无动作权限 |
| 403 | `WORKSPACE_ACCESS_DENIED` | 无工作空间权限 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或无权查看 |
| 409 | `RESOURCE_VERSION_CONFLICT` | 资源版本变化 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 相同幂等键请求体不同 |
| 413 | `PAYLOAD_TOO_LARGE` | 请求或文件过大 |
| 429 | `RATE_LIMITED` | 请求过频 |
| 500 | `INTERNAL_ERROR` | 未分类服务端错误 |
| 503 | `SERVICE_UNAVAILABLE` | 服务暂不可用 |
| 503 | `MINIAPP_DISABLED` | 当前环境未启用小程序 API |

### 7.2 微信身份

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `WECHAT_CODE_INVALID` | 登录 code 无效/已使用 |
| 502/503 | `WECHAT_API_UNAVAILABLE` | 微信接口异常或服务端适配器未配置 |
| 401 | `BINDING_TICKET_INVALID` | 绑定票据无效 |
| 409 | `WECHAT_ALREADY_BOUND` | 微信身份已绑定其他账号 |
| 403 | `USER_BINDING_DISABLED` | 绑定已禁用 |
| 409 | `BINDING_PENDING_APPROVAL` | 等待管理员审批 |

### 7.3 任务、审批与指令

| HTTP | code | 说明 |
| --- | --- | --- |
| 409 | `TASK_STATE_NOT_ALLOWED` | 当前状态不允许该动作 |
| 409 | `TASK_VERSION_CONFLICT` | 任务状态/版本已变化 |
| 409 | `APPROVAL_ALREADY_DECIDED` | 审批已经完成 |
| 422 | `PREFLIGHT_BLOCKED` | 安全预检存在阻断项 |
| 409 | `CONFIRM_TOKEN_INVALID` | 二次确认 token 无效 |
| 409 | `CONFIRM_TOKEN_EXPIRED` | 二次确认已过期 |
| 409 | `CONFIRM_SNAPSHOT_CHANGED` | 风险/设备/任务快照变化 |
| 409 | `AIRCRAFT_COMMAND_CONFLICT` | 飞机存在互斥指令 |
| 422 | `AGENT_OFFLINE` | Agent 不在线或状态陈旧 |
| 422 | `AIRCRAFT_NOT_READY` | 飞机状态不满足执行条件 |
| 422 | `AIRCRAFT_MODEL_MISMATCH` | 实际机型与航线不匹配 |
| 422 | `AIRCRAFT_MODEL_UNKNOWN` | 型号无法规范识别，仅允许只读 |
| 422 | `AIRCRAFT_COMBINATION_NOT_VERIFIED` | 当前飞机、控制端、负载和固件组合未验收 |
| 422 | `WAYLINE_CAPABILITY_NOT_REPORTED` | 运行端未显式上报航线控制能力 |
| 422 | `PAYLOAD_MODEL_MISMATCH` | 载荷型号或位置不匹配 |
| 403 | `FLIGHT_CONTROL_DISABLED` | 系统/workspace/用户灰度未开放 |
| 504 | `COMMAND_ACK_TIMEOUT` | Agent 未在时限内 ACK |
| 504 | `COMMAND_RESULT_TIMEOUT` | 未收到最终执行结果 |

### 7.4 报告、通知与媒体

| HTTP | code | 说明 |
| --- | --- | --- |
| 409 | `REPORT_ALREADY_GENERATING` | 报告正在生成 |
| 422 | `REPORT_SOURCE_NOT_READY` | 任务未终态或数据不足 |
| 409 | `REPORT_VERSION_CHANGED` | 签阅时报告版本/哈希变化 |
| 503 | `REPORT_RENDERER_UNAVAILABLE` | 渲染器不可用 |
| 409 | `NOTIFICATION_SUBSCRIPTION_REQUIRED` | 未获得所需模板授权 |
| 422 | `WECHAT_TEMPLATE_UNAVAILABLE` | 模板未配置或失效 |
| 403 | `MEDIA_ACCESS_DENIED` | 无媒体查看权限 |
| 503 | `MEDIA_STREAM_UNAVAILABLE` | 当前无可用直播 |
| 503 | `MEDIA_LIVE_PLAYER_NOT_ENABLED` | 小程序直播组件未开通 |

## 8. 限流与防滥用

| 接口 | 建议限制 |
| --- | --- |
| 微信登录 | 单 IP 30 次/分钟，单 deviceId 10 次/分钟 |
| 刷新令牌 | 单会话 10 次/分钟 |
| 普通查询 | 单用户 300 次/分钟 |
| 首页聚合 | 单用户 60 次/分钟 |
| 飞行预检 | 单任务/用户 10 次/分钟 |
| 飞行指令 | 单任务/用户 5 次/分钟；同飞机串行 |
| 报告生成 | 单任务 3 次/小时 |
| 分享创建 | 单用户 20 次/小时 |
| 上传凭证 | 单问题 30 次/小时 |

触发限流返回 `429` 和 `Retry-After`。

## 9. 文件上传流程

```text
POST /issues/{id}/attachments
  -> 返回 uploadId、短时上传 URL、大小/MIME/哈希要求
客户端直接上传对象存储
  -> POST /issues/{id}/attachments/{uploadId}/complete
后端验证对象、MIME、大小、SHA-256 和扫描状态
  -> 返回 attachmentId
```

默认单图 ≤ 20MB，单视频 ≤ 200MB；最终限制由组织策略配置。

## 10. 回调安全

### 10.1 微信回调

- 按微信官方算法验证签名、时间戳和 nonce。
- 拒绝超时和重复事件；以微信事件 ID/业务键幂等。
- 仅更新 `notification_delivery`，不由回调触发飞行操作。

### 10.2 媒体回调

- 使用 mTLS 或 `timestamp + nonce + bodySha256` HMAC。
- 默认 5 分钟时间窗，nonce 一次性。
- 播放开始/停止回调只能更新直播会话投影，不改变任务事实状态。

## 11. 向后兼容与版本

- v1 允许新增可选字段和新枚举；客户端必须忽略未知字段。
- 删除字段、改变单位、改变必填性或改变状态语义属于破坏性修改。
- OpenAPI 通过 CI 做 breaking-change 检查。
- BFF 负责将现有 snake_case/camelCase、毫秒时间戳和旧枚举转换为本契约。
- 小程序发版需声明支持的 `minApiVersion`；后端可返回 `426 CLIENT_UPGRADE_REQUIRED`。
