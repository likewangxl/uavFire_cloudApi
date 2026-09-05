# UAVFire 领导移动驾驶舱小程序

当前目录是第一阶段可导入微信开发者工具的原生小程序基座，版本 `0.1.0`。

## 已实现

- 与 PC 领导驾驶舱一致的深色态势风格和移动端首页骨架。
- `Authorization: Bearer`、`X-Request-Id` 和统一响应封装。
- 微信 `wx.login` 调用链及服务端登录接口占位。
- 当前用户、工作空间和首页摘要请求链路。
- 巡检任务、报告中心、账号与系统页面入口。
- 客户端和服务端飞行控制开关默认关闭。
- 数据未接入时展示“—”和明确警告，不生成演示业务数据。

## 本地导入

1. 使用微信开发者工具导入本目录。
2. 开发期可继续使用 `touristappid` 查看静态界面。
3. 联调前把 `project.config.json` 中的 `touristappid`
   替换为已申请的真实 AppID；AppID 不是 AppSecret，但应按团队配置流程管理。
4. 如需本地关闭域名校验，把 `project.private.config.example.json`
   复制为 `project.private.config.json`；该文件已被 Git 忽略，
   真机仍必须使用已备案 HTTPS 域名。
5. 在 `config/runtime.js` 的 `apiBaseUrl` 填写测试环境 HTTPS 地址，例如 `https://example.test/miniapp/api/v1`。
6. 后端显式设置 `MINIAPP_ENABLED=true` 后，受保护接口才会开放。

真实微信登录还需要服务端环境变量 `MINIAPP_WECHAT_APP_ID` 和
`MINIAPP_WECHAT_APP_SECRET`。AppSecret 只能保存在服务端密钥系统中，
不要发到聊天、前端源码或微信项目配置里。

## 验证

```bash
npm test
```

当前版本是开发基座，不代表微信审核、真机网络、任何具体飞机/控制端/负载组合或实飞验收已经完成。
