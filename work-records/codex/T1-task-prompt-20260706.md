# T1：RC 端热区探测提速与告警去阻塞（火情识别提速 Phase 1 / agent 部分）

## 背景

当前火情识别端到端约 3 分钟，评审定位的 RC 端（rcplus-msdk-agent）耗时根因：

1. `ThermalHotspotMonitor` 探测周期 10s（`DEFAULT_PROBE_INTERVAL_MS=10_000`）。
2. `DjiMsdkStreamBinder.locateAndMeasureThermalHotspotC` 在帧探测无候选时退化为 9 区粗扫 + 细扫的串行 MSDK 测温（每区 ≈1s，单轮 10–15s）。
3. 热快照拿不到时最多 4 次全量重测（每次 500ms 延迟 + 完整重测），且**拿不到快照就丢弃整个告警事件**（ThermalHotspotMonitor.kt:83-86）——告警被截图阻塞。

## 任务范围（仅 rcplus-msdk-agent，例外见第 3 条）

### 1. ThermalHotspotMonitor.kt

- `DEFAULT_PROBE_INTERVAL_MS`：10_000 → 2_000。
- `THERMAL_SNAPSHOT_REMEASURE_ATTEMPTS`：4 → 1。
- **快照不再阻塞告警**：温度达标即调用 `client.recordThermalHotspotEvent`，`thermalImageUrl` 拿不到时传 null/空字符串照常上报；快照上传重试保留但改为在上报之后异步进行（成功与否不影响已发出的告警）。

### 2. DjiMsdkStreamBinder.kt — locateAndMeasureThermalHotspotC

- 仅测量帧探测候选区（`thermalFrameProbe.latestHotspotRegions()` 聚类去重后最多取 3 个）与 `seedRegion`。
- **无任何候选（帧候选为空且 seedRegion 为 null）时直接返回 null 跳过本轮**——探测周期已缩到 2s，下一轮帧探测大概率给出候选。
- 删除或短路 `coarseThermalScanRegions()` 9 区粗扫与后续细扫回退路径（若测试或他处引用，保留函数但不再在主路径调用）。
- 保留 fast-confirm（≥120°C 提前返回）逻辑。
- 保留 CENTER 兜底测温仅在 seedRegion 存在场景（跟随现有语义收窄），不要为"无候选"场景保留全屏扫描。

### 3. 后端上报契约核查（唯一允许触碰 backend 的点）

先阅读 `AgentBackendClient.recordThermalHotspotEvent` 与 backend 中接收该上报的 Controller/Service（在 `backend/uavfire` 里搜 dual-stream 事件上报入口），确认 `thermalImageUrl` 为空时后端是否拒绝：

- 若后端接受空值：backend 零改动。
- 若后端强校验非空：做**最小放宽**（允许空，reviewStatus 语义不变），并在报告中说明改了哪一行。

### 4. 测试

- 更新 `ThermalHotspotMonitorTest`：新探测间隔生效；快照缺失时事件仍上报（thermalImageUrl 为空）；重测次数为 1。
- 更新 `DjiMsdkStreamBinderHotspotSelectionTest`：无候选跳过（返回 null、零次测温调用）；候选 ≤3；fast-confirm 保留。
- 相关既有测试若引用旧常量/旧行为，同步修正，但不得降低断言强度。

## 验收

1. `gradlew.bat :app:testDebugUnitTest`（或项目等效单测任务）通过，至少贴出 ThermalHotspotMonitorTest、DjiMsdkStreamBinderHotspotSelectionTest、RealMsdkStreamProviderTest、DualStreamSessionManagerTest 的结果。
2. 写报告 `work-records/codex/T1-report-20260706.md`：改动清单（文件+要点）、测试输出摘要、契约核查结论、遗留问题。

## 红线

- 不改 ai-service、frontend；backend 仅限第 3 条所述最小放宽。
- 不改 `RealMsdkStreamProvider` 的 restartLiveStream 逻辑（真机验证项，另行处理）。
- 不 git commit / push。
- 不触碰工作区已有未提交改动的 3 个文件（HgtTerrainElevationService.java、HgtTerrainElevationServiceTest.java、StreamSplitterServiceTest.java）。
- 不动 fc100 释放策略、FireMissionStatus 等安全边界代码。
