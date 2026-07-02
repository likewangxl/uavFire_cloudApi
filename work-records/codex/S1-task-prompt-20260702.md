# 任务 S1：基线与安全边界（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。请先阅读根目录 README.md 和 backend/uavfire 模块结构，再开始实现。

## 背景

本仓库已具备：fc100 后端模块（backend/uavfire/src/main/java/com/yx/uavfire/fc100/，含 mission 状态机 FireMissionStatus 20 态、deliverysync Http/Mock 双适配器、Fc100IdempotencyAspect 幂等切面、fc100_init.sql 8 张表）、Vue3 前端（frontend/，含 .mjs 策略测试）、FastAPI AI 服务（ai-service/）。

安全红线（不可违反）：
- 不得引入任何自动释放路径。
- FireMissionStatus 枚举为状态机唯一事实源，本任务不得修改它。
- 危险指令必须幂等、写审计、记录操作人。
- 不改动 backend/cloud-sdk 模块。

## 工作内容

### 1. 后端：release_policy 落地

- 新增迁移 SQL 到 backend/sql/migrations/，文件名沿用日期前缀约定（如 2026-07-02-fc100-mission-release-policy.sql）：为 fc100_fire_mission 表增加 `release_policy` VARCHAR(32) NOT NULL DEFAULT 'MANUAL_CONFIRM' 和 `release_execution_mode` VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL_HOOK_MANUAL' 两个字段；同步更新 backend/uavfire/sql/fc100_init.sql 中的建表语句。
- 新增枚举：ReleasePolicy { MANUAL_CONFIRM, DRY_RUN, CONTROLLED_TEST_AUTO }；ReleaseExecutionMode { OFFICIAL_HOOK_MANUAL, DELIVERY_SYNC_REMOTE, PSDK_RELEASE }。放在 fc100/mission/model/enums/ 下。
- 实体、DTO、Mapper、创建任务的 service 同步支持这两个字段，创建任务时未显式指定则取默认值。

### 2. 后端：释放接口硬校验

找到现有释放挂钩相关接口（fc100/payload/ 下 PayloadController 及对应 service）：
- release_policy = MANUAL_CONFIRM 时：请求必须携带操作人确认信息（操作人标识 + 确认令牌/确认标记），缺失则返回 4xx 业务错误码（沿用 Fc100ErrorCode 风格新增错误码），并且该拒绝也要写入任务日志。
- release_policy = DRY_RUN 时：不发送真实释放指令，只记录演练事件。
- release_policy = CONTROLLED_TEST_AUTO 时：还需要系统配置开关（application.yml 中新增 fc100.release.controlled-test-auto-enabled，默认 false）同时打开才放行，否则拒绝。
- release_execution_mode = DELIVERY_SYNC_REMOTE 时：因 DJI 尚未书面确认该接口能力，直接返回"能力未确认"业务错误（保留代码扩展点，注释说明依据）。
- 以上全部路径必须记录操作人与结果到既有任务日志表（fc100_mission_log）。

### 3. 前端：测试基线修复

- 找到 frontend 下全部 .mjs 策略测试（frontend/src/pages/page-web/projects/*.mjs 及 __tests__），确认它们的运行方式（查 package.json scripts）。
- 逐个运行，修复因代码演进导致的漂移失败（只修测试与被测约定不一致处，不得为了通过而删除断言）。
- 若没有统一入口，新增 npm script（如 test:policies）一次性跑全部策略测试。
- 运行 npm run lint（如超时可跳过并在报告注明）。

### 4. 基线验证

- 后端：运行 mvn -pl uavfire -DskipTests compile 确认编译通过；运行 uavfire 模块测试（mvn -pl uavfire test），记录通过/失败清单；为本任务新增功能补测试：a) 新建任务默认 release_policy=MANUAL_CONFIRM；b) 无操作人确认时释放接口拒绝；c) CONTROLLED_TEST_AUTO 在开关关闭时拒绝；d) DELIVERY_SYNC_REMOTE 返回能力未确认。
- 前端：npm run build 或至少 vite build 可通过（如依赖未安装先 npm install）。

### 5. 交付

- 所有验收命令的真实输出记录到 work-records/codex/S1-baseline-report-20260702.md（基线验证报告：改动清单、测试输出摘要、通过/失败基线、遗留问题）。
- 不要 git commit，保留工作区改动供审查。
- 最终输出：改动文件清单 + 每个验收标准的通过情况。

### 2.1 重点：封死既有自动释放路径（上轮侦察发现）

代码中已存在 `DeliveryController.releaseHook` 远程释放入口和 `autoReleaseHookWhenDeliveryReady` 自动释放逻辑。这是本卡最高优先级安全项：
- `autoReleaseHookWhenDeliveryReady` 自动释放必须默认关闭：仅当 release_policy = CONTROLLED_TEST_AUTO 且 fc100.release.controlled-test-auto-enabled=true 双闸门同时满足才允许，否则该路径直接跳过并写日志说明被策略阻止。
- `DeliveryController.releaseHook` 与 PayloadController 的释放入口执行同一套硬校验（操作人确认、策略校验、审计），不能留旁路。

## 禁区

- 不新增 operation_* 相关表（属后续任务 S2）。
- 不修改 FireMissionStatus 枚举。
- 不改 cloud-sdk、rcplus-msdk-agent、ai-service。
- 不提交 node_modules/dist/target/日志文件。
- 不做与本卡无关的重构。
