# 任务 S9：工作台处置链路前端接线（紧急补全，仅前端）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。背景：S1~S8 已完成，后端全链路可用（确认火情已能自动建 incident + CREATED 草稿任务），但工作台前端在"已确认"之后断链：无分配入口、预检/生成任务按钮还是 S3 占位灰态、合规记录无录入界面。本任务只改 frontend/。

实测现状（真实环境验证过）：
- POST /api/operations/incidents/{id}/assign-delivery、assign-monitor 可用（body: resource_sn, role, operator_id 之类，以后端 AssignOperationResourceParam 为准，先读源码）。
- POST /api/compliance/preflight-checks 可运行预检并返回结构化 PASS/WARN/BLOCK 明细；dispatch 被阻断时错误响应 data 里也带同结构。
- 合规留证接口：record-flight-application / record-takeoff-confirmation / record-landing-report / qualifications（POST/GET）。
- incident 列表/详情已带 mission_no、mission_status；但详情的坐标质量/置信度/热成像温度对真实 incident 显示"未知/-"（候选事件才有），需从关联 fire_event 补数据。

## 工作内容（全部在 frontend/）

1. **分配入口**：详情面板操作栏新增"分配 FC100"和"分配巡检机"按钮（CONFIRMED 状态可见可用）。弹窗：设备 SN 输入框（如后端有设备列表接口就下拉，没有就文本输入+最近使用记忆）、角色选择（DELIVERY_PRIMARY/DELIVERY_BACKUP 或 MONITOR_PRIMARY/MONITOR_RECHECK）。成功后刷新 assignments，DISPATCH 按钮随 hasActiveDeliveryPrimary 自动亮起（策略已有）。资源冲突错误要 toast 出后端 message。
2. **运行预检启用**：解除占位灰态，调 preflight-checks，结果渲染在 CompliancePanel：总体结论 + 每条规则（R01-R15）的 PASS/WARN/BLOCK 徽标与原因；BLOCK 项醒目。dispatch 被阻断时同样渲染返回的阻断明细。
3. **生成灭火任务按钮**：incident 已有 mission（mission_no 非空）时显示"任务 {mission_no}（{状态}）"信息而不是灰按钮；确无任务且火情 PRECISE 时才可点（调后端既有生成接口，如无独立接口则隐藏并显示"确认时自动生成"说明——先查后端有没有独立生成接口，没有就不要造）。
4. **合规录入最小界面**：CompliancePanel 增加"录入合规记录"折叠区：四个最小表单（飞行申请：申请单号/批复文号/有效期；起飞确认；落地报告；资质档案：类型下拉四类/编号/发证机构/生效失效日期）。提交调 record-* 与 qualifications 接口。目的：让 R02/R15 能在演示中变绿。
5. **详情数据补全**：真实 incident 详情从关联 fire_event（详情接口若已带就直接用，没带就前端按 fire_event_id 拉一次）回填坐标质量/置信度/热成像温度/饱和警示。
6. **策略与测试**：operation-policy.mjs 更新（ASSIGN_DELIVERY/ASSIGN_MONITOR 动作、RUN_PREFLIGHT 启用条件、GENERATE_MISSION 三态逻辑），operation-workbench-policy.test.mjs 同步补断言。npm run test:policies 全绿 + npm run build 通过。

## 验收剧本（写进报告）

CONFIRMED 事件 → 分配 FC100(输入 SN) → 运行预检(看到 R02/R15 BLOCK) → 录入飞行申请+四类资质 → 重跑预检(减少 BLOCK；设备在线等 mock 相关项如仍 BLOCK 属预期，注明) → DISPATCH 按钮状态变化。

## 禁区

- 不改 backend 任何文件（发现后端缺口写进报告，不要自己加接口）。
- 不改 S1-S7 既有策略语义（只增不删）。
- 不 git commit。交付 work-records/codex/S9-report-20260702.md。
