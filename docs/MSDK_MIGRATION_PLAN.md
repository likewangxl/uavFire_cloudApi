# MSDK 数据面迁移路线图

> Drafted: 2026-05-21 by Claude (盲改，用户不在场实测条件下)
> 状态：第一阶段进行中；本文是目标路线图，实际落地状态以 `docs/CURRENT_PROJECT_STATUS_2026-05-21.md` 和当前代码为准。

## 背景

M4T + RC Plus 2 + 无 Dock 场景下，对 Cloud SDK / MSDK 各种"切镜头+双模态"
路线做了一系列 PoC，结论：

- **MSDK 双 lens 并行帧**：硬件单 live-view，不支持
- **`ThermalDisplayMode.PIP`**：在 M4T 上等价 side-by-side 强占飞行员视野，不可常驻
- **Cloud SDK `live_lens_change`**：M4T 飞机不响应 services_reply（飞机层
  不支持该方法）
- **MSDK Agent `setValue(KeyCameraVideoStreamSource)` 切镜头**：M4T 上工作
  （已通过 sample APK 的 KeyValue 调试器在 5.17.0 上验证过）

⇒ 决定：**全栈数据面从 Cloud SDK livestream 迁到 MSDK Agent**，本次只做
直播 + 航线 + 设备状态（OSD/HMS）三块，**飞控延后**。

## 本次范围 (第一阶段)

| 模块 | 文件 | 改动性质 |
|------|------|---------|
| agent | `App.kt` | 启动时自动进入 dual-stream 模式 |
| agent | `DjiMsdkStreamBinder.kt` | `bindThermal` 单流模式不抛异常 |
| agent | 新增 `OsdReporter.kt` | 订阅 MSDK OSD → MQTT 模拟 Cloud SDK 协议 |
| agent | 新增 `HmsReporter.kt` | 订阅 MSDK HMS → MQTT events topic |
| backend | `LiveStreamController.java` / `LiveStreamServiceImpl.java` | `@Deprecated`，保留 fallback |
| backend | `FireDetectionController` / `FireEventServiceImpl` | 视频源 URL 优先取 DualStream group，回落 Cloud SDK |
| frontend | `leadership-cockpit.vue` | 清除"路线 A pilot2-live-url" hack，统一 DualStream URL |
| ai-service | 视频源配置 | URL 改为按 droneSn 从 backend DualStream API 拉 |
| **航线** | **零改动** | `WaylineAgentClient`/`WaypointMissionExecutor` 5月20日已验证 |
| **飞控** | **零改动** | 第二阶段处理 |

## 当前落地状态（2026-05-21 校准）

已落地：

- `LiveStreamController` / `ILiveStreamService` / `LiveStreamServiceImpl` 已标记 `@Deprecated`，作为 Cloud SDK livestream fallback。
- `FireDetectionController` 在未传 `video_id` 时会默认拉 `rtsp://<zlm-host>:8554/live/{droneSn}-0`。
- `DjiLiveStreamController` 已用 MSDK `LiveStreamManager` 推 RTMP 到 ZLM，stream id 为 `{AGENT_AIRCRAFT_SN 或 droneSn}-0`。
- `App.kt` / `AppServices.kt` 已在启动时拉起 runtime loop、延迟启动 dual-stream、启动 OSD reporter、挂 HMS reporter scaffold。
- `OsdReporter` / `HmsReporter` / `WaylineMqttPublisher.publishCloudOsd|publishCloudEvent` 已存在。

尚未落地或未完成：

- `DjiMsdkStreamBinder.bindThermal()` 仍然硬抛旧的 `msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`，还没有改成更准确的 M4T 单 ComponentIndex 降级语义。
- `leadership-cockpit.vue` 仍保留 `startPilotLivestreamOnce()` 和 `pilotLiveUrl` patch，尚未完全统一到 DualStream URL。
- `PlannedWaylineServiceImpl.triggerFireDetectionForWayline()` 仍按 Cloud SDK 默认 `video_id` 拼 RTSP，没有迁到 `{droneSn}-0` agent stream。
- `ai-service` 还不会按 `droneSn` 主动查询 backend DualStream group，也没有 composite side-by-side slicer。
- OSD/HMS reporter 和 Cloud SDK MQTT payload helper 尚无单测。

## 关键约束

1. **不删现有 Cloud SDK livestream 代码** — 保留 fallback 便于回滚
2. **航线不动** — 已工作，最近 `WaypointProbeController` 跑通了 M4T pushKMZ
3. **飞控不动** — 没有实测条件下不能盲改飞控代码（安全敏感）
4. **OSD/HMS 上报字段必须严格对齐 Cloud SDK 协议** — backend 现有订阅器不改，
   靠 agent 假装成 Cloud SDK 协议发布者
5. **每个文件改动单独 commit** — 便于局部 review/回滚

## 第二阶段（必须实测后才能动）

- 真删除 Cloud SDK livestream 模块
- 飞控（virtual stick / 起飞 / 降落 / 紧急停止 / 返航）
- 设备拓扑迁移（删 Cloud SDK MQTT 设备订阅，全靠 agent 上报）
- 验证 agent OSD/HMS 协议字段在 M4T 上准确性
- 双模态识别策略最终决定（接受单可见光 / 时分复用 / side-by-side / 等更高阶机型）

## 部署变更

迁移完成后 **运行时模型变化**：

- RC Plus 2 上 **不再跑 DJI Pilot 2**，改为 **uavfir Agent APK 常驻**
- 飞行员视野 = Agent 自定义 UI（目前是 ValidationConsoleController 简单页，未来需要补全为飞行视图）
- Cloud SDK MQTT broker 仍在用（agent OSD/HMS 也走同一个 broker），backend 协议无感

## 回滚方案

- 任意一个 commit 单独 revert
- Cloud SDK livestream 代码本阶段保留，回滚时把 frontend/ai-service URL 源切回 Cloud SDK 即可
- agent 端的新增（OsdReporter/HmsReporter）通过 AppServices 装配，删除装配即可禁用

## 已知风险

1. agent OSD 字段映射 — `OsdSubTypeDataMatrice` 在 backend 是按 Cloud SDK 协议设计的，agent 需要严格按 M4T payload index/lens index 发字段
2. RTMP 推流可靠性 — agent 单点，断流后 backend/ai-service 怎么感知和重连
3. cockpit WebRTC 拉流配置 — 需要 ZLM webrtc-host/port 正确配置
4. Cloud SDK 订阅了 `thing/product/+/services_reply` 等 topic 假设 Pilot 2 在线，agent 取代后这些 reply 没人发，backend 某些等 reply 的代码会超时（如已观察过的 `live_lens_change` 211001）— 第二阶段才能完全治理
5. **`mode_code` enum 翻译表缺失** — MSDK `FlightMode.ordinal` 跟 Cloud SDK `DroneModeCodeEnum` int 值不对齐。phase 1 临时发 `null` 避免 backend Jackson 反序列化失败。phase 2 必须基于真飞机抓 Pilot 2 OSD 真值建翻译表
6. **`height` altitude 语义未验证** — `OsdReporter` 假设 `LocationCoordinate3D.altitude` 是 takeoff-relative（跟 Cloud SDK `OsdRcDrone.height` 一致），未经实测，可能实际是 MSL 绝对海拔，会导致 cockpit 显示飞行高度偏几百米
7. **HmsReporter 空 list 可能"清空告警"** — backend `HmsHandler` 处理 `{"data":{"list":[]}}` 的语义未审计（是 no-op 还是 wipe）。phase 1 默认 `enabled=false` 不启动，phase 2 审计 + 加真告警订阅后再开启
8. **cockpit / wayline 入口仍有 Cloud SDK URL 路径** — `FireDetectionController` 改成默认拉 agent stream，但只在 `video_id` 缺省时触发；`leadership-cockpit.vue` 当前手动 fire detection 调用已不传 `video_id`，但 cockpit 仍有 `startPilotLivestreamOnce()` 会启动 Cloud SDK livestream 并 patch `pilotLiveUrl`。`PlannedWaylineServiceImpl.triggerFireDetectionForWayline()` 仍显式使用 default `video_id`，导致航线触发 AI 时仍走 Cloud SDK 风格 URL。
9. **MQTT QoS 0 + broker 抖动期间静默丢失** — `publishCloudOsd` qos=0；paho auto-reconnect 期间 publish 直接丢，OSD 心跳会断几秒，cockpit 飞机看起来"短暂失联"。可接受但要文档化
10. **零测试覆盖** — `OsdReporter`/`HmsReporter`/`WaylineMqttPublisher.publishCloudOsd|publishCloudEvent` 都没单测。JSON 字段名/类型错误**不依赖真飞机就能捕到**，phase 2 至少补 OSD payload 字段名/类型/null-safe 三个用例
