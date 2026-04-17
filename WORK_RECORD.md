# 前后端工作记录

记录日期：2026-04-16

本文档只记录本仓库中的 `frontend/` 和 `backend/` 两个工程。

## 1. 仓库整理

已将当前工作目录中的两个工程整理到 GitHub 仓库：

- `Cloud-API-Demo-Web-main/` -> `frontend/`
- `DJI-Cloud-API-Demo-main/` -> `backend/`

提交时已排除以下内容：

- 前端依赖目录：`node_modules/`
- 前端构建产物：`dist/`
- 后端构建产物：`target/`
- 运行日志：`logs/`、`*.log`
- IDE 元数据：`.idea/`
- 原工程中的 `.git/`

## 2. 前端修改记录

主要文件：

```text
frontend/src/pages/page-web/projects/tsa.vue
```

已完成内容：

- 将原先的模拟摇杆起飞逻辑调整为官方 `takeoff_to_point` 起飞流程。
- 起飞参数使用当前 OSD 中的经纬度作为目标点。
- 起飞流程增加确认提示，避免误认为这是 1 米本地试飞。
- 移除了前端通过持续发送 stick 控制量模拟起飞的旧逻辑。
- 保留 DRC 授权、设备选择、OSD 展示等原页面能力。

当前限制：

- 官方 `takeoff_to_point` 必须传经纬度。
- 官方 `security_takeoff_height` 最小值为 20 米。
- 该接口不是 1 米低空试飞接口。
- 起飞前必须确认 OSD 经纬度有效，否则可能向错误目标点发起起飞。

已验证：

```bash
npm run build
```

构建可以完成，存在既有 Sass 或依赖相关警告。

## 3. 后端修改记录

主要文件：

```text
backend/sample/src/main/java/com/dji/sample/control/service/impl/ControlServiceImpl.java
backend/sample/src/main/java/com/dji/sample/control/model/param/TakeoffToPointParam.java
```

已完成内容：

- 调整起飞前置检查逻辑。
- 将机场 Dock 的空闲状态检查限定在 Dock 网关上。
- RC Plus 2 场景下不再使用 Dock 专属状态作为起飞前置条件。
- 将 `securityTakeoffHeight` 的校验最小值调整为 20，匹配官方文档限制。

已验证：

```bash
mvn -pl sample -DskipTests compile
```

编译可以完成，存在 Maven 配置层面的既有警告。

## 4. 控制台乱码处理记录

后端本地启动验证时，已使用 UTF-8 方式启动并输出日志，确认可以看到可读日志。

建议后续 Windows PowerShell 启动前设置：

```powershell
chcp 65001
$OutputEncoding = [System.Text.UTF8Encoding]::new()
```

如果日志文件仍乱码，需要继续检查：

- 控制台编码。
- JVM 参数 `-Dfile.encoding=UTF-8`。
- Logback 文件编码。
- 终端软件字体和编码设置。

## 5. GitHub 提交记录

目标仓库：

```text
https://github.com/likewangxl/uavFire_cloudApi.git
```

已完成提交：

- `0985186 Initialize UAV fire Cloud API frontend and backend`
- `05744fe Add AI work records`

后续文档修复和运行说明会作为新的提交继续推送。

## 6. 后续排查建议

如果继续排查“点击起飞后遥控器提示云端操控断开”的问题，建议按以下顺序采集证据：

1. 前端点击起飞时实际请求参数。
2. 后端调用 `takeoff_to_point` 前后的日志。
3. MQTT `thing/product/{sn}/services` 发送内容。
4. MQTT `thing/product/{sn}/services_reply` 回复内容。
5. MQTT `thing/product/{sn}/events` 事件内容。
6. 遥控器界面提示和错误码。
7. 起飞前后的 DRC WebSocket MQTT 连接状态。

只有拿到 `services_reply` 或 `events` 的具体错误码，才能判断是参数问题、权限问题、飞行状态问题，还是 DRC 链路被设备侧主动断开。

---

## 7. 2026-04-17 `takeoff_to_point` 调试会话（RC Plus 2 + M4T）

本节记录在 2026-04-17 对 RC Plus 2 遥控器 + Matrice 4T 飞机"正式起飞"按钮的诊断过程，最终形成 commit `f0ef95b` 与 tag `v0.2.0-takeoff-coord-offset`。

### 7.1 错误码推进链路

| 阶段 | 错误码 | 含义 | 拦截点 |
| --- | --- | --- | --- |
| 初始 | `210003` | `DEVICE_TYPE_NOT_SUPPORT` | SDK AOP（`CloudSDKHandler.checkCloudSDK`） |
| 中间 | `336002` | Unknown，`output` 为空 | 飞机飞控预检 |
| 当前 | `336003` | 进入 `takeoff_to_point_progress` 流程后中止 | 飞机飞控预检后半段 |

### 7.2 210003 根因与修复

- 现象：调用 `takeoff_to_point` 直接抛 `CloudSDKException(DEVICE_TYPE_NOT_SUPPORT)`，命令根本未到 MQTT。
- 根因：RC Plus 2 虽然在 `GatewayTypeEnum` 里已单独拆成 `RC2`（见首次提交），但 `AbstractControlService.takeoffToPoint` 的 `@CloudSDKVersion(exclude = GatewayTypeEnum.RC)` 注解本来就只排除旧 RC，不排除 RC2；然而现场实测 `SDKManager` 识别出的网关类型有时仍为 `RC`，被 AOP 拦截。
- 修复：去掉 `exclude = GatewayTypeEnum.RC`，放开 SDK 层拦截，改由飞机端判定。见 `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/control/api/AbstractControlService.java`。
- 关键踩坑：`mvn -pl sample ... compile` 不会重新编译 cloud-sdk 模块。sample 运行时仍使用 `D:\localRepository` 里的旧 cloud-sdk-1.0.3.jar，修改看起来"没生效"。必须先 `mvn -pl cloud-sdk clean install` 刷本地仓库。

### 7.3 336002 根因与修复

- 现象：命令成功下发，飞机回 `services_reply.result=336002`，`output` 为空，MQTT 链路完全正常。
- 排查过程：
    1. 在三个位置增加详细诊断日志（ControlServiceImpl、MqttGatewayPublish、ServicesReplyHandler），确认请求 JSON 真正下发到飞机的字段名、值与 DJI 文档一致。
    2. 观察到飞机**推送了 `takeoff_to_point_progress` 事件**（说明飞机进入了起飞流程），然后才回 336002，排除"飞机不支持此命令"这一猜想。
    3. 对比 DJI 文档字段范围，将起飞参数从边缘值调整为推荐值：`max_speed` 1 → 5（DJI 范围 2-15），`rth_altitude` 20 → 100，`target_height` / `security_takeoff_height` 20 → 30。
    4. 调整后仍 336002，但起飞前 OSD 日志显示 target 坐标 = 当前 OSD 坐标（水平距离 = 0）。
- 根因：M4T 飞控在**目标点等于当前点（零水平距离）**场景下直接判为无效目标，拒绝起飞，并在 `output` 里不返回任何细节。
- 修复：前端 `handleTakeoff` 把目标点相对当前 OSD 位置向北偏约 16 m（`latitude + 0.00015°`）。修改后错误码从 336002 → 336003，并出现持续的 `takeoff_to_point_progress` 事件推送，证明已进入飞控预检流程。

### 7.4 336003 现状（未完成项）

- 现象：发送一条 `takeoff_to_point`，SDK 内置 3 秒超时会重发共 3 条（11:23:42 / 45 / 48）。飞机回 3 条 336003，同时持续推送 `takeoff_to_point_progress`。高度 OSD 显示海拔从 458.55 轻微上升到峰值 458.93（抬升约 0.4-0.7 m），随后回落——未真正起飞。
- 判断：第一条指令被飞机接受并进入 `takeoff_to_point` 流程，预检阶段因某种现场条件中止；后两条因"命令重复"被 336003 拒掉。
- 未解决原因：`services_reply.output` 为空，飞控层的拒飞原因只能在 RC Plus 2 屏幕上读取橙色/红色横幅提示（如电量过低、指南针需校准、障碍物检测、头顶遮挡等）。
- 现场需核查项（下次测试前逐项确认）：
    - [ ] 电池电量 ≥ 50%（历史日志出现过 35%）
    - [ ] GPS 状态为 GPS 固定（非 ATTI 或搜索中），卫星数 ≥ 10
    - [ ] 指南针无需校准（换地点或强磁场后通常需重新校准）
    - [ ] 遥控器新手模式已关闭
    - [ ] 起飞点头顶 30 m、周围 30 m 内无障碍物（垂直爬升高度为 `security_takeoff_height=30`）
    - [ ] 物理摇杆未被触碰
    - [ ] 遥控器屏幕出现的任何横幅提示文本

### 7.5 本次代码改动清单

| 文件 | 改动 |
| --- | --- |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/control/api/AbstractControlService.java` | 移除 `takeoffToPoint` 的 `exclude = GatewayTypeEnum.RC`；加注释说明原因 |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/mqtt/MqttGatewayPublish.java` | publish 日志从 debug 升到 info，用 `ObjectMapper.writeValueAsBytes` 序列化真实下发字节 |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/mqtt/services/ServicesReplyHandler.java` | `services_reply raw payload` 从 debug 升到 info |
| `backend/sample/src/main/java/com/dji/sample/control/service/impl/ControlServiceImpl.java` | `takeoffToPoint` 增加入参 / 网关类型 / 请求 JSON / `reply.output` 四段诊断日志 |
| `frontend/src/pages/page-web/projects/tsa.vue` | 起飞参数改为 30/30/5/100；目标点北偏 `0.00015°` (~16 m)；确认弹窗同时显示当前与目标坐标 |

### 7.6 诊断日志关键位置

启动后端后，`backend.log` 中排查起飞问题应优先关注：

```
ControlServiceImpl         : takeoffToPoint called. sn=..., param=...
ControlServiceImpl         : takeoffToPoint gateway info. gatewayType=RC2, sdkVersion=V0_0_1
ControlServiceImpl         : takeoffToPoint request JSON. json={...}
WebSocketMessageSend       : MQTT send topic: thing/product/.../services, payload: {...}
ServicesReplyHandler       : services_reply raw payload: {...}
ControlServiceImpl         : takeoffToPoint reply. result={errorCode=..., errorMsg=...}, output=...
```

配合 OSD 日志中飞机的 `latitude / longitude / height` 时间序列，可以重建完整飞行事件链路。

### 7.7 Git 备份信息

- 提交：`f0ef95b` on `main`
- Tag：`v0.2.0-takeoff-coord-offset`（annotated tag，带详细说明）
- 推送至：`https://github.com/likewangxl/uavFire_cloudApi`
- 回滚方式：`git checkout v0.2.0-takeoff-coord-offset`
- Tag 标注的状态：210003 已解除 / 336002 已通过坐标偏移绕过 / 错误码推进到 336003 待 RC 屏幕抓提示

### 7.8 下一步工作建议

1. **先处理现场条件**：按 7.4 清单逐项核查，电量、GPS、指南针是最容易忽视的三项。
2. **RC 屏幕抓图**：下次点击起飞失败瞬间，立即对遥控器屏幕拍照，记录横幅提示文字。这是目前定位飞控拒飞原因的唯一途径。
3. **考虑 SDK 重试去重**：当前 SDK 3 秒超时会发 3 条同样的 `takeoff_to_point`，飞机对第 2、3 条必然 336003。如果影响排查，可在 sample 层把 `retryCount` 传 0（单次发布）。
4. **OSD 原始字段诊断**：如需确认飞机上报的卫星数、电量、飞行模式等具体字段值，可把 `OsdRouter` 中 `log.debug("OSD原始数据 [{}] keys: {}", ...)` 临时升为 `log.info`，或在 `application.yml` 打开 `logging.level.com.dji.sdk.mqtt.osd=DEBUG`。

---

## 8. 2026-04-17 功能扩展：点选飞行 / 航线规划 可行性摸底（M4T + RC Plus 2 + DRC）

记录日期：2026-04-17（与 §7 同一日）

本节目标：在没有现场测试环境时，先把 `fly_to_point` 与点选飞行/航线规划 的资料、代码、验证步骤整理清楚，落到代码与文档两处，供下次飞行直接验证。

### 8.1 现有代码盘点

前端（`frontend/src/api/drone-control/drone.ts`）已封装：

| 函数 | 路由 | 入参 |
| --- | --- | --- |
| `postFlyToPoint(sn, body)` | `POST /control/api/v1/devices/{sn}/jobs/fly-to-point` | `{ max_speed, points: [{ latitude, longitude, height }] }` |
| `deleteFlyToPoint(sn)` | `DELETE /control/api/v1/devices/{sn}/jobs/fly-to-point` | 无 |
| `postTakeoffToPoint(sn, body)` | `POST /control/api/v1/devices/{sn}/jobs/takeoff-to-point` | 见 §7 |

前端 `components/g-map/DroneControlPanel.vue`（Dock 场景使用）已经有基于 `useDroneControl().flyToPoint` 的 "点选/手动输入 lat/lng/height" Popover，但该面板只在 Dock workspace 下挂载，RC Plus 2 场景看不到。

前端 `components/g-map/use-drone-control-ws-event.ts` 已经在处理 `EBizCode.FlyToPointProgress` 事件（54 行附近），WS 链路已经通。

后端 `cloud-sdk/...AbstractControlService.flyToPoint` 上的 `@CloudSDKVersion` 注解**不做** `exclude = GatewayTypeEnum.RC` 过滤，换言之 SDK AOP 层不会因为网关是 RC / RC2 而拦截该命令。实际可用性最终由飞机飞控决定。

### 8.2 DJI 文档 vs 实际代码差异

- DJI Cloud API 1.9.0 官方文档将 `fly_to_point` 放在 Dock 场景章节下，未明确标注 RC 场景可用。
- 但 `cloud-sdk` 与后端 sample 的实际代码允许任何网关类型调用。与 §7 里 `takeoff_to_point` 的情况一致："**SDK 允许，飞机飞控判定**"。
- 合理推测：RC2 + DRC 下 `fly_to_point` 大概率可用（本质是 commander_flight 模式下飞控接受目标点），但需要实飞验证。

### 8.3 适用前置条件

调用 `fly_to_point` 前必须全部满足：

- 飞机已经**空中**（由 `takeoff_to_point` 或手动起飞得来），`mode_code` 处于"在飞"状态
- DRC WebSocket 仍处于连接状态（`remoteControlState.connected === true`）
- 目标点与当前 OSD 位置**水平距离 ≥ 16 m**（避免重演 336002）
- 目标高度（相对起飞点）≥ `security_takeoff_height`，推荐与当前悬停高度一致以避免额外爬升

### 8.4 验证计划（下次飞行需按序完成）

1. 通过 tsa.vue "Official Takeoff" 起飞，悬停 30 m，确认 OSD `height ≈ 30`。
2. 点击新增的 "Fly Forward 20m" 测试按钮（见 §8.5），观察：
   - 后端 `services` MQTT 发送的 JSON（`ControlServiceImpl` 日志）
   - `services_reply.result` 与 `output`
   - RC Plus 2 屏幕是否出现拒飞横幅
3. 若 `result=0`，再点击 "Fly To Point (Manual)" 手动输入远距离点，验证多次调用是否稳定。
4. 若 `result ≠ 0`，记录错误码并对照 §7 错误码表，重点留意 `210003`（SDK 层拒 → 需去掉注解） / `336xxx`（飞控拒 → 查屏幕）。
5. 最后点 "Stop Fly To Point" 调用 `deleteFlyToPoint`，确认飞机停止平移并悬停。

---

## 9. 2026-04-17 功能收口：planned wayline 稳定性增强（持久化 / 恢复）

记录日期：2026-04-17

本节记录在 §8 第一版 `planned wayline` 原型基础上，继续做的“可恢复性”收口，目标是降低页面切换或浏览器刷新后丢失规划数据的风险。

### 9.1 本轮问题判断

- §8 的实现已经能在前端完成“点选航点 -> 顺序 `fly_to_point` 执行”，但所有规划数据都只保存在运行时内存中。
- 一旦刷新页面、热更新、浏览器崩溃或用户误切页面，目标机选择、默认高度、速度、航点列表都会丢失。
- 这类问题不会影响单次 demo 演示，但会直接影响下一次实飞排查效率，因此优先级高于继续堆新按钮。

### 9.2 本轮代码改动

| 文件 | 改动 |
| --- | --- |
| `frontend/src/types/enums.ts` | 为 `planned wayline` 增加独立本地存储键 `PlannedWaylineDraft` |
| `frontend/src/hooks/use-wayline-planning.ts` | 新增草稿序列化 / 反序列化；在目标机选择、航点增删改、执行结束时自动持久化；模块初始化时自动恢复 |
| `frontend/src/pages/page-web/projects/wayline.vue` | 页面挂载时优先用已恢复的 `planningState.aircraftSn` 回填目标机选择框 |

### 9.3 当前行为变化

- 重新进入 `wayline` 页面后，若浏览器本地已有草稿，会自动恢复：
  - 目标飞机 SN
  - 默认高度
  - 最大速度
  - 航点列表（GCJ / WGS / height）
- 恢复后不会自动继续“执行中”状态；执行状态统一回落为 `idle`，避免页面刷新后错误地把旧任务当成仍在运行。
- 若本地草稿损坏或 JSON 解析失败，会自动清掉坏数据，避免反复报错。

### 9.4 验证计划

本轮代码完成后，需重新执行：

```bash
cd frontend
npm.cmd run build
```

若构建通过，再补一次人工验证：

1. 打开 `wayline` 页面，选择飞机并添加 2-3 个航点。
2. 刷新页面。
3. 确认目标飞机、默认高度、最大速度、航点列表仍在。
4. 确认页面未误显示为“正在执行”。

### 9.5 本轮验证结果

- 已执行：

```bash
cd frontend
npm.cmd run build
```

- 结果：构建通过。
- 备注：仍存在项目原有的 Sass `@import` 弃用警告、legacy JS API 警告、`::v-deep` 警告和大 chunk 警告；本轮 `planned wayline` 持久化改动未引入新的构建错误。
- 尚未完成：浏览器侧“加点 -> 刷新 -> 自动恢复”的人工交互验证，需在下次打开页面时补做。

### 8.5 本次代码改动清单

| 文件 | 改动 |
| --- | --- |
| `frontend/src/pages/page-web/projects/tsa.vue` | 新增 3 个按钮：`Fly Forward 20m`、`Fly To Point (Manual)`、`Stop Fly To Point`；引入 `postFlyToPoint` / `deleteFlyToPoint`；新增对应 handler 与 manual 输入 Popover 的表单状态 |
| `frontend/src/api/drone-control/drone.ts` | 已存在所需接口，无需变更 |

按钮启用条件：

- 三个按钮都要求 `isCurrentRemoteGateway(device) === true`（DRC 已连接）。
- `Fly Forward 20m` 与 `Fly To Point (Manual)` 额外要求：飞机 OSD 存在，`mode_code !== Disconnected`，且 `height ≥ 15 m`（避免地面阶段误触发）。
- `Stop Fly To Point` 只要求 DRC 连接，方便在任何状态下中断。
- Manual 按钮点击 "发送" 前做基本数值校验（经纬度范围、height 数值型）。

### 8.6 航线规划（点选航线）初步方案

`/wayline` 页目前是纯 KMZ 文件管理，不是规划工具。针对 M4T + RC Plus 2 + DRC，短期内采取：

1. **不走官方 wayline 任务流**（`flighttask_create/prepare/execute` 是 Dock 专属，RC2 场景预期被拒）。
2. **自建点选航线**：在 `tsa.vue` / `workspace.vue` 上增加 "航点序列" 侧栏，用户依次点击地图增加航点；点 "开始执行" 后前端按顺序 `postFlyToPoint` → 监听 `FlyToPointProgress.reach_target` → 下一个点。
3. 航点在前端内存即可，不进后端数据库；执行过程中可随时 `Stop Fly To Point` 中断。

§8.5 的三个按钮是该方案的**第一步**（单点验证），在飞行验证通过之前不展开多点序列逻辑。

### 8.7 回滚参考

- 本次改动不触碰后端任何文件；前端改动限 `tsa.vue`。
- 如发现 `fly_to_point` 在 RC2 场景被 SDK AOP 拦截（返回 `210003`），参照 §7.2 的做法处理 `AbstractControlService.flyToPoint` 上的 `@CloudSDKVersion`（当前看代码无 `exclude`，应不需要改）。
- tsa.vue 回滚到 §7 tag 状态：`git checkout v0.2.0-takeoff-coord-offset -- frontend/src/pages/page-web/projects/tsa.vue`。
