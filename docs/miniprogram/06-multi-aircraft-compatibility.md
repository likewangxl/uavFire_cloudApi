# 微信小程序多机型兼容与验收方案

> 版本：v1.1
>
> 日期：2026-09-03
>
> 原则：分支名称表示开发起点，不表示产品只支持 M300。

## 1. 范围定义

小程序和后端按“大疆行业机型能力驱动平台”设计，目标覆盖 Mavic 3 行业系列、
Matrice 30 系列、M300/M350、Matrice 3D 系列、Matrice 4 行业及 Dock 系列、
Matrice 400，以及后续通过适配器接入的行业机型。

“M3”在本项目中必须写成明确型号：

- `M3E`、`M3T`、`M3M`：Mavic 3 Enterprise 系列。
- `M3D`、`M3TD`：机场/Dock 运行链路，不能与 M3E/M3T 共用控制拓扑假设。
- 消费级 Mavic 3 不因简称相同而自动进入支持范围。

DJI 官方当前分别公布 Cloud API 和 Mobile SDK V5 的产品支持范围；两份清单并不
等价，具体机型还受遥控器、机场、负载、固件和 SDK 版本约束：

- [DJI Cloud API 产品支持](https://developer.dji.com/doc/cloud-api-tutorial/en/overview/product-support.html)
- [DJI Mobile SDK V5 产品支持](https://developer.dji.com/doc/mobile-sdk-tutorial/en/basic-introduction/msdk-introduction.html)

因此，“M300 及以上”不按价格、发布时间或数字大小推导兼容性。每个
`飞机 + 控制端 + 负载 + 固件 + SDK/Agent` 组合都必须有独立记录。

## 2. 机型族与接入路径

| 机型族 | 代表型号 | 主要控制端 | 首选接入路径 | 当前工程状态 |
| --- | --- | --- | --- | --- |
| Mavic 3 Enterprise | M3E、M3T、M3M | RC Pro Enterprise | MSDK Agent | 有枚举，待联调 |
| Matrice 30 | M30、M30T | DJI RC Plus | MSDK/Cloud | 有枚举，待联调 |
| Matrice 300 | M300 RTK | 第一代 RC Plus | MSDK Agent | 当前基线，未实飞 |
| Matrice 350 | M350 RTK | DJI RC Plus | MSDK/Cloud | 部分逻辑待补齐 |
| Matrice 3D | M3D、M3TD | DJI Dock 2 | Cloud API | 已有基础枚举，必须走机场任务语义 |
| Matrice 4 Enterprise | M4E、M4T | RC Plus 2 Enterprise | MSDK/Cloud | 有枚举，待联调 |
| Matrice 4 Dock | M4D、M4TD | 对应 DJI Dock | Cloud API | 目标扩展，需升级本地枚举与适配器 |
| Matrice 400 | M400 | 支持的行业遥控器/机场 | 以官方当前 SDK 为准 | 目标扩展，需新增枚举与适配器 |

物流、运载、灭火投放类机型或第三方 PSDK 负载必须使用独立任务与安全适配器，
不得继承普通巡检航线的“已支持”结论。

## 3. 能力分层

机型名称只负责选择适配器，按钮是否开放由运行时能力、任务状态和验收白名单共同决定。

| 等级 | 能力 | 放行条件 |
| --- | --- | --- |
| L0 | 设备身份 | 飞机 SN、规范型号、控制端、固件可追溯 |
| L1 | 只读遥测 | 在线状态、位置、电量、GNSS/RTK、更新时间真实上报 |
| L2 | 航线任务 | 航线格式、上传/下载、执行、暂停、恢复、停止和最终回执已验证 |
| L3 | 可见光媒体 | 相机通道、推流、播放、快照和媒体归档已验证 |
| L4 | 热成像 | 热通道、测温口径和数据质量已验证 |
| L5 | 激光/定位 | 激光能力、坐标质量、失败恢复和安全边界已验证 |
| L6 | 专项任务 | 火情闭环、PSDK 载荷、投放等专项安全门禁已通过 |

小程序首期允许所有在线设备进入 L1 只读视图；L2 及以上必须由 Agent/Cloud
适配器显式上报能力，并命中服务端验收白名单。未知型号即使能上报遥测，也保持只读。

## 4. 服务端决策模型

```text
原始 identity/model key
  -> AircraftIdentityNormalizer（只做别名规范化）
  -> AircraftAdapterRegistry（按机型族与控制端选适配器）
  -> RuntimeCapabilitySnapshot（Agent/Cloud API 实际上报）
  -> AcceptanceMatrix（已签字的组合和能力等级）
  -> PreflightPolicy（账号、任务、飞机、载荷、环境、时效）
  -> READ_ONLY / PREVIEW_ONLY / CONTROL_ALLOWED
```

硬规则：

1. 不以 `model == M300` 或“非 M300”分支推断功能。
2. 不以代码中存在枚举值推断真机兼容。
3. 不以某个载荷支持热成像推断整机支持航线控制。
4. 未知型号、未知控制端、过期状态、能力字段缺失均 fail-closed。
5. 航线预检生成的一次性确认令牌必须绑定能力快照和验收矩阵版本。
6. 机型能力在确认后发生变化，旧令牌立即失效。

## 5. 当前代码差距

| 位置 | 当前问题 | 整改要求 |
| --- | --- | --- |
| Agent `PayloadCapabilityResolver` | 主要按 M300 特判载荷与开关 | 拆成机型族适配器，能力逐项探测 |
| Agent `MsdkKeyValueClient` | 非 M300 存在旧兼容路径 | 未识别型号不得自动获得能力 |
| Agent `AgentRuntimeLoop` | 多项控制能力固定上报 `true` | 改为 SDK key 可用性、设备状态和实测结果 |
| Agent `WaylineMqttPublisher` | 非 M300 默认 M4T 拓扑 | 显式映射，未知禁止发布 |
| 后端 `PlannedWaylineServiceImpl` | 多处仅判断 M300 | 按负载族拆分 |
| Web 航线页面 | 外挂负载选择仅对 M300 展示 | M350 按同类策略展示；其他机型使用自身载荷契约 |
| 本地 Cloud SDK 枚举 | 尚未覆盖所有官方当前机型 | 升级 SDK 或维护隔离的兼容枚举，不篡改未知值 |

这些差距意味着当前不能宣称“所有机型已适配”。当前完成的是多机型安全基线和
实施方案；每个组合仍需开发、自动化测试和真机/实飞验收。

## 6. 兼容矩阵数据结构

建议新增 `aircraft_compatibility_profile`：

| 字段 | 说明 |
| --- | --- |
| `profile_id` | 版本化配置 ID |
| `aircraft_family/model_key` | 规范机型族与型号 |
| `control_endpoint_type/model_key` | RC、Dock 及具体型号 |
| `payload_model_key/position` | 负载型号与安装位，可空但不能含糊 |
| `firmware_range` | 已验证固件范围 |
| `agent_or_adapter_version` | Agent APK 或 Cloud 适配器版本 |
| `capability_level` | L0–L6 |
| `allowed_actions` | 精确到动作的白名单 |
| `acceptance_record_id` | 验收证据关联 |
| `status` | DRAFT、VERIFIED、SUSPENDED、REVOKED |
| `effective_at/expires_at` | 生效与复验时间 |

运行时接口同时返回：

- `aircraftFamily`：规范机型族。
- `compatibilityStatus`：`VERIFIED`、`PARTIAL`、`UNVERIFIED`、`UNKNOWN`。
- `capabilityProfile`：逐项真实能力。
- `operationPolicy`：`READ_ONLY`、`PREVIEW_ONLY`、`CONTROL_ALLOWED`。
- `blockingReasons`：可展示且可审计的阻断原因。

## 7. 逐组合验收模板

每行只代表一个组合，不允许用“M300 通过”概括所有负载或固件：

```text
验收记录ID / 日期 / 地点 / 审批 / 天气
飞机SN / 规范机型 / 飞机固件
控制端SN / 类型 / 型号 / 固件
负载SN / 型号 / 安装位 / 固件
SDK版本 / Agent包名 / versionName / versionCode / APK SHA-256
后端、前端、AI、媒体服务提交或制品哈希
航线ID / KMZ哈希 / 坐标系 / 航点与动作
L0-L6每项：期望、实际、日志、视频、结论
异常：断网、断流、低电量、RTK变化、遥控器接管、重复指令、重启恢复
批准人 / 飞行负责人 / 安全负责人 / 复验日期
```

最低测试集按机型族分别执行：地面通电、无桨安全测试、封闭场航线、暂停/恢复/停止、
控制端接管、失联策略、报告生成、通知送达、审计回放。热成像、激光和专项载荷只在
对应硬件组合上增加测试，不能由其他机型结果代替。

## 8. 发布策略

1. 默认仅开放多机型只读态势。
2. 按 `workspace + user + aircraftSn + model + controller + payload + action` 灰度。
3. 新机型先进入 `UNKNOWN/READ_ONLY`，完成适配后进入 `UNVERIFIED/PREVIEW_ONLY`。
4. 自动化、地面和实飞证据签字后才进入 `VERIFIED/CONTROL_ALLOWED`。
5. 发现身份不一致、固件越界或严重故障时，撤销矩阵版本即可统一降级，无需等待发版。
