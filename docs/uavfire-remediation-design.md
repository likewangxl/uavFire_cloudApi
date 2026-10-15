# uavFire 安全与任务链路整改设计

本文对照 `main` 源码（并吸收草稿 PR #1 的审批断链修复）给出可落地的改造方案。实现以本文为准：先改行为，再补测试，不把真实司运 / 真实飞行器调用当成默认路径。

## 1. 目标与非目标

### 目标

- 试用授权从三端硬编码截止日，改成可配置的许可证策略。开发 / 测试默认能启动；生产必须显式配置截止时间，或显式声明不限期。历史截止日 `2026-10-01 00:00 Asia/Shanghai` 保留为 `mode=until` 的默认值，不静默删除。
- 审批只有在安全检查通过之后才写成 `APPROVED`。检查失败不改状态、不写审批字段、不写审批日志。
- 登录：演示登录默认关闭；口令可验证哈希；JWT 密钥来自环境变量，生产拒绝空密钥和示例密钥 `CloudApiSample`。
- MSDK 的 state / poll / ack 必须携带设备凭证。命令队列落到 Redis，进程重启后记录还在。
- 任务操作者与角色取自登录票据，不再信任请求体里的 `operatorId`。`force-fail` 不再把调用者标成 `ADMIN`。四眼默认开启。
- 自定义设备命令不能绕过开钩策略。
- 司运创建任务先写本地意图，外部成功后再推进状态；外部成功但本地提交失败时留下可重试的补偿记录。默认仍是 mock，生产拒绝悄悄使用 mock。
- `UNLOCATED` 不再把飞机坐标当成火点展示。`ACCEPTED_BY_PILOT` 补上入边。PR #1 的 `CREATED → WAITING_REVIEW / APPROVE` 一并落地。
- Surefire 固定到能发现 JUnit 5 的版本。Lombok 升到可在 JDK 21 上编译 Java 11 目标的版本。

### 非目标

- 不接入真实司运 HTTP，不改 DJI 开放平台协议，不在本变更里飞真机。
- 不引入 Spring Security 过滤器链，继续用现有 `AuthInterceptor`。
- 不改 MQTT、直播、小程序微信登录。
- 不把历史明文口令批量离线重写。登录成功时顺带升级为哈希。
- 不把 MSDK 遥测状态做成持久化。位置和电量由 Agent 周期上报，重启后下一拍会重新写入。
- 不实现完整许可证签名服务器。本期是配置 / 环境变量许可证，预留字段，不做非对称验签。

## 2. 路线图

| 阶段 | 范围 | 验收 |
| --- | --- | --- |
| P0 | 试用可配置；审批事务；登录（demo、口令哈希、JWT）；MSDK 凭证 + Redis 命令；操作者来自票据；四眼；开钩不可绕过；司运本地意图；`UNLOCATED`；`ACCEPTED_BY_PILOT`；审批断链；Surefire | 对应单元测试通过；默认配置下后端上下文能启动 |
| P1 | 生产拒绝司运 mock；UOM 缺失在正式配置里阻断预检；JDK 21 可用的 Lombok | 配置开关有测试；开发用 `new PreflightProperties()` 的演练仍按原警告通过 |
| P2 | 不在本次改代码 | 许可证签名、司运取消接口、口令离线迁移、MSDK 命令派发窗口产品化、全量角色模型 |

## 3. 分项方案

### 3.1 试用授权（P0，致命）

现状：

- 后端 `TrialExpirationPolicy.EXPIRES_AT = 2026-09-30T16:00:00Z`。`TrialExpirationGuard` 在此之后拒绝创建 Spring 容器，`TrialExpirationFilter` 回 403。今天已过截止日，`SpringContextSmokeTest` 无法启动。
- 指挥端 `frontend/src/trial/trial-expiration-policy.mjs` 写死同一时刻，`App.vue` 到期后不渲染路由。
- Agent `app/build.gradle.kts` 把 `1790784000000` 写进 `BuildConfig`，`TrialExpirationPolicy` 只和这个常数比较。

目标行为：

- `off`：不按时间截止。非生产默认。生产若选 `off`，必须同时 `allow-unlimited=true`，否则拒绝启动。
- `until`：到达 `expires-at` 后，后端拒绝启动并拦截 HTTP，指挥端全屏停用，Agent 停止业务循环。未改日期时，默认仍是 `2026-10-01 00:00 Asia/Shanghai`。因此只把模式改成 `until`、不改日期，系统仍然关闭。这不是把死线删掉。

配置：

```yaml
uavfire:
  trial:
    mode: ${UAVFIRE_TRIAL_MODE:off}          # off | until
    expires-at: ${UAVFIRE_TRIAL_EXPIRES_AT:2026-10-01T00:00:00+08:00}
    allow-unlimited: ${UAVFIRE_TRIAL_ALLOW_UNLIMITED:false}
```

生产判定：Spring profile `prod` / `production`，或环境变量 `UAVFIRE_ENV=production`。

接口：`GET /manage/api/v1/trial-status` 无需登录，返回 `mode`、`expiresAt`、`expired`、`display`。指挥端启动时读取。生产构建在接口失败时停用界面；开发构建在接口失败时允许进入，避免本地没起后端就被锁屏。

Agent：

- `uavfire.trial.mode` 默认 `off`（debug 可启动）。
- `uavfire.trial.expiresAtEpochMs` 默认仍为 `1790784000000`。
- `uavfire.trial.allowUnlimited` 默认 `false`。
- release 构建在 `mode=off` 且未允许不限期时视为已到期，避免正式包装机后永久可用。

迁移：已过期的旧包行为不变，直到运维显式配置。回滚：把 `UAVFIRE_TRIAL_MODE=until` 且日期保持原值，即回到硬截止。

测试：原有“截止前可用、截止后拒绝”仍覆盖 `until` + 历史时刻；新增 `off` 在非生产不过期、生产 `off` 且未显式允许时拒绝启动。

### 3.2 审批事务（P0，高）

现状：`FireMissionController.approve` 先 `sm.transit(APPROVE)`，状态和风速等字段已经提交，然后再跑 `BEFORE_APPROVE`。检查失败抛 `SAFETY_CHECK_FAILED`，任务仍是 `APPROVED`。

目标：用内存副本预演。把本次审批参数和审批人叠到任务副本上，先 `SafetyCheckService.check(phase, entity)`。未通过则抛错，数据库保持原状态。通过后才 `transit`。

接口不变：`POST /api/fire/missions/{no}/approve`。

兼容：同时接受 `WAITING_REVIEW` 和历史 `CREATED`（见 3.9）。新草稿直接写 `WAITING_REVIEW`。

回滚：恢复“先 transit 再检查”会重新出现脏状态，不要回滚到那个顺序。

测试：风速超限时 mapper 的更新和状态机 transit 都不被调用；通过时才 transit。

### 3.3 登录安全（P0，高）

现状：

- `POST /manage/api/v1/demo-login` 在拦截器白名单里，无口令签发 `adminPC` 的 Web 票据。
- `manage_user.password` 明文，`userLogin` 用 `String.equals`。
- `jwt.secret` 与代码默认值都是 `CloudApiSample`。

目标：

- `uavfire.auth.demo-login-enabled` 默认 `false`。关闭时接口回 403，不查用户。指挥端按钮仅在 `VITE_DEMO_LOGIN=true` 时渲染，后端仍要打开开关才签发票据。
- 口令：`{bcrypt}` 前缀 + Spring Security Crypto 的 BCrypt。无前缀的旧口令仍可登录，成功后立刻写回哈希。不提供注册接口，种子数据保持原样直到首次登录。
- `jwt.secret=${JWT_SECRET:}`。空或 `CloudApiSample`：生产直接拒绝启动。非生产遇到示例密钥也拒绝，除非 `jwt.allow-insecure-secret=true`。非生产且密钥为空时，为本进程生成临时密钥并打警告，重启后旧票据失效。

回滚：打开 demo 开关、允许 insecure secret，只能用于本地排障。

测试：关闭 demo 不访问数据库；明文登录会触发密码更新；生产空密钥 / 示例密钥启动失败。

### 3.4 MSDK 设备凭证与命令持久化（P0，高）

现状：`/msdk/devices/state|commands/poll|commands/ack` 排除了登录拦截器，Agent 不带头。命令在 `ConcurrentHashMap` + `ConcurrentLinkedQueue`，重启即丢。15 秒未派发则标 `EXPIRED`，避免过期起飞指令被补执行。

目标：

- 请求头 `X-Msdk-Device-Token`，值等于 `UAVFIRE_MSDK_DEVICE_TOKEN`。未配置或不相等回 401。比较前做 SHA-256，避免按长度提前返回。
- 遥测状态仍放内存（15 秒在线窗口）。命令写入 Redis：`msdk:cmd:{id}` 保存 JSON（7 天），`msdk:cmdq:{aircraftSn}` 用列表保证弹出原子性。
- 派发时限默认仍是 15 秒，可用 `uavfire.msdk.command-dispatch-ttl-ms` 加长。重启后记录还在；超过时限的命令保留为 `EXPIRED`，不会补飞。这是故意的飞行安全边界。

Agent：`agentMsdkDeviceToken` gradle 属性写入 `BuildConfig`，OkHttp 在非空时附带请求头。后端未配置令牌时，这些接口保持关闭。

回滚：清空令牌会使设备通道关闭，而不是退回匿名。不要为了回滚重新放开匿名。

测试：错误令牌 401；同一存储交给新的服务实例后仍能 poll；超过 15 秒的既有用例仍标 `EXPIRED`。

### 3.5 操作者、角色与四眼（P0，高）

现状：任务、投放接口的 `operatorId` 来自 JSON。`force-fail` 无条件 `operatorRole("ADMIN")`。`fc100.safety.four-eyes-enabled` 默认 false，而且没有任何代码读取它。票据里只有 `id`、`username`、`user_type`、`workspace_id`，没有管理员角色列。

目标：

- 请求里已有 `customClaim` 时，操作者固定为票据 `username`，角色按下面规则计算，忽略请求体。没有票据时保留请求体，只为现有单测和内部调用留退路；这些 HTTP 路径本身仍走登录拦截器。
- 角色：`uavfire.auth.admin-usernames`（逗号分隔）命中则为 `ADMIN`；`user_type=Web` 为 `COMMANDER`；`Pilot` 为 `PILOT`。
- `force-fail` 只允许 `ADMIN` 与 `COMMANDER`，日志写真实角色。
- 四眼默认开启。`BEFORE_APPROVE` 增加检查：审批人与 `createdBy` 都非空且不相同才通过。新草稿的 `createdBy` 写成确认火情的操作者。历史草稿没有创建人时，四眼会拒绝审批，需要补 `created_by` 或临时关闭开关。

配置：`fc100.safety.four-eyes-enabled=${FC100_FOUR_EYES_ENABLED:true}`。

回滚：`FC100_FOUR_EYES_ENABLED=false` 关闭分离校验，不改变操作者来源。

测试：同一人审批失败且不写库；Web 用户 force-fail 的角色是 `COMMANDER`；Pilot 被拒绝。

### 3.6 开钩策略（P0，高）

现状：`POST .../delivery/release-hook` 走 `PayloadReleasePolicyService`。`POST .../delivery/commands/{method}` 和 `POST .../delivery/devices/{sn}/commands/{method}` 把任意 method 送进 `sendDeviceCommand`。`DeliverySyncCommandExecutor.DANGEROUS_METHODS` 含 `hoist_hook_control` 但从未使用。

目标：`hoist_hook_control` 只有 `release-hook` 在策略通过后才能发送。自定义入口直接拒绝。队列载荷只有这条路径会带 `releaseAuthorized=true`，执行器再次检查，避免已经入队的自定义命令在异步执行时开钩。急停、返航、降落仍走各自端点。

回滚：不要用配置重新打开自定义开钩。

测试：自定义 method 不调用适配器；执行器拒绝没有授权标记的开钩载荷。

### 3.7 司运创建一致性（P0，高）

现状：`createDeliveryTaskFromLatestRoute` 先 `adapter.createTask`，成功后再 `CREATE_DELIVERY_TASK`，最后写 `dji_task_id`。外部成功、本地失败时，司运侧有任务，本地没有任务号。

目标：

1. 先把 `delivery_intent_status=PENDING`、`delivery_intent_id` 写到 `fc100_fire_mission`。
2. 再调用外部创建。失败则把意图标为 `FAILED`，任务状态不变，并保留现有失败计数。
3. 外部成功后先写 `dji_task_id` 和 `EXTERNAL_CREATED`，再推进状态机。
4. 状态机失败则标 `LOCAL_COMMIT_FAILED`，保留外部任务号。下次创建同一任务时看到该标记，不再次调用外部，只重试本地转移。

数据：

```sql
ALTER TABLE fc100_fire_mission
  ADD COLUMN delivery_intent_id varchar(64) NULL,
  ADD COLUMN delivery_intent_status varchar(32) NULL;
```

`fc100_init.sql` 同步这两列。未执行迁移时，创建任务会在更新语句失败，而不是悄悄退回“先外部后本地”。

接口：`DeliverySyncAdapter` 保持 mock / http 互斥。不在本期发明司运取消 API。补偿手段是本地记录外部任务号并阻止重复创建。

生产：`fc100.delivery-sync.mode` 仍默认 `mock`（开发安全，不打外网）。`UAVFIRE_ENV=production` 或 prod profile 下，mock 且 `allow-mock` 不为 true 时拒绝启动。正式环境要设 `mode=http` 并配置 AK/SK，或显式 `FC100_DELIVERY_SYNC_ALLOW_MOCK=true`。

回滚：新列可留空。回滚代码前确认没有 `LOCAL_COMMIT_FAILED` 行，否则旧代码会再次向外创建。

测试：mock 上验证 update 发生在 `createTask` 之前；外部异常时不 transit；本地 transit 异常后重试不再调用外部。

### 3.8 UNLOCATED 展示（P0，中）

现状：`DualStreamServiceImpl` 对纯视觉事件设置 `geoQuality=UNLOCATED` 且不带火点坐标。`fillPositionFromOsdIfMissing` 把 OSD 飞机经纬度写入 `lat/lng`。去重逻辑把 `UNLOCATED` 排除了，但指挥端 `isUsableFireLocation` 只排除激光定位中的两种质量，数字坐标仍会显示成火点。

目标：质量为 `UNLOCATED` 时，已有或 OSD 坐标只写入 `aircraftLat/aircraftLng`，火点 `lat/lng/alt` 置空。没有 OSD 也不再因此拒绝事件。指挥端对 `UNLOCATED` 显示“火点未定位”，不打印坐标，也不把它当成可用火点。

兼容：已经落库、火点字段里仍是飞机坐标的旧记录，前端同样不把它们显示成火点。

测试：后端插入实体的火点坐标为空、飞机坐标有值；前端格式化单测覆盖 `UNLOCATED`。

### 3.9 状态机：审批断链与飞手接收（P0，中）

现状：

- 精确火情确认后的草稿是 `CREATED`。`CREATED` 只能取消或强制失败。审批接口要求 `WAITING_REVIEW`。这是 PR #1 已经修过、`main` 上仍在的断链。
- `ACCEPTED_BY_PILOT` 只有出边（开始投放、强制失败），没有任何入边，生产代码也不写这个状态。

目标：

- 新草稿写 `WAITING_REVIEW`，并写入 `createdBy`。
- 转移表增加 `CREATED + APPROVE → APPROVED`，旧草稿仍可审批。火情列表对两种状态都显示审批按钮。
- 增加事件 `PILOT_ACCEPT`：`SENT_TO_DELIVERY → ACCEPTED_BY_PILOT`。补 `ACCEPTED_BY_PILOT` 的取消和接管出边，避免进入后无法退出。端点 `POST /api/fire/missions/{no}/pilot-accept`。不删除枚举，避免已有前端文案和历史行变成非法值。

测试：转移表覆盖新草稿、旧草稿可审批、未审批不能开始投放；`PILOT_ACCEPT` 只有来自 `SENT_TO_DELIVERY` 才合法。

### 3.10 预检 UOM（P1，中）

现状：`BoundaryRule` 在没有 UOM 参考图层时只警告，不阻断。

目标：`fc100.preflight.block-when-uom-missing` 在 `application.yml` 默认 true，正式进程缺图层即阻断。Java 属性默认 false，现有 `new PreflightProperties()` 的演练和单测保持警告，避免把没有图层数据的本地演练全部打红。正式部署缺图层时预检失败，这是预期。

回滚：`FC100_PREFLIGHT_BLOCK_WHEN_UOM_MISSING=false`。

### 3.11 测试基建与 JDK 21（P0 / P1）

现状：父 POM 只 import 了 Spring Boot BOM，没有继承其插件管理。Maven 因此用超 POM 的 Surefire 2.12.4，不执行 JUnit 5，`mvn test` 会报 0 个测试并成功。Lombok 跟随 Boot 2.7.12 为 1.18.26，在 JDK 21 上编译会失败。源码目标仍是 Java 11。

目标：父 POM 固定 `maven-surefire-plugin` 3.2.5。`uavfire` 的 Lombok 显式 1.18.34。

验收：`mvn -pl uavfire test` 的 Surefire 报告计数大于 0，且本变更的新测试类出现在结果里。

## 4. 风险与回滚

| 风险 | 表现 | 处理 |
| --- | --- | --- |
| 生产未配置试用 | 进程拒绝启动 | 设置 `until` + 新的到期时间，或书面确认后 `allow-unlimited=true` |
| 未设置 `JWT_SECRET` | 生产起不来；开发环境票据重启失效 | 生产写入长随机密钥。开发要固定票据时也设置该变量 |
| 四眼挡住历史草稿 | 审批 3000，提示缺少创建人 | 补 `created_by`，或短期关闭四眼 |
| 未跑数据库迁移 | 创建司运任务失败 | 执行 `backend/sql/migrations/2026-10-15-fire-mission-delivery-intent.sql` |
| MSDK 令牌未下发 | 飞机状态不再上报，命令 poll 401 | 后端与 Agent 配置同一个 `UAVFIRE_MSDK_DEVICE_TOKEN` / `agentMsdkDeviceToken` |
| 命令派发窗口仍是 15 秒 | 长时间重启后的待派发命令变成 `EXPIRED` | 需要更长窗口时增大 `UAVFIRE_MSDK_COMMAND_DISPATCH_TTL_MS`。不要靠加窗口去补飞过期指令 |
| 司运生产误用 mock | 生产拒绝启动 | `mode=http` 或显式允许 mock |
| 同一人确认并审批 | 四眼拒绝 | 换一名指挥员审批。这是开关打开后的目标行为 |

代码回滚按提交整体回退。配置回滚优先于代码回滚：先用环境变量恢复旧的严格程度，再决定是否回退二进制。不要只回退试用代码而把截止日删掉。

## 5. 验收标准

1. 设计文档进入仓库 `docs/uavfire-remediation-design.md`，PR 说明配置与验证步骤。
2. 非生产、不额外配置时，试用守卫不再因为 2026-10-01 阻止上下文启动。`mode=until` 且日期为历史截止日时，守卫和过滤器仍拒绝。
3. 安全检查失败的审批不调用状态机。
4. 默认配置下 demo-login 返回 403。示例 JWT 密钥在生产配置下不能完成守卫初始化。明文口令登录后存储值以 `{bcrypt}` 开头。
5. 无设备令牌或错误令牌不能 poll。命令存储替换为新实例后仍能取出未过期命令。
6. 票据用户名覆盖请求体。force-fail 日志角色不是写死的 `ADMIN`。同一人不能通过四眼。
7. `hoist_hook_control` 不能从自定义 method 发出。
8. 司运创建的本地 `PENDING` 更新早于 `adapter.createTask`。外部任务号在本地转移失败后被保留，重试不再创建第二张外部任务。
9. `UNLOCATED` 事件的火点经纬度为空。前端格式化结果为“火点未定位”。
10. 新草稿状态为 `WAITING_REVIEW`。`CREATED` 仍可 `APPROVE`。`SENT_TO_DELIVERY` 可以 `PILOT_ACCEPT` 到 `ACCEPTED_BY_PILOT`。
11. Surefire 3.2.5 执行本变更的 JUnit 5 测试，失败数为 0。

## 6. 本次明确不做、需要人工配置的项

- 生产试用到期时间、是否允许不限期。
- `JWT_SECRET`、管理员用户名列表、是否临时关闭四眼。
- `UAVFIRE_MSDK_DEVICE_TOKEN` 与 Agent `agentMsdkDeviceToken` 必须一致，本仓库不生成生产令牌。
- 数据库迁移需要在目标库执行。
- 司运正式接入要改 `fc100.delivery-sync.mode=http` 并填写 AK/SK/group。mock 只作为默认安全替身。
- 不提供司运侧自动取消。`LOCAL_COMMIT_FAILED` 需要人对照外部任务号处理。
- 不批量重写历史口令，不替换种子 SQL 里的明文，直到对应用户登录。
- 不实现许可证签名、UOM 图层数据本身、真实飞行验证。
