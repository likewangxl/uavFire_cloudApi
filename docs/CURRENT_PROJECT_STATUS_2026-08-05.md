# 智能集群大载重无人机灭火系统当前生产状态

> 更新日期：2026-08-05
> 生产分支：`feature/fire-precision-and-realtime-detection`
> 远端跟踪：`origin/feature/fire-precision-and-realtime-detection`
> 已提交基线：`b47042b`
> 状态口径：已提交基线、当前工作区改动、现场验证结论分开记录。

## 1. 项目定位

本项目是面向森林、园区和应急消防场景的无人机集群巡检与灭火协同平台。平台以 M4T / RC Plus 2 为巡检、可见光火情识别和激光定位端，以 FC100 为重载灭火投送端，由后端、AI 服务、媒体中枢和指挥驾驶舱组成“发现—确认—定位—派发—投送—复测—归档”业务闭环。

系统定位是消防场景指挥调度与协同处置平台，不替代 DJI FlightHub 2、DeliveryHub、Delivery App 或民航 UOM/USS 正式审批系统。

## 2. 当前生产主链路

```text
M4T / RC Plus 2
  -> rcplus-msdk-agent（MSDK v5）
  -> ZLMediaKit（RTMP / RTSP / WebRTC）
  -> ai-service 可见光 YOLO
  -> backend 火情事件、坐标更新、空间合并与任务编排
  -> frontend 领导驾驶舱 / 事件处置工作台
  -> 人工确认与安全合规门禁
  -> FC100 Delivery Sync / DeliveryHub / Delivery App
```

生产火情识别已改为纯可见光主链路：YOLO 检测后通过火色像素检查、连续两帧确认和空间去重生成候选火情。红外检测与测温代码保留，但不再作为当前生产确认链路的前置条件。

## 3. 已提交生产基线能力

### 3.1 M4T / RC Plus Agent

- DJI MSDK v5 初始化、设备身份、心跳、状态、OSD/HMS 上报、命令轮询和 ack。
- 可见光直播推送到 ZLMediaKit，支持指挥端 WebRTC 播放与 AI RTSP 取流。
- 航线任务下载、KMZ 执行、状态与事件回传。
- 可见光火情触发后的悬停、ROI 对准、激光三样本测距与同一火情事件精确坐标更新。
- Agent 版本基线为 `0.1.1` / `versionCode=2`；工作区正在开发 `0.1.2` / `versionCode=3`，未经提交和验收不应当作已发布版。

### 3.2 AI 火情识别

- FastAPI 任务生命周期、连续取帧、可选 YOLO 检测和后端事件上报。
- 可见光连续两帧确认、框内火色像素否决、短窗去抖与快照存档。
- 拉流线程与检测线程分离，优先消费最新帧，降低 RTSP 停顿导致的长时间盲区。
- AI 只负责发现和辅助判断，不可自动作出真实灭火投放决策。

### 3.3 后端与指挥端

- 火情事件创建、历史、复核、坐标质量、空间合并和驾驶舱通知。
- 处置事件、资源分配、资源租约、指令队列、时间线和事件处置工作台。
- FC100 任务以 `FireMissionStatus` 20 态枚为唯一事实源。
- 已实现 15 项派发前预检规则，包括时间/能量预算、DeliveryHub 连通性和运行资质有效性。
- 合规接口为记录留证语义：`record-flight-application`、`record-takeoff-confirmation`、`record-landing-report`，不代表自动接入 UOM 审批。

## 4. 安全与合规基线

- 默认释放策略是 `MANUAL_CONFIRM`，默认执行模式是 `OFFICIAL_HOOK_MANUAL`。
- `OFFICIAL_HOOK_MANUAL`：飞手通过官方端执行开钩，平台只提供二次确认、证据和审计闭环。
- `DELIVERY_SYNC_REMOTE`：公开接口能力尚未获得 DJI 书面确认，必须受配置开关和数据库策略双重闸门约束，默认不可用。
- `PSDK_RELEASE`：二期预留，未经专项评审、风险控制和外场验证不得进入生产。
- FC100 载荷设计默认按双电 85 kg 上限并扣除吊具自重；单电 100 kg 只能作应急模式参考。
- 任何释放、急停、返航、降落和人工接管操作必须幂等、二次确认、记录操作人并写入审计。

## 5. 运行基线

| 组件 | 基线 |
| --- | --- |
| Frontend | Vue 3 + Vite，默认端口 `8080` |
| Backend | Java 11 + Spring Boot + Maven，默认端口 `6789` |
| RC Plus Agent | Java 17 + Android SDK + DJI MSDK v5 |
| ai-service | Python 3.11+ + FastAPI + OpenCV / YOLO，运行端口口径以 `RUNBOOK.md` 为准 |
| Media | ZLMediaKit，RTMP `1935`、RTSP `8554`、HTTP/WebRTC API `58925` |
| Data | MySQL + Redis + MQTT |
| 当前 LAN 参考 | `172.20.10.7`，网络变更后必须同步 backend、frontend、agent 和 ZLM |

启动、公网 VM、SSH 双向隧道、端口和现场排查步骤以根目录 `RUNBOOK.md` 为唯一运行入口，不再从旧交接文档复制命令。

## 6. 当前工作区改动（尚未纳入生产基线）

2026-08-05 文档更新前，工作区存在 51 个已跟踪文件改动和 8 个未跟踪文件。主要正在开发：

- H20 / H20T / H30 / H30T 载荷型号、载荷位置和 AI 模型配置档案。
- M300 火情闭环功能开关与 Agent 载荷选择。
- 航线计划持久化载荷字段及相关数据库迁移。
- 前端载荷能力展示、航线载荷选择和登录页素材调整。

上述改动尚未形成独立验收报告，不得在宣传、验收或生产发布说明中标记为已交付。

## 7. 已有验证证据

根据 2026-07-28 交接记录，可见光火情检测与激光定位改动曾完成以下自动化验证：

- AI：`168 passed, 1 skipped`。
- Backend：`433 tests, 0 failures, 0 errors, 0 skipped`。
- RC Plus Agent：`:app:testDebugUnitTest` 和 `:app:assembleDebug` 通过。
- Frontend policy tests：`99 passed, 0 failed`。
- Frontend：`npm run build:test` 与 `npm run build` 通过。
- `git diff --check` 通过。

这些是历史节点证据，不等于当前未提交工作区的重新验收结果。

## 8. 尚待闭环事项

1. 完成真实实火/实飞动态验证：两帧确认、悬停、tap zoom、激光三样本、同一事件更新为 `PRECISE`。
2. 继续降低可见光模型在夜间暗部的误报，并补齐白天、小余烬和稀薄烟雾样本。
3. 修复 Agent 冷启动时占位 SN 与真实飞机 SN 并存的身份时序问题。
4. 实现 ai-service 重启后的检测任务自动对账和恢复。
5. 在重启自动抵近前，拆除 `FireConfirmationProcessor` 中对红外测温的生产依赖。
6. 为当前载荷型号扩展和 M300 兼容改动补齐专项设计、测试记录和验收报告。
7. 获得 DJI 对 Delivery Sync 即时任务创建、设备命令和远程开钩能力的书面确认；未确认前保持官方飞手端人工开钩。
8. 完成 UOM/USS 正式接入前，继续把本地空域数据仅作参考层和申请证据留存。

## 9. 文档事实源与阅读顺序

1. `README.md`：仓库入口和生产分支说明。
2. `docs/CURRENT_PROJECT_STATUS_2026-08-05.md`：当前生产状态、能力边界和待办。
3. `RUNBOOK.md`：实际运行、网络、端口和排障。
4. `HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md`：可见光检测和激光定位的现场证据与历史细节。
5. `docs/superpowers/specs/2026-07-28-visible-fire-hover-laser-geolocation-design.md`：激光定位设计。
6. `docs/superpowers/plans/2026-07-28-visible-fire-hover-laser-geolocation.md`：激光定位实施计划。
7. `docs/WAYLINE_AGENT_CONTRACT.md` 和 `docs/WAYLINE_L1_L2_CONTRACT.md`：航线执行契约。
8. `../../02_需求与方案设计/智能集群巡检灭火详细设计与开发方案.md`：业务总体方案、安全边界和验收口径。

旧的 `CURRENT_PROJECT_STATUS_2026-05-21.md`、早期 M4T 双流方案和 4 月交接记录仅作历史参考；与本文档冲突时，以当前生产分支代码、`RUNBOOK.md` 和本状态文档为准。
