# 一期联调验收报告

日期：2026-07-02  
范围：S8 Mock 联调与验收，智能集群巡检灭火一期收官。  
代码改动：仅新增后端测试 `EndToEndMockDrillTest`，未改业务逻辑，未触碰 `cloud-sdk`、`rcplus-msdk-agent`、`ai-service`。

## 1. 演练剧本执行结果

测试类：`backend/uavfire/src/test/java/com/yx/uavfire/fc100/acceptance/EndToEndMockDrillTest.java`

执行命令：

```bash
mvn -pl uavfire -Dtest=EndToEndMockDrillTest test
```

结果：2 tests, 0 failures, 0 errors。

### 正常剧本

| 步骤 | 验收点 | 自动化断言 |
| --- | --- | --- |
| 1 | AI 热成像告警含 PRECISE 坐标、置信度、温度，上报后生成 CANDIDATE | `fire_event.status=CANDIDATE`，`missionCreated=false`，无任务生成，mock 链路耗时断言 `<= 5000ms` |
| 2 | 人工确认火情 | incident 为 `CONFIRMED`；生成 `CREATED` 草稿任务；任务回填 `incident_id` |
| 3 | assign-delivery 分配 FC100 | `DELIVERY_PRIMARY` assignment 为 `ACTIVE`；资源锁 `lease_id=1` |
| 4 | 未录入飞行申请/资质时 dispatch | `PreflightBlockedException`；阻断项包含 `R02`、`R15` |
| 5 | 录入飞行申请、起飞确认、资质档案后重跑预检 | `PreflightResult.blockingItems()` 为空；dispatch 进入 `DISPATCHING`；Delivery task mock 创建、启动，并进入 `RESPONDING` |
| 6 | Mock 到达投放点 | Delivery 状态 `ARRIVED/HOVERING` 映射到 `PAYLOAD_RELEASE_PENDING`；签发 `releaseConfirmationToken` 和过期时间 |
| 7 | 错误/正确令牌释放 | 错误令牌拒绝并写 `PAYLOAD_RELEASE_TOKEN_DENIED`；正确令牌写 `fc100_payload_event`，方式为 `OFFICIAL_HOOK_MANUAL`，任务进入 `RETURNING` |
| 8 | 两轮复测 | 饱和场景下温度下降但面积未解除时 `CONTINUE_RESPONSE` 回 `RESPONDING`；二次复测面积/温度/无明火满足后 `RESOLVED` |
| 9 | close 归档 | incident `ARCHIVED`；任务 `RETURNING -> REVIEWING -> COMPLETED -> ARCHIVED` |
| 10 | 危险动作审计 | `operation_incident_log` 覆盖 `CREATE/ASSIGN_DELIVERY/DISPATCH/RESPOND/START_RECHECK/CONTINUE_RESPONSE/RESOLVE/ARCHIVE`；`fire_mission_log` 覆盖 `CREATE_DELIVERY_TASK/START_DELIVERY/MARK_RELEASE_PENDING/PAYLOAD_RELEASE_TOKEN_DENIED/CONFIRM_RELEASE/MARK_RETURNING/MARK_RETURN_COMPLETED/SUBMIT_REVIEW/ARCHIVE`；急停入队写 `operation_command_event.drone_emergency_stop` |

### 异常剧本

| 场景 | 自动化断言 |
| --- | --- |
| DeliveryHub 连续失败 | 连续 3 次 `createTask` 抛出 `DeliveryHub unreachable` 后，任务进入 `MANUAL_TAKEOVER`，`fire_mission_log.TAKEOVER` 记录待人工接管原因 |
| 待释放超时 | `PAYLOAD_RELEASE_PENDING` 且令牌过期后，`scanReleasePendingTimeouts()` 入队 `return_home`，任务转 `RETURNING`，`fire_mission_log.MARK_RETURNING` 备注 `RELEASE_PENDING_TIMEOUT_AUTO_RETURN` |

## 2. 主方案 §12.2 八条业务验收场景对照

| # | 验收场景 | 验证方式与证据 |
| --- | --- | --- |
| 1 | 巡检发现火点，指挥端 5 秒内出现候选事件 | S8 正常剧本步骤 1：mock 链路从 `fireEventService.create()` 到 CANDIDATE 创建，断言 `<= 5000ms` |
| 2 | 人工确认后生成处置事件和任务草稿 | S8 正常剧本步骤 2：incident `CONFIRMED`，任务 `CREATED`，`incident_id` 回填 |
| 3 | 预检失败禁止派发并展示阻断原因 | S8 正常剧本步骤 4：dispatch 抛 `PreflightBlockedException`，阻断项含 `R02/R15` |
| 4 | 预检通过后创建 Delivery Sync 即时任务 | S8 正常剧本步骤 5：预检无 BLOCK，dispatch 后 mock adapter 创建 `TASK-S8-001` 并启动 |
| 5 | 到点只能进入待释放，不能自动释放 | S8 正常剧本步骤 6：到点后仅 `PAYLOAD_RELEASE_PENDING`，未产生 payload release event，等待令牌确认 |
| 6 | 人工确认释放记录载荷事件和操作人 | S8 正常剧本步骤 7：`fc100_payload_event.RELEASED`，`operatorId=operator-release`，方式 `OFFICIAL_HOOK_MANUAL` |
| 7 | 返航后可发起复测并归档 | S8 正常剧本步骤 8-9：复测 unresolved/ resolved 双路径，最终 incident 和 mission 均归档 |
| 8 | 任意阶段急停/返航/接管有审计记录 | S8 正常剧本急停入队断言 `operation_command_event.drone_emergency_stop`；异常剧本断言接管 `TAKEOVER` 和超时返航 `return_home/MARK_RETURNING` |

## 3. 全量测试统计

### 后端

命令：

```bash
mvn -pl uavfire test
```

结果：384 tests，379 passed，0 failures，5 errors。

本期新增 S8 测试在全量中通过。失败清单为既有环境性问题：

| 测试类 | 数量 | 原因 |
| --- | ---: | --- |
| `HgtTerrainElevationServiceTest` | 4 errors | Windows 临时目录下 `N39E115.hgt` 删除失败 |
| `StreamSplitterServiceTest` | 1 error | ffmpeg 计数文件 `ffmpeg-count.txt` 不存在 |

同时 Maven 仍打印既有 POM 警告：`druid` 传递依赖 `systemPath` 非绝对路径、`hacoud` profile 不存在、`org.jetbrains:annotations` 使用 `LATEST/RELEASE`。

### 前端

命令：

```bash
npm.cmd run test:policies
npm.cmd run build
```

结果：`test:policies` 86 tests 全部通过；`vite build` 成功。首次直接运行 `npm` 被 PowerShell 执行策略拦截 unsigned `npm.ps1`，已改用 `npm.cmd` 执行同一命令。构建存在既有 chunk size warning。

## 4. 风险与遗留问题

1. Delivery Sync 任务创建和状态回传当前为 mock 验证；真实 FC100 即时任务创建、状态字段、到点语义仍需 DJI 书面确认。
2. `OFFICIAL_HOOK_MANUAL` 已作为一期安全默认路径验证；`DELIVERY_SYNC_REMOTE` 远程开钩能力仍待 DJI 书面确认前保持拒绝。
3. UOM 空域/边界当前通过规则接口和 mock checker 验证；自动对接 UOM 平台属于远期集成项。
4. R13 时间/能量预算参数使用工程默认值，外场前需要结合 FC100 满载、风场、航线高度做标定。
5. 当前代码存在编排边界：incident 从 `DISPATCHING` 到 `RESPONDING` 由状态机支持，但缺少明确服务层自动桥接；S8 测试在 Delivery task 启动后显式触发该迁移。
6. 任务从 `SENT_TO_DELIVERY` 到启动阶段的飞手接受/接管边界仍需产品化接口明确；S8 mock 使用 `ACCEPTED_BY_PILOT` 夹具模拟飞手已接受。

## 5. 二期建议

1. 建立真实 Delivery Sync 沙箱联调：创建任务、启动任务、查询任务、设备状态、异常码映射全链路留样。
2. 补齐事件编排服务：Delivery task accepted/start 回调自动推进 incident `RESPONDING`，避免测试或前端自行拼接状态机。
3. 将飞手接受任务、任务重派、人工接管解除做成明确 API 和审计模型。
4. 推进 UOM/空域平台接口化，替换 mock boundary checker，并保留离线降级策略。
5. 外场标定 R13/R06/R07：时间预算、风速限制、载荷重量与返航余量参数。
6. 二期再评估 DJI 远程开钩、PSDK 释放或保持官方遥控器留证路径，并将确认材料归档到验收包。
