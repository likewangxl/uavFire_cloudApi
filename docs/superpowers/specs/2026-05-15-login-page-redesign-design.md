# 登录页重做设计

- 日期: 2026-05-15
- 范围: Web 端登录页(`/project` 路由)的视觉重做 + 验证码 + 演示模式
- 不在范围: Pilot 端 (`/pilot-login`) 改动、路由名变更、全局 router guard

## 背景

当前 `pages/page-web/index.vue` 是一个最小化登录页 —— 灰底、居中、账号 + 密码两个输入框,默认值写死 `adminPC/adminPC`,登录后跳 `/leadership-cockpit`。用户提供了新视觉(背景图 Image #1 + 完整稿 Image #2),要求一次重做覆盖:
- 全屏背景图(山林无人机灭火主视觉,左侧已含标题与功能徽章)
- 右侧深色玻璃质感登录卡:账号 / 密码 / 图形验证码 / 记住我 + 忘记密码 / 登录系统 / 演示模式
- 登录卡入场动画 + 按钮 hover 微动

## 需求

| 项 | 决定 | 备注 |
|---|---|---|
| 验证码 | 新增后端接口 `GET /manage/api/v1/captcha`,返回 `{token, imageBase64}`;`POST /login` body 扩 `captcha + captchaToken`;Redis SETEX 60s,一次性消费 | base64 内联,不分开做图片 URL |
| 演示模式 | 新增 `POST /manage/api/v1/demo-login`(空 body),返回与普通 login 相同结构,内部用 adminPC 用户签 JWT,跳过 captcha 与密码校验 | 用户要求 demo 模式能看到数据,不能 401 |
| `flag` | 写死 `1`(Web 后台)。Pilot 走原 `/pilot-login` 不变 | 登录卡无身份切换控件 |
| 记住我 | 只存 `username` 至 localStorage(key `remember_username`),不存密码 | |
| 忘记密码 | 弹 Modal「忘记密码请联系系统管理员」,无后端调用 | |
| 左侧文字与徽章 | 直接用合成图 Image #1 当背景,前端不重画文字 | 接受 `min-width: 1366px` 兼容性约束 |
| 动效 | 登录卡入场 `translateY(20px)+opacity` 400ms;按钮 hover `box-shadow` 150ms。无背景粒子 | |

## 架构

### 前端文件结构(新增/改写)

```
uavFire_cloudApi/frontend/src/
  pages/page-web/
    index.vue                                   # 改:容器,仅 <LoginPage/>
    login/
      LoginPage.vue                             # 新:整页布局(背景图 + 居右登录卡 + 入场动画)
      components/
        LoginForm.vue                           # 新:账号/密码/验证码/记住我/忘记密码 + 登录系统按钮 + 演示模式按钮
                                                #     emit('submit', body) 与 emit('demo')
        CaptchaImage.vue                        # 新:base64 img + 点击刷新
        ForgotPasswordModal.vue                 # 新:固定文案 Modal
      composables/
        use-login.ts                            # 新:onLogin/onDemoLogin/记住我 持久化
  assets/
    login-bg.png                                # 新:Image #1 合成图(需用户放置)
  api/
    manage.ts                                   # 改:LoginBody 加 captcha + captchaToken
    captcha.ts                                  # 新:getCaptcha() / demoLogin()
  types/
    index.ts                                    # 改:ELocalStorageKey 加 RememberUsername
```

### 后端文件结构(新增/改写)

```
backend/uavfire/src/main/java/com/yx/uavfire/manage/
  controller/
    CaptchaController.java                      # 新:GET /manage/api/v1/captcha
    LoginController.java                        # 改:加 POST /demo-login
  model/param/
    LoginParam.java                             # 改:加 captcha + captchaToken
  model/dto/
    CaptchaResponseDTO.java                     # 新:{token, imageBase64}
  service/
    ICaptchaService.java                        # 新
    impl/
      CaptchaServiceImpl.java                   # 新:ImageIO 生成 + StringRedisTemplate
      UserServiceImpl.java                      # 改:userLogin 头部插 captcha 校验;新增 demoLogin
  config/
    CaptchaConfig.java                          # 新:字符集/长度/TTL 常量
```

### 路由 + Storage(不动签名)

- 路由:`/` → `/project` → `LoginPage`(`page-web/index.vue` 仍是入口)
- localStorage 既有 6 个 key 保持不变,新增一个 `remember_username`

## 数据流

### 普通登录(captcha 一次性消费)

```
LoginPage onMounted
  → CaptchaImage.fetch()
  → axios GET /captcha
       后端 CaptchaServiceImpl:
         - 随机 4 字符 [A-Z0-9],排除易混 (0,O,1,I,L)
         - UUID = token,Redis SETEX "captcha:{uuid}" 60s
         - ImageIO 画 90x36 PNG + 干扰线/噪点 → base64
  → JSON {token, imageBase64}
  → CaptchaImage 渲染 <img :src="`data:image/png;base64,${imageBase64}`">
  → emit('update:token') 同步至 LoginForm

用户填表 → 点「登录系统」
  → use-login.ts onLogin()
  → axios POST /login {username, password, flag:1, captcha, captchaToken}
       后端 UserServiceImpl.userLogin(LoginParam):
         a) Redis GET captcha:{captchaToken} → null ⇒ 401 "captcha expired"
         b) value ≠ captcha (大小写不敏感) ⇒ 401 "captcha invalid"
         c) Redis DEL captcha:{captchaToken}    # 一次性
         d) 走原 username/password/workspace/JWT 链路

  成功 (code 0):
    → 写 token/workspaceId/username/userId/flag/mqtt 到 localStorage
    → 若 form.remember,写 remember_username = username;否则 removeItem
    → $router.push('/leadership-cockpit')

  失败 (任何分支):
    → message.error(result.message)
    → finally { captchaRef.value?.refresh() }    # captcha 已消费,必须刷新
```

### 演示模式

```
点「演示模式」
  → use-login.ts onDemoLogin()
  → axios POST /demo-login (空 body)
       后端 UserServiceImpl.demoLogin():
         1. getUserByUsername("adminPC") → 不存在则 500
         2. workspaceService.getWorkspaceByWorkspaceId(...)
         3. 构造 CustomClaim + JwtUtil.createToken(...) (与 userLogin 一致)
         4. 返回 UserDTO + access_token + mqtt 信息

  → 写 localStorage 6 项(同普通登录),但**不写 remember_username**(进 demo 不视作选择记住身份)
  → $router.push('/leadership-cockpit')
```

### 「记住我」持久化

```
LoginPage onMounted:
  const remembered = localStorage.getItem('remember_username')
  if (remembered) { form.username = remembered; form.remember = true }

onLogin 成功后:
  form.remember
    ? localStorage.setItem('remember_username', form.username)
    : localStorage.removeItem('remember_username')
```

### 「忘记密码?」

```
点链接 → ForgotPasswordModal 弹出 → 显示「忘记密码请联系系统管理员」 → 关闭
零后端调用。
```

## 错误处理

| 场景 | 处理 |
|---|---|
| `GET /captcha` 失败 | CaptchaImage 显示「刷新」占位图,点击重试 |
| Redis 不可用(captcha service 抛) | 全局 ExceptionHandler 500 兜底,前端 message.error |
| `captcha` 已过期(60s) | 后端 401 + message="验证码已过期",前端弹错并刷新 |
| `demo-login` 找不到 adminPC | 后端 401 + message,前端弹错(不应在生产发生,但需兜底) |
| localStorage 写入异常(隐私模式) | console.warn,不阻塞跳转 |

## 测试策略

按现有 TDD 习惯,每个新文件配 spec,失败先红再实现。

### 前端(Vitest)

| 文件 | 关注点 |
|---|---|
| `CaptchaImage.spec.ts` | mount 自动 fetch、refresh() 触发再次 fetch、emit token、加载失败展示占位 |
| `LoginForm.spec.ts` | 必填校验、submit 携带 5 字段(含 captchaToken)、勾选「记住我」反映在 emit payload |
| `ForgotPasswordModal.spec.ts` | open/close、显示固定文案 |
| `use-login.spec.ts` | 成功路径写 6+1 localStorage 并跳转、失败路径统一调 refresh()、记住我读写逻辑、onDemoLogin 走 `/demo-login` |

### 后端(JUnit + MockMvc)

| 文件 | 关注点 |
|---|---|
| `CaptchaServiceImplTest.java` | 生成的串符合长度/字符集、Redis 写入 key 与 TTL 正确 |
| `CaptchaControllerTest.java` | GET 返回 JSON 结构含 token + imageBase64、非空 |
| `UserServiceImplTest.java` | userLogin 新增分支:token 不存在/值不匹配/大小写不敏感匹配通过/一次性消费(再次校验返 expired) |
| `UserServiceImplTest.demoLogin` | 返回完整 payload、JWT 可解析为 adminPC user_type=1 |

## 自适应与视觉

- 最小宽度 1366px(背景图原比例)。`<body>` `min-width: 1366px`,小屏横向滚。
- 登录卡固定宽 400px,距右边 80px,垂直居中。
- 颜色从 Image #2 取:
  - 卡片底:`rgba(11, 28, 58, 0.85)` + `backdrop-filter: blur(8px)`
  - 卡片边:`1px solid rgba(64, 158, 255, 0.3)`
  - 主按钮:`#1890FF` 实底
  - 次按钮(演示):透明底 + `1px solid #1890FF` + 文字 `#40A9FF`
  - 输入框底:`rgba(255, 255, 255, 0.04)` + `1px solid rgba(64, 158, 255, 0.2)`
- 入场动画:`@keyframes slideUp { from { opacity: 0; transform: translateY(20px) } to { opacity: 1; transform: translateY(0) } }` 400ms `cubic-bezier(0.22, 1, 0.36, 1)`
- 按钮 hover:`box-shadow: 0 0 16px rgba(24, 144, 255, 0.4)` 150ms

## 资源

用户需要把 Image #1 合成图存到:
```
D:\uavfire\uavFire_cloudApi\frontend\src\assets\login-bg.png
```
建议长边 ≥ 1920px,PNG 或 JPG 均可。该步骤会列入实施计划 TODO。

## 风险与权衡

| 风险 | 缓解 |
|---|---|
| `min-width: 1366px` 在小屏会横向滚 | 已与用户达成一致:接受;为响应式更佳需用户后续提供「无文字版背景图」并切换到方案 B 第二选项 |
| Redis 故障影响登录 | 与现有架构等价(Redis 已是核心依赖);ExceptionHandler 已有 500 兜底 |
| `adminPC` 用户被改密/删除导致 demo 模式 401 | 文档约束:adminPC 是 demo 模式唯一身份,运维不应改动其用户名;未来若想可配置,加 `application.yml` 的 `demo.username` 字段 |
| 验证码图片对色弱用户不友好 | 当前需求不覆盖无障碍;后期可加「换一张」按钮(本设计的 CaptchaImage 点击即刷新,已具备) |
| 后端 `LoginParam` 加字段对现有调用方影响 | `captcha + captchaToken` 标 `@NotBlank` 后,旧客户端(只传 username/password/flag)会被拦下;这是预期行为(新登录页强制走验证码) |
