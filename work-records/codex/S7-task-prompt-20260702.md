# 任务 S7：FC100 灭火闭环强化（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。已完成：S1 释放安全边界、S2/S2.1 事件编排、S3 前端工作台、S4 锁与队列、S5 合规门禁、S6 巡检闭环（火情确认→incident→草稿）。

## 目标

打通灭火任务"草稿→审批→派发→到点→待释放→OFFICIAL_HOOK_MANUAL 人工释放留证→返航→复测→归档"全链路，强化 Delivery Sync 适配的异常与降级，收紧 S1 遗留的两个安全观察项。

安全红线：
- FireMissionStatus 20 态仍为唯一事实源，不改枚举。
- 释放默认 MANUAL_CONFIRM + OFFICIAL_HOOK_MANUAL：到点后系统提示飞手用遥控器开钩，操作员在系统"释放已确认"留证；DELIVERY_SYNC_REMOTE 保持"能力未确认"拒绝。
- 不碰 cloud-sdk、rcplus-msdk-agent、ai-service。
- 所有危险指令仍经 S4 队列且先过 PayloadReleasePolicyService。

## 工作内容

### 1. Delivery Sync 适配强化（deliverysync 包）

- 即时任务创建/启动链路查缺补漏：状态轮询与（若已有）回调事件消化路径统一收敛任务状态映射（Delivery 任务状态 -> FireMissionStatus 迁移事件），映射表集中一处并有测试。
- DeliveryHub 不可达降级：创建/启动/轮询连续失败（阈值可配置）时任务进入 MANUAL_TAKEOVER，写审计与"待人工接管"原因；恢复后人工决定续跑或中止。
- Mock adapter 补齐同等行为，保证无真实账号可全链路联调。

### 2. 待释放超时策略（评审 P0-R3 落地的第二半）

- 任务进入 PAYLOAD_RELEASE_PENDING 后启动超时计时（fc100.release.pending-timeout，默认 5 分钟，注释说明依据：FC100 满载悬停约 12 分钟，超时须留返航余量）。
- 超时未人工确认：自动触发返航（经 S4 队列），任务状态按既有事件走 RETURNING，审计记录"RELEASE_PENDING_TIMEOUT_AUTO_RETURN"。
- 超时自动返航是安全动作（不是释放），不需要 CONTROLLED_TEST_AUTO 开关，但必须可配置禁用（禁用时只告警不动作）。

### 3. 收紧 S1 遗留观察项

- releaseHook / confirmRelease 增加任务状态门禁：仅 PAYLOAD_RELEASE_PENDING 状态可释放（DRY_RUN 亦同），其余状态返回业务错误并审计。
- confirmationToken 加固：确认令牌由系统签发（进入 PAYLOAD_RELEASE_PENDING 时生成、随任务详情下发、一次性、有效期=pending-timeout），释放请求必须携带匹配令牌；仅 confirmedRelease=true 不再足够。兼容说明写入报告。

### 4. 释放留证与归档链路

- OFFICIAL_HOOK_MANUAL 释放确认后：写 fc100_payload_event（操作人、时间、方式=OFFICIAL_HOOK_MANUAL、遥控器开钩备注字段）、任务进入 PAYLOAD_RELEASED -> RETURNING。
- 返航完成后联动 incident：RESPONDING -> START_RECHECK 建议（不自动派复测机，生成复测待办即可）。
- S6 的 recheck-result RESOLVED 后：任务 REVIEWING -> COMPLETED -> 可 ARCHIVED，incident RESOLVED -> ARCHIVED（close 接口既有）。
- 全链路时间线在 incident timeline 中可见（任务状态变迁映射为时间线项）。

### 5. 前端

- 工作台"确认释放"按钮从占位变为真实：仅 PAYLOAD_RELEASE_PENDING 可见，点击弹二次确认（展示令牌流程与后果文案），调用释放确认接口。
- 待释放倒计时展示（pending-timeout 剩余时间）与超时自动返航提示。
- 任务状态时间线接入 incident 详情。
- 更新 operation-policy.mjs 策略与测试。

### 6. 测试

- 状态映射表全覆盖测试；降级转 MANUAL_TAKEOVER 测试（连续失败阈值）。
- 超时自动返航：到时触发返航指令入队 + 审计；禁用配置时只告警。
- 状态门禁：非 PAYLOAD_RELEASE_PENDING 释放被拒并审计。
- 令牌：无令牌/错令牌/过期令牌/重放（二次使用）全部拒绝；正确令牌放行并写载荷事件。
- DRY_RUN 全链路不发真实指令回归。
- 运行：mvn -pl uavfire test -Dtest="*Fire*,*Incident*,*Operation*,*Preflight*,*Compliance*,*Lease*,*Command*,*Payload*,*Delivery*" 全绿；前端 test:policies 全绿 + build 通过。

### 7. 交付

- 真实输出记录到 work-records/codex/S7-report-20260702.md。
- 不 git commit。

## 禁区

- 不实现 DELIVERY_SYNC_REMOTE 真实释放（保持能力未确认拒绝）。
- 不做 PSDK 集成（二期）。
- 不改 S5 规则引擎语义（只消费其结果）。
- 不迁移 wayline agent 指令。
