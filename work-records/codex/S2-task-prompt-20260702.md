# 任务 S2：数据模型与事件编排接口（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。上一任务 S1 已完成并提交（release_policy 安全边界，见 work-records/codex/S1-baseline-report-20260702.md）。

## 背景与目标

在既有 fc100 模块之上，建立"处置事件（operation incident）"编排层：把已确认的火情事件（fire_event）转化为统一处置事件，管理设备/人员分配，为后续资源锁竞争（S4）、合规门禁（S5）打地基。

安全红线（不可违反）：
- 不修改 FireMissionStatus 枚举（20 态，唯一事实源）。
- 不改动 backend/cloud-sdk、rcplus-msdk-agent、ai-service。
- 不触碰 S1 已实现的释放策略校验逻辑。
- 危险入口沿用 @Idempotent 幂等切面模式。

## 工作内容

### 1. 数据库迁移（backend/sql/migrations/，日期前缀命名；同步 backend/uavfire/sql/fc100_init.sql）

先阅读既有迁移（尤其 2026-05-23-fire-event-aggregation-fields.sql、2026-05-30-fire-event-geo-quality-fields.sql）和 fc100_init.sql 中 fire_event 现有字段，避免重复添加。

新表（命名与字段说明）：
- `operation_incident`：id、incident_no（唯一）、fire_event_id、level（风险等级）、status、center_lat、center_lng、risk_radius_m、created_by、confirmed_by、closed_at、create_time、update_time。
- `operation_assignment`：id、incident_id、resource_sn（设备 SN 或人员编号）、role（MONITOR_PRIMARY / MONITOR_RECHECK / DELIVERY_PRIMARY / DELIVERY_BACKUP / COMMANDER）、status、lease_id（可空，S4 启用）、assigned_at、released_at。
- `operation_resource_lease`：id、resource_sn、lease_type、owner_type、owner_id、expires_at、heartbeat_at、status。本任务只建表和实体，不实现竞争逻辑（S4 范围）。
- `operation_command_event`：id、command_id、target_sn、command_type、payload_json、status、idempotency_key、retry_count、ack_at、error_message、create_time。本任务只建表和实体，队列逻辑属 S4。

fire_event 扩展（仅补缺失字段）：source_type、confidence、confirmed_status、linked_incident_id、location_quality（PRECISE / ESTIMATED / MANUAL_MARKED / UNKNOWN）——凡已有等价字段（如 geo-quality 迁移已加的）复用并在工作记录中说明映射，不要加重复列。

### 2. 处置事件状态机

新包 backend/uavfire/src/main/java/com/yx/uavfire/fc100/operation/（或 com/yx/uavfire/operation/，选择与现有包结构更一致者并说明理由）。

事件状态枚举 OperationIncidentStatus：CANDIDATE, CONFIRMED, DISPATCHING, RESPONDING, RECHECKING, RESOLVED, ARCHIVED, FALSE_ALARM。
- 参考 fc100/mission/service/MissionStateMachine 的既有模式实现 IncidentStateMachine：显式合法迁移表，非法迁移抛业务异常并写审计日志（沿用 FireMissionLogEntity 风格，可新建 operation_incident_log 表或复用通用日志方案，二选一并说明）。

### 3. REST 接口（沿用 fc100 模块 controller/service/mapper 分层与 ApiResult 风格）

- POST /api/operations/incidents —— 由已确认火情创建处置事件：校验 fire_event 存在且已确认；同一 fire_event 不允许重复创建活跃 incident；生成 incident_no；初始状态 CONFIRMED（火情已人工确认才允许创建）。
- GET /api/operations/incidents —— 分页列表，支持 status/level 过滤。
- GET /api/operations/incidents/{id} —— 详情，含 assignments 与时间线。
- GET /api/operations/incidents/{id}/timeline —— 事件时间线（状态变迁 + 分配记录，按时间排序）。
- POST /api/operations/incidents/{id}/assign-monitor —— 分配巡检/复测 M4T（role=MONITOR_PRIMARY 或 MONITOR_RECHECK）。
- POST /api/operations/incidents/{id}/assign-delivery —— 分配 FC100（role=DELIVERY_PRIMARY 或 DELIVERY_BACKUP）；同一 incident 同时只能有一个 DELIVERY_PRIMARY 活跃分配。
- POST /api/operations/incidents/{id}/dispatch —— 状态 CONFIRMED→DISPATCHING→RESPONDING；本任务只做状态流转与基础校验（必须已有 DELIVERY_PRIMARY 分配），合规门禁在 S5 接入，代码中留出清晰的 PreflightGate 扩展点接口（空实现 + TODO 注释标注 S5）。
- POST /api/operations/incidents/{id}/abort —— 任意活跃状态可中止，记录操作人与原因。
- POST /api/operations/incidents/{id}/close —— RESOLVED/中止后归档为 ARCHIVED。

所有写操作记录操作人；dispatch/abort/close 挂 @Idempotent。

### 4. 与 fc100 任务的联动（最小实现）

- incident 创建时如关联 fire_event 已有 fc100_fire_mission 草稿，将 mission 的 incident 关联字段补上（fc100_fire_mission 增加 incident_id 可空列，入迁移）。
- 不实现自动生成任务草稿的编排策略（S6/S7 范围）。

### 5. 测试（不可少）

- IncidentStateMachine：全部合法迁移逐一断言 + 至少 4 个非法迁移拒绝用例。
- Service/Controller 层（沿用既有 Mock 风格测试，如 DeliveryControllerApifoxWorkflowTest 的模式）：创建（含火情未确认拒绝、重复创建拒绝）、分配（DELIVERY_PRIMARY 唯一性）、dispatch 前置校验、abort/close 流转、时间线聚合。
- 运行 mvn -pl uavfire test -Dtest="*Incident*,*Operation*" 全绿；再跑一遍 S1 的三个测试类确认无回归。

### 6. 交付

- 全部验收命令真实输出记录到 work-records/codex/S2-report-20260702.md（改动清单、测试输出、设计取舍说明、遗留问题）。
- 不要 git commit。
- 最终输出：改动文件清单 + 每条验收标准通过情况。

## 禁区

- 不实现资源锁竞争/指令队列执行逻辑（S4）。
- 不实现合规检查规则（S5），只留扩展点。
- 不做前端改动（S3 单独执行）。
- 不修改 FireMissionStatus、S1 释放策略代码、cloud-sdk、rcplus-msdk-agent、ai-service。
- 不提交 node_modules/dist/target/日志。
