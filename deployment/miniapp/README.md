# 小程序独立服务部署

此入口只扫描 `com.yx.uavfire.miniapp`，不会加载原火情后端的飞行控制、MQTT 消费者或任务调度。共用业务数据库，使用专用数据库账号：六张现有业务表仅 SELECT，小程序五张表允许会话、审核与偏好所需读写。

## 2026-09-15 已部署实例

- 服务器：`192.168.50.200`，服务：`uavfire-miniapp.service`，端口：`8090`。
- 目录：`/home/djdev/uavfire-miniapp`；当前版本：`releases/20260915-183200-mobile-layout`，之前版本保留供回滚。
- 接口：`https://1916dn17xs12.vicp.fun/miniapp/api/v1`，沿用花生壳现有映射。
- 私密配置：`config/miniapp.env`，权限 `0600`，仅服务账号与管理员读取；密钥不在发布包中。
- 发布前记录及数据库结构快照：`backups/20260915-104300`。原有三个服务 PID 发布前后相同。
- 首次发布归档 SHA-256：`d8722754b0520a21c29e19517c88bb5b8ad9d736ec9211d76886059c22e4315a`。最新移动布局配套版本再次验证 166 个运行文件，新 JAR SHA-256：`25ed5816501ef278ea4b03f9409a2eda36e58c8605f26f70408e45940bf1e870`。

独立进程尚未接入原进程内的实时 MSDK 遥测，设备归属取自真实业务表，在线状态保持未知。任务、火情、执行记录和用户归属来自真实数据库，不生成示例数据。

## 运维与绑定审核

```bash
systemctl status uavfire-miniapp.service
journalctl -u uavfire-miniapp.service --since '10 minutes ago'
/home/djdev/uavfire-miniapp/tools/venv/bin/python /home/djdev/uavfire-miniapp/tools/manage-bindings.py list
/home/djdev/uavfire-miniapp/tools/venv/bin/python /home/djdev/uavfire-miniapp/tools/manage-bindings.py approve <identity-id> --user-id <已核实用户ID> --workspace-id <工作空间ID> --operator <审核人> --reason <核实依据>
```

`manage-bindings.py` 在服务端私密读取数据库配置，不应复制密钥到命令行。只审核真实微信登录产生的申请；申请与会话数量以实时查询为准。

2026-09-15 登录 503 排查：服务器仍运行，非强制刷新的微信凭据校验已通过；无效 code 探测返回预期 401。原版本未保留具体上游故障原因，因此不能据此宣布真实登录 503 已修复。诊断版本新增 `WeChat code exchange failed` 日志，只记录请求 ID、受控原因分类、数字上游错误码/HTTP 状态和异常类型，不记录 code、密钥、OpenID、上游响应正文或异常堆栈。29 项相关测试通过，诊断日志已通过公网探测验证；真实失败原因需以新一次登录记录确认。

停用或回滚只操作 `uavfire-miniapp.service`。首次部署可停止该服务；后续发布应保留旧 release，切回 `current` 后只重启小程序服务。不要重启原火情/AI 服务，不自动删除共享小程序表或用户状态。重置微信密钥后，在服务器私密配置文件中更新并重启本服务；同时更新 Windows 本机 DPAPI 配置以免后续使用旧值。

## 打包与验证

从 `backend/` 执行 `mvn -pl uavfire -am test package`，以及 `mvn -pl uavfire -DincludeScope=runtime -Dmdep.outputFile=target/miniapp-runtime-classpath.txt dependency:build-classpath`。发布内容为最新 `uavfire` 普通 JAR、最新 `cloud-sdk` JAR、运行时依赖和本目录的配置模板。不得打入 H2 测试依赖。主系统默认 Maven 启动入口保持 `CloudApiSampleApplication`，独立服务通过 `MiniAppStandaloneApplication` 启动。

部署前确认端口、目录、当前链接与原服务 PID；上传后逐文件回读哈希，再切换新服务。公网未登录访问 `/me`、`/inspection-tasks` 应返回 `401 AUTH_REQUIRED`；无效微信 code 应返回 `401 WECHAT_CODE_INVALID`，这只证明请求链路已通，不代表真实身份登录已验收。

微信后台的 request 合法域名填写 `https://1916dn17xs12.vicp.fun`。微信开发者工具导入仓库 `miniprogram/`，完成真实登录、申请、管理员核实、重新登录后，才能验收所属工作空间业务读取。
