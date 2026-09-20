# UAVFire 领导移动驾驶舱小程序

当前目录是可导入微信开发者工具的原生小程序工程，版本 `0.2.0`。已配置真实 AppID 和 HTTPS 接口地址，独立后端已部署到 `192.168.50.200:8090`，用户已完成账号绑定并提供手机预览截图。本轮已改为白色主题，检查范围见下方说明。

## 已实现

- 白色背景、黑灰文字、原生下拉筛选、长文本换行与完整详情。
- `Authorization: Bearer`、`X-Request-Id` 和统一响应封装。
- 微信 `wx.login` → 服务端 code2Session → 绑定申请/人工审核 → 业务会话。
- 访问令牌续期、并发请求合并续期、退出及迟到响应保护。
- 当前用户、所属工作空间和真实首页摘要。
- 首页、任务、态势、报告、我的五个页签，以及任务/火情/执行记录详情。
- 搜索、筛选、分页、账号收藏和通知偏好持久化。
- 客户端和服务端飞行控制开关默认关闭。
- 数据未接入时展示“—”和明确警告，不生成演示业务数据。

## 本地导入

1. 使用微信开发者工具导入本目录。
2. 开发期可继续使用 `touristappid` 查看静态界面。
3. 联调前执行 `node scripts/configure.mjs --app-id <真实AppID> --api-url https://实际域名/miniapp/api/v1`，设置公开 AppID 和接口地址。
4. 如需本地关闭域名校验，把 `project.private.config.example.json`
   复制为 `project.private.config.json`；该文件已被 Git 忽略，
   真机仍必须使用已备案 HTTPS 域名。
5. 按 [服务端接入说明](../docs/miniprogram/08-live-auth-and-data.md) 创建小程序表、配置微信环境并部署后端。
6. 登录后提交系统账号绑定申请；管理员核对批准后重新登录，即可读取所属工作空间业务数据。

真实微信登录还需要服务端环境变量 `MINIAPP_WECHAT_APP_ID` 和
`MINIAPP_WECHAT_APP_SECRET`。AppSecret 只能保存在服务端密钥系统中，
不要发到聊天、前端源码或微信项目配置里。

## 验证

```bash
npm test
npm run preview
```

接口契约见 [openapi-live.yaml](../docs/miniprogram/openapi-live.yaml)。报告目前来自任务执行记录，未生成正式 PDF 或签阅；通知偏好不代表微信订阅消息已经发送。浏览器交互原型仍在 `prototypes/miniapp/`，使用示例数据。

浏览器检查直接复用当前页面结构、样式和交互代码，使用离线测试数据，服务会自行启动。详细截图、46 组布局状态检查与验证边界见 [白色主题与移动端交互检查](../docs/miniprogram/09-light-theme-and-mobile-review.md)。微信原生控件、键盘和实飞验收不由浏览器替代。


## 2026-09-16 飞行报告与 iOS 布局

首页删除手动刷新按钮，登录且页面可见时每 5 秒更新，离开后停止；网络失败保留最近成功数据。使用浅色分组界面、系统字体、蓝色操作和至少 44px 触控区。列表筛选使用原生 picker。

任务/报告详情分为概览、航线、执行记录，新增速度、高度、起降/返航/电量、风速、候选关联火情、媒体元数据和完整分页遥测。历史未采集值显示“未记录”。设备名称以 `shared/device-models.json` 为唯一目录。

运行与验证记录见 [飞行报告实施记录](../docs/miniprogram/10-flight-report-implementation.md)。浏览器预览为合成数据；真机使用重新编译生成的预览码。采集端新增物理起降与返航模式上报，需要更新遥控器端后才能产生这些新字段。
