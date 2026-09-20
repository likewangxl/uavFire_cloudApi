# 微信登录与业务数据接入（0.2.0）

日期：2026-09-15。

本批将真实登录与业务读取实现在 `miniprogram/` 原生微信工程，以及后端 `/miniapp/api/v1`。浏览器原型 `prototypes/miniapp/` 仍使用示例数据，不能在浏览器内替代 `wx.login`。

## 已实现与验收范围

| 能力 | 实现 | 验收口径 |
| --- | --- | --- |
| 微信登录 | 服务端调用微信 code2Session，得到 OpenID 后查询持久化绑定 | 不接受客户端 OpenID，不返回 session_key，不用模拟凭证绕过微信 |
| 绑定 | 一次性 10 分钟 bindingTicket 提交系统账号、姓名、单位，进入待审核 | 未批准的身份拿不到业务访问令牌；管理员核实后通过服务端工具批准 |
| 会话 | 随机不透明 Bearer；访问令牌 15 分钟、会话最长 7 天；刷新轮换、设备绑定、注销 | 数据库只保存令牌哈希；旧刷新令牌不可再次使用；并发刷新仅一个成功 |
| 工作空间 | 以已批准绑定与当前系统用户归属为准，每次请求重新验证 | 修改用户归属、禁用绑定或注销后，旧会话不能继续读取；不接受客户端指定工作空间 |
| 首页与任务 | 真实 planned_wayline 记录，搜索、筛选、分页、航点与已记录时间线 | 缺失进度显示未知，过期执行状态标记待更新；不虚构完整历史 |
| 火情 | fire_event 与 fire_event_history，排除已删除记录 | 列表、详情、历史均限制工作空间；原始坐标标注 WGS84 与精度来源 |
| 报告 | 从已有执行时间的任务记录实时整理只读报告，支持账号收藏 | 明确 TASK_RECORD 来源；不虚构正式 PDF、签阅、归档或无火情结论 |
| 设备 | 仅关联当前工作空间已绑定设备的 MSDK 遥测 | 缺失/过期遥测不当成在线或离线；无法确认完整数量时返回 null |
| 偏好 | 通知偏好按用户与工作空间持久化 | 保存偏好不表示已获微信订阅授权，也不发送微信消息 |

当前已实现接口的准确字段、参数和响应以 [openapi-live.yaml](openapi-live.yaml) 为准。原 `openapi.yaml` 是完整产品目标契约，尚含审批、飞行控制、订阅消息、PDF、直播等后续范围；不能用它推断这些接口已经实现。本批未开放这些动作。

## 真实环境接入步骤

1. 在服务端安全配置渠道设置现有数据库连接与以下变量。不要把 AppSecret 写入小程序、源码、聊天或命令行参数：

   ```text
   MINIAPP_ENABLED=true
   MINIAPP_WECHAT_ENABLED=true
   MINIAPP_WECHAT_APP_ID=<真实小程序 AppID>
   MINIAPP_WECHAT_APP_SECRET=<服务端私密配置>
   MINIAPP_FLIGHT_CONTROL_ENABLED=false
   ```

2. 显式执行 `backend/sql/migrations/2026-09-15-miniapp-sessions.sql`。只创建小程序身份、票据、会话、偏好/收藏和审核记录表，不修改现有业务数据。新增表使用区分大小写的 utf8mb4_bin，避免不同 OpenID 混淆。不应通过测试给真实微信身份预置访问权限。

3. 由部署管理员重启后端，使新实现和环境变量生效。网关使用已在微信后台登记的 HTTPS 域名；服务端须能访问 `api.weixin.qq.com:443`。网关同时配置认证端点限流；代码中的每来源 IP 每分钟 30 次限制仅覆盖单进程，反向代理后按实际网络拓扑配置网关限流。

4. 在 `miniprogram/` 执行配置工具（参数中不包含 AppSecret），检查生成的配置差异：

   ```sh
   node scripts/configure.mjs --app-id wx0123456789abcdef --api-url https://实际域名/miniapp/api/v1
   ```

   以上 AppID 只是格式示例，请替换。工具写入公开 AppID 和接口地址，不保存密钥。真实设备拒绝 HTTP 地址；微信开发者工具可使用 localhost 地址做本地开发，但仍须在工具中完成本地调试配置。

5. 微信开发者工具导入 `miniprogram/`，在“我的”微信登录，提交已有系统账号、姓名和单位。管理员核对真实人员与账号对应关系后批准绑定；小程序重新微信登录即可取得业务会话。

## 管理员绑定审核

审核工具仅在拥有数据库管理权限的服务端使用，不开放公网上的审核绕过入口。依赖 Python 与 PyMySQL；环境变量为 `MINIAPP_DB_HOST`、`MINIAPP_DB_PORT`（默认 3306）、`MINIAPP_DB_NAME`、`MINIAPP_DB_USER`、`MINIAPP_DB_PASSWORD`，支持 `MINIAPP_DB_SSL_CA` 启用证书与主机名验证。

```sh
python backend/scripts/miniapp-bindings.py migrate
python backend/scripts/miniapp-bindings.py list
python backend/scripts/miniapp-bindings.py approve <identity-id> --user-id <已核实用户ID> --workspace-id <工作空间ID> --operator <审核人> --reason <核实依据>
python backend/scripts/miniapp-bindings.py reject <identity-id> --operator <审核人> --reason <驳回原因>
python backend/scripts/miniapp-bindings.py disable <identity-id> --operator <审核人> --reason <停用原因>
python backend/scripts/miniapp-bindings.py cleanup
```

批准必须同时满足：申请当前待审核、指定用户存在、所属工作空间一致、用户名与申请一致。审核操作、会话撤销和审核记录在一个事务内提交。工具不输出 OpenID、AppSecret 或令牌。`cleanup` 只清理到期临时票据和到期会话，保留身份与审核记录；不自动创建定时任务。

## Windows 本机私密配置

`backend/scripts/miniapp-wechat-config.ps1` 支持将 AppSecret 保存为当前 Windows 用户的 DPAPI 密文。配置放在 `%LOCALAPPDATA%\UAVFire\miniapp\wechat.credential.xml`，不在源码目录中；目录访问限制为当前用户与 SYSTEM。密文不能直接迁移给其他用户或 Linux 服务器。

```powershell
# 输入密钥时不显示明文；重置微信密钥后也使用此方式更新。
.\backend\scripts\miniapp-wechat-config.ps1 -Mode Set -AppId wx61a65bcc8ec245c2
# 只检查保存状态，不输出密钥。
.\backend\scripts\miniapp-wechat-config.ps1 -Mode Check
# 仅载入当前 PowerShell 进程，后端须从同一进程启动才能继承。
.\backend\scripts\miniapp-wechat-config.ps1 -Mode Import
```

`Import` 设置 `MINIAPP_WECHAT_APP_ID`、`MINIAPP_WECHAT_APP_SECRET`、`MINIAPP_WECHAT_ENABLED`，不会自动启用 `MINIAPP_ENABLED`、迁移数据库或重启后端。启动完子进程后可用 `Remove-Item Env:MINIAPP_WECHAT_APP_SECRET` 清理当前终端的密钥变量。禁止打印环境变量全集或将这些变量拼进命令行、日志。

## 验证与实际部署边界

- 首轮独立部署执行 `mvn -pl uavfire -am test package`，完整测试 524 项通过。后续白色主题与筛选更新完成 30 项相关后端测试和 24 项客户端测试；管理员工具 4 项测试通过。最新浏览器验证与截图见 [09-白色主题与移动端交互检查](09-light-theme-and-mobile-review.md)。OpenAPI 的 17 个路径与 60 处内部引用已解析检查。
- 本批后端定向测试覆盖完整 JDBC 事务、绑定状态、令牌哈希/轮换、并发刷新、失效/撤销、租户隔离、收藏和未知遥测。测试使用隔离 H2 数据库，不连接真实微信、不写现有业务库。
- 管理员工具测试使用内存 SQLite 适配参数与行锁语法，验证账号/工作空间一致性、审核状态转换、会话撤销与审核失败回滚；不替代真实 MySQL 运维执行或并发验收。
- 原生端测试覆盖微信 code 请求、并发 401 续期、刷新失效/网络失败、退出与迟到请求、HTTPS 校验、列表乱序响应、页面配置与脚本解析。
- 当前后端连接数据库字段已只读核对，涉及的六张现有表均具有实现所需字段。
- 2026-09-15 已将用户提供的真实 AppID 和 HTTPS 接口地址写入原生工程；服务端微信密钥已在本机 DPAPI 加密保存并验证可载入，未写入源码。保存配置不代表运行中的 JVM 已加载。
- 用户确认部署到 `192.168.50.200` 后，已在该服务器新建 `uavfire-miniapp.service`，使用 8090 端口与原有花生壳映射；修复了此前“内部网站连接失败”。公网 `/me`、`/inspection-tasks` 返回 `401 AUTH_REQUIRED`，无效 code 的登录探测返回 `401 WECHAT_CODE_INVALID`。
- 已显式创建五张小程序表，并使用专用数据库账号；六张现有业务表仅授予 SELECT。真实库的任务、火情、工作空间与收藏关联查询已验证，身份与会话仍为零，没有伪造微信身份。发布前后原有三个服务 PID 相同，既有后端和 AI 服务均未重启。
- 独立服务未加载原进程的 MSDK 内存遥测；设备归属可查询，在线与电量数据保持未知。真实微信 code、绑定申请及真机页面仍需在微信开发者工具与实际微信身份下完成验收。
- 服务器目录、私密配置、绑定审核工具、备份和回滚方式见 [独立服务部署说明](../../deployment/miniapp/README.md)。
- 真实上线前仍需以实际微信身份完成“未绑定拒绝业务访问→申请→审核→登录→读取所属工作空间→注销/停用立即失效”的端到端验收，并检查真机页面和网络域名。

微信协议参考：[code2Session 官方接口](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html)、[wx.login 官方接口](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/login/wx.login.html)。当前网络未能读取官方文档页面，因此未将本地网关测试描述为微信平台联调成功。
