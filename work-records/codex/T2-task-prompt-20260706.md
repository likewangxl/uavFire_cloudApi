# T2：后端测温冷却/超时收紧与命令通道提速（火情识别提速 Phase 1 / backend 部分）

## 背景

火情识别端到端约 3 分钟，后端（backend/uavfire）耗时根因：

1. `DualStreamServiceImpl` 测温冷却 `dual-stream.thermal-measurement-cooldown-ms` 默认 30_000ms——每次测温完成后 30s 内新亮点事件直接被置为 THERMAL_REJECTED（DualStreamServiceImpl.java:757-765）。
2. 在途测温命令超时 `dual-stream.thermal-measurement-timeout-ms` 默认 120_000ms——命令丢失时事件卡 THERMAL_MEASURING 最长 2 分钟。
3. `thermal-monitor-on` / `focus-thermal` 等监测启停命令走 RC 端普通轮询通道（5s 间隔）；agent 端存在 urgent 通道（`AgentRuntimeLoop.DEFAULT_URGENT_COMMAND_INTERVAL_MS=500`）。

## 任务范围（仅 backend/uavfire）

### 1. application.yml

在 `dual-stream` 配置节显式加入（若节不存在则新建，风格对齐 fc100 节的环境变量覆盖写法）：

- `thermal-measurement-cooldown-ms: ${DUAL_STREAM_THERMAL_COOLDOWN_MS:5000}`
- `thermal-measurement-timeout-ms: ${DUAL_STREAM_THERMAL_TIMEOUT_MS:20000}`

### 2. 命令通道 urgent 路由

先阅读代码回答：backend `DualStreamServiceImpl.issueCommand` 下发的命令如何被 agent 取走？命令 DTO 是否已有 urgent/优先级字段？agent 端 `CommandPollingCoordinator` / `AgentRuntimeLoop` 的 urgent 通道按什么条件拉取？

- 若命令模型已支持 urgent 标记：将 `thermal-monitor-on`、`focus-thermal`、`focus-visible`、`measure-thermal-region` 标记为 urgent。
- 若不支持：在 backend 侧做最小改动（如命令 DTO 加 urgent 字段并在上述四类命令置 true），**agent 端改动不要做**——把 agent 需要的配合改动写进报告，由后续任务处理。
- 若调研发现 urgent 通道语义与预期不符（例如只用于飞控命令），如实写进报告并只做 yml 部分。

### 3. 测试

- `DualStreamServiceImpl` 相关既有测试：确认新默认值下冷却/超时状态流转正确；若测试硬编码旧值，改为读配置或更新断言。
- 新增/更新 urgent 标记的单测（若第 2 条落地了代码改动）。
- 跑 `SpringContextSmokeTest` 确认装配无回归。

## 验收

1. `mvn -pl uavfire test -Dtest="DualStream*Test,SpringContextSmokeTest" -DfailIfNoSpecifiedTests=false` 通过（命令可按项目实际调整，多模块注意 `-pl`）。
2. 写报告 `work-records/codex/T2-report-20260706.md`：改动清单、命令通道调研结论（urgent 机制如何工作、是否落地、agent 侧还需要什么）、测试输出摘要、遗留问题。

## 红线

- 只改 backend/uavfire（java + resources + test）；不动 rcplus-msdk-agent、ai-service、frontend。
- 不 git commit / push。
- 不触碰工作区已有未提交改动的 3 个文件（HgtTerrainElevationService.java、HgtTerrainElevationServiceTest.java、StreamSplitterServiceTest.java）。
- 不动 fc100 释放策略、FireMissionStatus 等安全边界代码。
- 不改 `measure-thermal-region` 回路的业务逻辑（该回路保留为兜底，本任务只调参数与通道速度）。
