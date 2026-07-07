# T3：帧探测事件驱动测温（火情识别提速 Phase 2 / agent 部分）

## 背景

T1 已完成：探测周期 10s→2s、无候选跳过网格扫描、告警不被快照阻塞。本任务把"周期轮询发现热区"升级为"帧探测命中即触发"，消除最后的固定等待。

`ThermalFrameProbe` 每 500ms 在解码帧上做一次亮点检测（HOTSPOT_DETECT_INTERVAL_MS=500），当前结果只被 `ThermalHotspotMonitor.pollOnce` 的周期轮询被动读取。

## 任务范围（仅 rcplus-msdk-agent）

### 1. 帧探测回调触发测温

- `ThermalFrameProbe` 检出新的高亮候选区时，通过回调/监听器立即通知（新增最小接口，构造注入，默认 no-op 保持向后兼容）。
- `ThermalHotspotMonitor`（或新的小型协调器）订阅该回调：收到候选即执行与 `pollOnce` 相同的"测温→达标上报→异步可见光确认"流程。
- **去抖 2s**：一次触发处理完成后 2s 内忽略新回调（防止 500ms 帧探测风暴）；与周期轮询共享"进行中"互斥，同一时刻只有一个测温流程在跑。
- 周期轮询（2s）保留为兜底，不删除。

### 2. 会话/开关约束

- 回调触发路径必须遵守与 pollOnce 相同的前置条件：session RUNNING 且 `thermalMonitoringEnabled=true`，否则忽略。
- 线程/协程安全：回调可能来自解码线程，切到与现有 monitor 相同的协程上下文执行。

### 3. urgent 命令通道接线（T2 遗留的 agent 侧配合）

T2 已在 backend 侧给 `DualStreamCommandDTO` 加了可空 `urgent` 字段，并对 `thermal-monitor-on`、`focus-thermal`、`focus-visible`、`measure-thermal-region` 置 true。但 agent 的 500ms urgent 轮询（`AgentRuntimeLoop` 的 urgent poller = `msdkControlPoller`，`CommandPollingCoordinator` 配置 `pollLegacyDualStream=false`）目前只拉 MSDK 命令队列，dual-stream 命令仍走 5s 普通轮询。

- 采用 T2 报告的 Option A：让 urgent 轮询也拉取 legacy dual-stream 命令（`AgentBackendClient.pollCommand`），agent 命令响应模型加可空 `urgent` 字段。
- 兼容性：backend 未标 urgent 或字段缺失时行为与现状完全一致（普通 5s 轮询处理）。
- 防重复：同一命令不能被普通轮询和 urgent 轮询重复执行（沿用现有 ack/去重机制，读代码确认后实现）。

### 4. 测试

- 新增单测：回调触发立即测温上报（不等轮询周期）；去抖生效（2s 内重复回调只处理一次）；monitoring 关闭时回调被忽略；与轮询互斥（同时只有一个流程）。
- 既有 ThermalHotspotMonitorTest 全部保持通过。

## 验收

1. `gradlew.bat :app:testDebugUnitTest` 通过，贴出 ThermalHotspotMonitor 相关与新增测试结果。
2. 写报告 `work-records/codex/T3-report-20260706.md`：设计说明（回调接口、去抖与互斥实现）、改动清单、测试输出摘要、遗留问题。

## 红线

- 只改 rcplus-msdk-agent；不动 backend、ai-service、frontend。
- 不改 `RealMsdkStreamProvider.restartLiveStream` 逻辑。
- 不 git commit / push。
- 不触碰工作区已有未提交改动的 3 个文件（HgtTerrainElevationService.java、HgtTerrainElevationServiceTest.java、StreamSplitterServiceTest.java）。
- 保持 T1 已做的改动不回退。
