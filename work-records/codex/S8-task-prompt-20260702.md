# 任务 S8：Mock 联调与验收（智能集群巡检灭火 一期收官）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。S1~S7 已全部完成并提交：释放安全边界、事件编排、前端工作台、锁与队列、合规门禁（R01-R15）、巡检闭环、灭火闭环（令牌、超时返航、降级）。

## 目标

形成"可评审、可演示"的验收版本：端到端 mock 联调脚本 + 主方案 §12.2 八条业务验收场景逐条验证 + 联调报告。

安全红线：不改任何业务逻辑语义（只允许为可测性做最小的测试基建改动，如测试配置、mock 数据构造器）；不碰 cloud-sdk、rcplus-msdk-agent、ai-service；不 git commit。

## 工作内容

### 1. 端到端 Mock 联调测试（后端集成测试类 EndToEndMockDrillTest，或等价组织）

用 Mock DeliverySyncAdapter + 内存/H2 或既有测试基建（沿用现有测试模式，不引入新框架），编写一个完整"演练剧本"集成测试，按顺序驱动并断言每一步：

1. 模拟 AI 上报热成像告警（含 PRECISE 坐标、置信度、温度）→ 产生 CANDIDATE 火情，无任务被创建。
2. 操作员确认火情 → incident 创建（CONFIRMED）、CREATED 草稿任务生成并回填 incident_id。
3. assign-delivery 分配 FC100（锁获取成功）。
4. 未录入飞行申请/资质档案时 dispatch → 被 S5 预检 BLOCK，断言阻断项包含 R02/R15。
5. 录入合规记录（飞行申请、资质档案、操作员确认等）后重跑预检 → PASS，dispatch 成功进入 DISPATCHING→RESPONDING，Delivery 任务被 Mock adapter 创建并启动。
6. Mock 任务到点 → 任务 PAYLOAD_RELEASE_PENDING，释放令牌已签发。
7. 错误令牌释放 → 拒绝并审计；正确令牌 + 操作人确认 → OFFICIAL_HOOK_MANUAL 留证、payload_event 落库、任务 RETURNING。
8. 提交复测结果（未解除场景）→ incident CONTINUE_RESPONSE 回 RESPONDING；再次复测（解除，含饱和判定路径）→ RESOLVED。
9. close 归档 → incident ARCHIVED，任务归档态。
10. 全程断言：每个危险动作在 operation_command_event / fire_mission_log 有审计（按 action 清单断言）。

另写一个"异常剧本"测试：DeliveryHub 连续失败 → MANUAL_TAKEOVER；待释放超时 → 自动返航 + 审计。

### 2. §12.2 八条业务验收场景对照

对主方案 §12.2 逐条给出验证方式与证据（自动化断言归属上述测试的哪一步，或引用既有单测），输出对照表：
1. 巡检发现火点，指挥端 5 秒内出现候选事件（mock 链路时延断言或说明）。
2. 人工确认后生成处置事件和任务草稿。
3. 预检失败禁止派发并展示阻断原因。
4. 预检通过后创建 Delivery Sync 即时任务。
5. 到点只能进入待释放，不能自动释放。
6. 人工确认释放记录载荷事件和操作人。
7. 返航后可发起复测并归档。
8. 任意阶段急停/返航/接管有审计记录（补一个急停入队审计断言）。

### 3. 全量回归与交付

- 运行后端全量：mvn -pl uavfire test（既有 HGT/ffmpeg 环境性失败如仍存在，单独列出说明非本期引入）。
- 前端：npm run test:policies + npm run build。
- 输出《一期联调验收报告》到 work-records/codex/S8-acceptance-report-20260702.md：
  - 演练剧本执行结果（含每步断言）
  - §12.2 八条对照表
  - 全量测试统计（通过/既有环境性失败清单）
  - 风险与遗留问题清单（含：Delivery Sync 任务创建/远程开钩待 DJI 书面确认、UOM 自动对接远期、R13 参数需外场标定等）
  - 二期建议（对齐主方案 §15）
- 不 git commit。

## 禁区

- 不改业务语义、不为通过测试而放宽任何校验。
- 不做真实外部调用。
- 不动前端组件逻辑（前端只跑既有测试与构建）。
