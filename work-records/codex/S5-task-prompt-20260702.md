# 任务 S5：安全合规门禁引擎（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。已完成：S1（release_policy 安全边界）、S2/S2.1（operation 事件编排 + PreflightGate 空扩展点）、S3（前端工作台，本任务不碰前端）、S4（资源锁 + 指令队列）。

## 目标

所有派发前强制预检：实现预检规则引擎，替换 PreflightGate.NoopPreflightGate；合规记录留证落库。

安全红线：
- 不修改 FireMissionStatus、IncidentTransitionTable、S1 释放策略、S4 锁与队列语义。
- 不碰 cloud-sdk、rcplus-msdk-agent、ai-service、frontend。
- 合规接口是"人工报备结果的记录留证"，不是自动对接 UOM——接口命名必须体现 record 语义，注释明确说明。

## 工作内容

### 1. 预检规则引擎（operation/preflight/）

- PreflightRule 接口：id、description、check(context) -> PASS / BLOCK(原因) / WARN(提示)。
- PreflightContext：incident、fc100 任务草稿、DELIVERY_PRIMARY 分配、设备可用信息（尽量复用既有 DeliverySyncAdapter 的设备属性查询；mock 环境可注入）。
- 规则清单（每条独立类或独立注册，可配置启用/禁用，fc100.preflight.rules.* 配置）：
  R01 火情未人工确认（fire_event.confirmed_status != CONFIRMED）→ BLOCK
  R02 空域未确认/审批缺失（无有效 flight-application 记录）→ BLOCK
  R03 涉及投放但无投放审批记录 → BLOCK
  R04 FC100 离线或未接入 Delivery Sync → BLOCK
  R05 电量不足（阈值 fc100.preflight.min-battery-pct，默认 30）→ BLOCK
  R06 风速超限（阈值 fc100.preflight.max-wind-mps，默认 12；数据缺失时 WARN）→ BLOCK/WARN
  R07 载荷超限（按双电 85kg 含吊具口径，净载荷阈值可配置）→ BLOCK
  R08 RTK/定位质量不足（fire_event.location_quality 非 PRECISE 时禁止生成可派发投放任务）→ BLOCK
  R09 起降点/投放点/航线越界（有 UOM 参考层数据时校验，缺失时 WARN）→ BLOCK/WARN
  R10 操作员未确认 → BLOCK
  R11 资源锁冲突（复用 S4 lease 状态）→ BLOCK
  R12 指令队列存在未完成危险指令（S4 队列中该 SN 有 PENDING/SENDING/WAIT_ACK 危险指令）→ BLOCK
  R13 任务时间/能量预算：去程+悬停余量+返航+安全冗余 vs 续航。简化模型：距离/速度估算飞行时间，悬停预算默认 12 分钟上限的可配置比例，参数全部 fc100.preflight.time-budget.* 可配置 → BLOCK
  R14 DeliveryHub 连通性探测（adapter 健康检查，mock 可注入）→ BLOCK
  R15 运行资质档案有效性（见第 3 节，档案缺失或过期）→ BLOCK
- 引擎输出 PreflightResult：整体 PASS/BLOCK + 每条规则结果明细，落库 operation_compliance_record（S2 已建表？先确认，若未建表则出迁移；blocking_items_json 存明细）。

### 2. 接入 dispatch

- OperationIncidentServiceImpl.dispatch 中把 NoopPreflightGate 替换为真实引擎；BLOCK 时拒绝派发并返回阻断项列表（结构化，前端可直接渲染）。
- 保留 Noop 实现供测试注入。

### 3. 合规留证接口（operation/compliance/）

- POST /api/compliance/preflight-checks：对指定 incident 手动运行预检（不派发），返回并落库结果。
- GET /api/compliance/preflight-checks/{id}：查询预检结果。
- POST /api/compliance/record-flight-application：记录飞行活动申请（申请单号、批复文号、有效期、材料 URL）。
- POST /api/compliance/record-takeoff-confirmation：记录起飞确认。
- POST /api/compliance/record-landing-report：记录落地报告。
- 运行资质档案 CRUD：POST/GET /api/compliance/qualifications（类型：CLUSTER_FLIGHT_PERMIT 集群飞行许可 / AIRDROP_APPROVAL 空投批准 / AIRWORTHINESS 审定状态 / JOINT_OPERATION_AGREEMENT 联合运行协议；字段：编号、发证机构、生效/失效日期、材料 URL、状态）。新表出迁移 SQL 并同步 fc100_init.sql。
- 全部记录操作人。

### 4. 测试（必须）

- 每条规则（R01-R15）至少一个 BLOCK 正用例 + 一个 PASS 反用例（数据缺失类规则加 WARN 用例）。
- dispatch 集成：预检 BLOCK 时 dispatch 拒绝且状态不变；全 PASS 时正常进入 DISPATCHING。
- 资质档案过期边界用例。
- 运行 mvn -pl uavfire test -Dtest="*Preflight*,*Compliance*,*Incident*,*Operation*,*Lease*,*Command*" 全绿；S1 回归三类全绿。

### 5. 交付

- 真实输出记录到 work-records/codex/S5-report-20260702.md。
- 不 git commit。

## 禁区

- 不自动调用任何 UOM/USS 外部接口（本地记录留证 only）。
- 不改前端（S5 前端展示由后续任务接入）。
- 不动 S4 队列执行器逻辑（只读取队列状态做 R12）。
