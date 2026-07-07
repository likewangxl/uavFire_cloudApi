# 火情识别提速改造 — 最终汇总报告（2026-07-06）

编排方式：Claude 负责分析/计划/审查，Codex 执行 T1/T2/T1.1/T3 四个子任务。

## 目标与结论

火情识别端到端延迟 ~180s → 设计目标 10–20s。四个耗时根因全部消除：

| 根因 | 改前 | 改后 |
|---|---|---|
| 热区探测周期 | 10s | 2s + 帧探测（500ms）命中即触发 |
| 无候选时串行网格测温 | 9+5 区 ×~1s | 跳过本轮，只测帧候选（≤3）+seed |
| 告警被热快照阻塞 | 拿不到图丢弃事件，最多 4 次全量重测 | 先报警（图可空），快照异步补传+重报 |
| 后端测温冷却/超时 | 30s / 120s | 5s / 20s（环境变量可覆盖） |
| 监测启停命令送达 | 5s 普通轮询 | urgent 通道 500ms |

## 子任务与审查记录

- **T1**（agent 探测提速）：报告 `T1-report-20260706.md`。审查发现缺陷 → T1.1。
- **T1.1**（缺陷修复）：T1 补图成功后未带 URL 重报热事件，后端 `THERMAL_IMAGE_MISSING` 契约下 fire_event 永远建不出（比旧行为回归）。已修复：补图成功后同 taskId/sourceTs 重报再触发可见光确认。
- **T2**（backend 参数与 urgent 标记）：`DualStreamCommandDTO.urgent`（可空、向后兼容），四类命令标 urgent；cooldown/timeout 新默认值。68 测试通过。
- **T3**（事件驱动+urgent 接线）：`ThermalFrameProbe` 候选回调 → `ThermalHotspotMonitor.onFrameHotspotCandidate`（2s 去抖、与轮询共享 Mutex 互斥、周期轮询保留兜底）；agent urgent 轮询也拉 dual-stream 命令（仅执行 urgent==true，共享去重器防双执行）。审查确认：后端 pollCommand 不在取出时置 dispatched，urgent 轮询丢弃非 urgent 命令不会吞命令。

## 附带改动（审查后保留）

- `settings.gradle.kts`：uxsdk 目录存在用真实模块，缺失回退 `uxsdk-stub/`（Mac 真实构建不受影响；本机单测可编译）。注意：无真实 uxsdk 的机器**不可出 release 包**（stub 是空壳）。
- `gradle.properties`：`android.overridePathCheck=true`（AGP 接受中文路径）。
- `DjiFlightControlActionClientTest`：加 CRLF 归一化——该测试此前在 Windows 上因换行符误报失败（源码检查型测试），飞控语义未动。

## 验证

- agent：`C:\Users\51799\uavfire-verify`（ASCII 路径 + Mobile-SDK-Android-V5 浅克隆 + JDK17）跑 `:app:testDebugUnitTest`：**156 tests, 0 failures**（用真实 uxsdk 编译）。
- backend：`mvn -pl uavfire test -Dtest="DualStream*Test,SpringContextSmokeTest"`：**68 tests, 0 failures**。
- 未提交任何 commit；工作区 3 个既有脏文件未触碰。

## 遗留事项（真机联调时验证）

1. `restartLiveStream`（每次切源停/重推流）是否必要——切 `KeyCameraVideoStreamSource` 后观察 ZLM 拉流是否自动跟随，若是可删，再省数秒。
2. 端到端延迟实测：地面加热源，记录帧探测命中→测温→`recordThermalHotspotEvent`→后端 fire_event 落库各时间戳；验收 ≤20s。
3. 2s 探测周期 + 500ms 帧触发在真机上的 MSDK 负载（KeyManager 调用频率）观察。
4. ai-service `visible_yolo_model_path` 生产环境未配置时"可见光 YOLO"实为颜色启发式（settings.py F1 问题）——建议配置真实模型。
5. Phase 3（红外直接 YOLO，FLAME2 预训练）未实施，见 `C:\Users\51799\.claude\plans\yolo-mighty-squirrel.md`。
