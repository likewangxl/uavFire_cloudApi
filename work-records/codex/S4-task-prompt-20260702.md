# 任务 S4：资源锁与指令审计队列（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。已完成：S1（release_policy 安全边界）、S2/S2.1（operation 事件编排层，operation_resource_lease 与 operation_command_event 表已建、实体已有，但无业务逻辑）、S3（前端工作台，本任务不碰前端）。

## 目标

多机并发安全（资源锁）+ 全部危险指令可追溯可重试（持久化指令队列）。这是外场联调的地基。

安全红线：
- 不修改 FireMissionStatus、IncidentTransitionTable 既有迁移语义。
- 不碰 S1 释放策略校验、cloud-sdk、rcplus-msdk-agent、ai-service、frontend。
- 不引入新的自动释放路径。

## 工作内容

### 1. 资源锁服务（operation/lease/）

基于既有 operation_resource_lease 表实现 ResourceLeaseService：
- acquire(resourceSn, leaseType, ownerType, ownerId, ttl)：同一 resource_sn 同时只能有一个 ACTIVE lease。并发竞争用数据库层保证（唯一索引或 UPDATE ... WHERE status 乐观锁，说明选型理由；如需加唯一索引，出迁移 SQL 并同步 fc100_init.sql）。
- renew（心跳续约）、release（释放）、expireStale（心跳超时自动过期，接入既有调度器模式，参考 GlobalScheduleService）。
- 与 operation_assignment 打通：assign-monitor / assign-delivery 成功时自动 acquire 并回填 lease_id；释放分配时同步 release；acquire 失败则分配失败并返回资源冲突错误。
- 约束：同一火情（incident）只能有一个 DELIVERY_PRIMARY 的 ACTIVE lease（S2 已有 assignment 层唯一性，这里加 lease 层兜底）。

### 2. 指令队列（operation/command/）

基于既有 operation_command_event 表实现 CommandQueueService：
- enqueue(targetSn, commandType, payload, idempotencyKey, operator)：幂等键去重（同 key 返回已有指令，不产生第二条）。
- 状态机：PENDING -> SENDING -> WAIT_ACK -> ACKED / FAILED / TIMEOUT；TIMEOUT/FAILED 可重试（retry_count 上限可配置，默认 3），超限进入 DEAD 并要求人工接管。
- 执行器接口 CommandExecutor（按 commandType 分发），本任务先实现两个执行器：
  a) DeliverySyncCommandExecutor：把现有 DeliveryController 中直接调 adapter.sendDeviceCommand 的危险指令（release-hook、急停/返航/降落/接管类 device command）改为经队列派发（enqueue 后由队列驱动执行并等待 ack）。释放类指令执行前仍必须先过 PayloadReleasePolicyService（红线，不得重排顺序）。
  b) MockCommandExecutor：测试用。
- ack 回填：Delivery Sync 的命令状态查询/回调更新 ack_at 与状态。
- 查询接口：GET /api/operations/commands?targetSn=&missionNo=（审计回放用，分页）。

### 3. 调度与超时

- 队列驱动用既有调度器模式（避免引入新框架）；发送超时、ack 超时时长走 application.yml 配置项（fc100.command-queue.*，给默认值）。
- 设备断连场景：指令进入 TIMEOUT 后写审计并可查询到"待人工接管"清单。

### 4. 测试（必须）

- 并发抢锁：两线程同时 acquire 同一 SN 仅一个成功（真实多线程用例）。
- 心跳过期：expireStale 后可重新 acquire。
- 幂等：同 idempotency_key 两次 enqueue 只有一条指令。
- 重试与 DEAD：模拟执行器失败，断言 retry_count 递增至上限进入 DEAD。
- 释放指令经队列仍被 MANUAL_CONFIRM 策略拒绝（红线回归）。
- assign-delivery 在锁被占用时返回资源冲突。
- 运行：mvn -pl uavfire test -Dtest="*Lease*,*Command*,*Incident*,*Operation*" 全绿；S1 三个测试类回归全绿。

### 5. 交付

- 真实输出记录到 work-records/codex/S4-report-20260702.md（含选型说明：锁的并发保证方案、队列驱动方式）。
- 不 git commit。

## 禁区

- 不做合规预检规则（S5）。
- 不改前端。
- 不动 MQTT/cloud-sdk 协议层。
- 航线派发类指令（wayline agent）本期不迁入队列，只留 TODO 注释（避免范围扩散）。
