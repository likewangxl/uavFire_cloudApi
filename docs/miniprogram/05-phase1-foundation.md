# Phase 1 小程序开发基座记录

> 日期：2026-09-03
>
> 分支：`codex/m300-miniapp-foundation`
>
> 工作树：`/Users/likewang/uavfire/.worktrees/m300-miniapp-foundation`

> 2026-09-05 更新：以上为历史开发位置，现已合入 `feature/m300-model-adaptation`，旧工作树已归档。
> 后续使用 `/Users/likewang/uavfire/.worktrees/m300-model-adaptation`；详见 `docs/worktree-consolidation-2026-09-05.md`。

## 1. 本批交付

### 1.1 后端 Mini Program BFF

- 建立独立的 `/miniapp/api/v1` 接口边界。
- 实现 Bearer JWT 鉴权，不复用管理端的 `x-auth-token` 请求格式。
- 实现 `X-Request-Id` 回传、统一响应和稳定错误码。
- 微信认证路径的请求体不写入通用请求日志。
- 实现首批接口：
  - `POST /auth/wechat/login`
  - `GET /me`
  - `GET /dashboard/summary`
- 微信登录目前按安全策略返回 `WECHAT_API_UNAVAILABLE`，
  不伪造 openid、账号绑定或访问令牌。
- 首页设备统计只有在部署显式确认工作空间隔离后才能启用。
- 首页按规范机型族汇总在线设备；机型身份与运行能力分离，未知机型保持只读并 fail-closed。
- 飞行放行增加精确组合白名单；只打开全局开关不能绕过组合验收门禁。
- M350 与 M300 共用的 Zenmuse 外挂负载选择、保存、发布校验已统一，
  并增加 M350 RTK 别名和 KMZ 枚举回归测试。

### 1.2 微信小程序工程

- 建立可导入微信开发者工具的原生小程序工程。
- 实现领导驾驶舱首页、任务、报告和账号系统四个页面。
- 实现统一 HTTP 客户端、Bearer 会话和微信登录调用链。
- 未接入指标显示“—”和数据警告，不使用模拟业务数字。
- 前端飞行控制能力默认关闭。

## 2. 安全开关

| 环境变量 | 默认值 | 当前作用 |
| --- | --- | --- |
| `MINIAPP_ENABLED` | `false` | 控制整个小程序 API |
| `MINIAPP_WECHAT_ENABLED` | `false` | 标记微信服务端适配是否启用 |
| `MINIAPP_WECHAT_APP_ID` | 空 | 服务端微信 AppID |
| `MINIAPP_WECHAT_APP_SECRET` | 空 | 服务端密钥，禁止进入前端和日志 |
| `MINIAPP_FLIGHT_CONTROL_ENABLED` | `false` | 小程序飞行控制总开关 |
| `MINIAPP_FLIGHT_CONTROL_VERIFIED_COMBINATION_KEYS` | 空 | 已签字验收的精确组合键；空值禁止控制 |
| `MINIAPP_DASHBOARD_LIVE_DEVICE_SUMMARY_ENABLED` | `false` | 在线设备摘要开关 |

即使只开启 `MINIAPP_ENABLED`，微信登录、飞行操作和跨工作空间设备统计
仍不会自动开放。

组合键格式为：

```text
MODEL|CONTROLLER_OR_DOCK|PAYLOAD|POSITION
M300|RCPLUS|H30T|0
```

## 3. 验证结果

| 验证项 | 结果 |
| --- | --- |
| 后端全量 Maven 测试 | 473 个通过，0 失败 |
| 小程序基础测试 | 4 个通过，0 失败 |
| 小程序/兼容策略/航线定向测试 | 56 个通过，0 失败 |
| Markdown 检查 | 0 个问题 |
| OpenAPI 校验 | 有效；50 个既有非阻断规范建议 |
| JavaScript 语法检查 | 通过 |
| JSON 配置解析 | 9 个文件通过 |
| Web 前端构建 | 通过；仅有既有大 chunk 警告 |

以上是源码和构建证据，不代表微信真机、任何飞机/控制端/负载组合或实飞验收完成。

## 4. 下一批研发顺序

1. 新建微信身份、账号绑定、刷新令牌和会话表。
2. 接入微信 `code2Session` 服务端网关和失败重试。
3. 建立微信身份与现有账号、角色、工作空间的审核绑定。
4. 接入工作空间名称、巡检任务和已发布航线只读聚合。
5. 接入火情事件、报告生成状态、签阅和站内通知。
6. 完成订阅消息模板和发送审计。
7. 在持久化命令、幂等、二次确认、飞手确认和实飞门禁通过后，
   再开发小程序飞行操作。
8. 按 [06-多机型兼容与验收](06-multi-aircraft-compatibility.md) 清理 M300 特判、补齐适配器并逐组合验收。

## 5. 后续联调时需要用户提供

- 已申请的小程序 AppID。
- AppSecret 的服务端密钥配置渠道；不要在聊天中发送 AppSecret。
- 可在微信后台登记的 HTTPS API 域名、证书和测试环境地址。
- 领导、审批人、飞手、管理员的测试账号及工作空间映射。
- 审批结果、报告完成、紧急事件等订阅消息模板 ID。

这些输入不阻塞当前只读基础能力继续研发，但会阻塞真实微信登录和通知联调。
