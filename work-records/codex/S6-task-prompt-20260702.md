# 任务 S6：巡检闭环——火情确认到处置事件（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。已完成：S1 释放安全边界、S2/S2.1 事件编排层、S3 前端工作台骨架、S4 资源锁与指令队列、S5 合规门禁（R01-R15）。

## 目标

打通"AI 告警/巡检发现 → 候选火情 → 人工确认 → 自动生成处置事件与任务草稿 → 复测建议"的完整链路，前端工作台接入真实数据流。本任务允许改后端 fc100 模块与前端。

安全红线：
- 不修改 FireMissionStatus、IncidentTransitionTable、S1 释放策略、S4 锁与队列、S5 规则引擎语义。
- 不碰 cloud-sdk、rcplus-msdk-agent、ai-service。
- 人工确认前不得生成可派发的 FC100 灭火任务；坐标质量非 PRECISE 不得生成可派发投放任务草稿。

## 工作内容

### 1. 后端：火情确认/驳回流（event 包 + operation 联动）

先阅读 FireEventServiceImpl 既有逻辑（已有 AI 告警合并、聚合字段、geo quality、auto-created mission 逻辑——注意日志里有 "auto-created mission from fire event"，先搞清现状再动）。

- POST /api/fire/events/{id}/confirm：操作人确认候选火情。确认后：
  a) fire_event.confirmed_status -> CONFIRMED；
  b) 自动创建 operation_incident（复用 S2 OperationIncidentService.create，避免重复创建：若已有活跃 incident 则复用并返回）；
  c) 若 location_quality = PRECISE 且尚无草稿任务：生成 fc100_fire_mission 草稿并回填 incident_id；若非 PRECISE：不生成草稿，incident 详情中给出"需复测或人工标注坐标"建议标记；
  d) 生成复测建议记录（新实体或 incident 上的字段，简单即可：recommended_recheck 布尔 + 建议原因）。
- POST /api/fire/events/{id}/reject：驳回为误报。fire_event.confirmed_status -> REJECTED；若已关联 incident（CANDIDATE/CONFIRMED 状态）走 mark-false-alarm。
- 既有"auto-created mission"逻辑与红线冲突时（人工确认前就建任务），必须改为：自动创建的任务只能是草稿且不可派发（依赖 S5 R01 已兜底 dispatch，但要确认创建侧也不越权），在报告中说明现状与处理。
- 两接口挂 @Idempotent，记录操作人，写审计日志。

### 2. 后端：复测判定的饱和处理

- fire_event 若 max_temp 接近 M4T 低增益上限（阈值 fc100.thermal.saturation-temp-c，默认 540），复测"火情已解除"判定不得仅凭绝对温度下降，需要热源面积/相对热异常同时满足（在既有复测相关逻辑处加校验；若无复测判定逻辑则实现最小版本：POST /api/fire/events/{id}/recheck-result 记录复测结果并按上述规则给出 resolved/unresolved 建议，联动 incident 状态 RECHEKING->RESOLVED 或 CONTINUE_RESPONSE->RESPONDING）。

### 3. 前端：工作台接入真实数据流

- 事件列表接入候选火情：CANDIDATE 火情显示在工作台（可与 incident 列表统一或分区展示，遵循 S3 的 IncidentListPanel 结构）。
- "确认火情"按钮从占位变为真实调用 confirm 接口，成功后刷新并选中新 incident；"标记误报"对候选火情调 reject。
- 详情面板展示：坐标质量徽标（PRECISE/ESTIMATED/MANUAL_MARKED/UNKNOWN 四色）、置信度、热成像饱和警示（max_temp >= 阈值时显示"测温可能饱和"）。
- 复测结果录入表单（最小版：温度/面积/明火/建议），提交 recheck-result。
- 更新 operation-policy.mjs 策略与测试：确认按钮仅 CANDIDATE 火情可见、非 PRECISE 坐标时任务草稿生成提示等。

### 4. 测试

- 后端：确认流（含重复确认幂等、已有 incident 复用）、驳回流（联动 mark-false-alarm）、非 PRECISE 不生成草稿、饱和场景复测判定（绝对温度降但面积未降 -> unresolved）、recheck-result 联动 incident 状态双向（RESOLVED / CONTINUE_RESPONSE）。
- 前端：策略测试更新（确认/驳回按钮可见性、坐标质量徽标映射、饱和警示）。
- 运行：后端 mvn -pl uavfire test -Dtest="*Fire*,*Incident*,*Operation*,*Preflight*,*Compliance*,*Lease*,*Command*,PayloadServiceImplReleasePolicyTest" 全绿；前端 npm run test:policies 全绿 + npm run build 通过。

### 5. 交付

- 真实输出记录到 work-records/codex/S6-report-20260702.md（含"auto-created mission 现状与处理"说明）。
- 不 git commit。

## 禁区

- 不动 ai-service（饱和处理全部在后端判定层做）。
- 不实现 Delivery Sync 任务创建/释放联动（S7）。
- 不做巡检航线派发的新功能（复用既有 wayline 能力，不迁移不重构）。
