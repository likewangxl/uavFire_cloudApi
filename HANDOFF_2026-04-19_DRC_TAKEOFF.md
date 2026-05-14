# 交接记录：官方起飞 / DRC / 云控授权问题

更新时间：2026-04-21
工作目录：`/Users/likewang/uavfire`

## 0.0 2026-04-21 最新补充摘要

这一轮没有再改官方起飞主链路，主要做了三件事：

1. 补 DRC 退出来源埋点，便于下次现场复测直接定位“是谁触发了退出遥控”
2. 把本机运行环境重新收敛到 Java 11 + `192.168.50.254`
3. 重做 `tsa.vue` 左侧飞行控制区的布局和交互，重点是位移控制弹窗、按钮分区、危险动作层级和文案统一

### 0.0.1 本轮与下次现场测试直接相关的结论

- 前端提示“云控会话仍在，但飞行授权已释放，请重新申请授权”不是误报。
- 结合下午日志，现场那次是后端实际收到了：
  - `drc_mode_exit`
  - `cloud_control_release`
  - `cloud_control_auth_update authorized=false`
- 当前代码里，`tsa.vue` 已给 `disconnectRemoteControl()` 增加埋点：
  - 日志关键字：`[RemoteSessionDisconnect]`
  - 目前已知 `source`：
    - `user_click_exit`
    - `reconnect_before_new_enter`
- 下次只要再出现“授权被释放”或后端出现 `DRC exit request`，第一件事就是先对照：
  1. 前端控制台是否出现 `[RemoteSessionDisconnect]`
  2. `source` 是什么
  3. 后端是否紧跟出现 `drc_mode_exit`
  4. 后端是否继续出现 `cloud_control_release` 和 `authorized=false`

### 0.0.2 当前前后端运行环境状态

- Java 默认环境已改为 **JDK 11**
  - 已写入：
    - `~/.zprofile`
    - `~/.zshrc`
    - `~/.bash_profile`
  - 新开 shell 默认应是 Java 11，不应再回到 JDK 8
- 当前仓库运行地址已改回 **`192.168.50.254`**
  - 前端：
    - `frontend/src/api/http/config.ts`
  - 后端：
    - `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/RootController.java`
    - `backend/uavfire/src/main/resources/application.yml`
- 这意味着当前应按下面这组地址理解本机环境：
  - 前端：`http://192.168.50.254:8080`
  - 后端：`http://192.168.50.254:6789`

### 0.0.3 TSA 页面本轮已完成的交互改动

本轮主要改的是：

- 文件：
  - `frontend/src/pages/page-web/projects/tsa.vue`
- 新增/保持的能力：
  - `上升` / `下降` 点击后弹出“距离（米）”输入框
  - `向前` / `向后` 点击后弹出“距离（米）”输入框
  - `向左` / `向右` 点击后弹出“距离（米）”输入框
  - 其中：
    - `向前` = 地图坐标向北
    - `向后` = 地图坐标向南
    - `向左` = 地图坐标向西
    - `向右` = 地图坐标向东
- 控制区已分成 4 个区域：
  - `远程控制`
  - `基础飞行`
  - `位移控制`
  - `任务控制`
- 文案已调整为当前版本：
  - `进入遥控` -> `申请遥控`
  - `官方起飞` -> `起飞`
- 当前位移按钮顺序是：
  - 第一行：`上升` / `向前` / `下降`
  - 第二行：`向左` / `向后` / `向右`
- 小字说明已去掉
- 位移按钮已加统一方向图标
- `急停`、`停止飞行` 已改成更鲜艳的危险色，并在布局上与普通动作拉开
- 整组按钮字体已统一回较大的版本，目前按钮字号为 `14px`

### 0.0.4 本轮验证结果

已实际通过：

```bash
node --test frontend/scripts/axis-displacement-policy.test.mjs frontend/scripts/cloud-control-auth-policy.test.mjs frontend/scripts/drc-connection-policy.test.mjs frontend/scripts/drc-ws-event-policy.test.mjs frontend/scripts/official-takeoff-flow.test.mjs frontend/scripts/official-takeoff-session-policy.test.mjs
npm --prefix frontend run build
```

说明：

- 测试通过
- 前端构建通过
- 构建过程中仍会出现仓库原有的：
  - Sass `@import` / legacy-js-api warning
  - `::v-deep` warning
  - chunk size warning
- 这些不是本轮新增问题

### 0.0.5 下一位接手时最值得优先看的点

1. 如果用户继续调 TSA 控制区，优先看：
   - `frontend/src/pages/page-web/projects/tsa.vue`
2. 如果用户继续排“授权被释放 / 自动退出遥控”，优先看：
   - 前端控制台 `[RemoteSessionDisconnect]`
   - 后端 `cloud-api-sample.log` 中的：
     - `DRC exit request`
     - `drc_mode_exit`
     - `cloud_control_release`
     - `cloud_control_auth_update`
3. 交接时不要沿用文档里旧的 `172.20.10.7` 运行结论；当前代码和本机测试已经回到 `192.168.50.254`

## 0.1 2026-04-20 最新交接摘要

这一轮工作已经把问题从“第二阶段不执行”进一步收敛到“第一阶段完成后仍存在一段错误降高”，并确认其中有两层不同问题：

- 第一层是高度基准用错：
  - 旧实现把 `target_height` / `fly_to_point.height` 当成相对高度发送
  - DJI 要求这两个字段是绝对椭球高
- 第二层是运行实例混淆：
  - 某些现场测试日志来自旧后端实例
  - 当时虽然源码已改，但运行中的进程仍在使用旧请求参数

这两层问题都已经被实际排过，不能再混为一谈。

本轮还完成了一项环境变更：

- 代码里的默认本机地址已从 `192.168.50.254` 统一改为 `172.20.10.7`
- 涉及后端 MQTT / Pilot 登录入口 / livestream 配置 / 前端 API 与 WebSocket 地址

当前有效服务地址应按以下值理解：

- 前端：`http://172.20.10.7:8080`
- 后端：`http://172.20.10.7:6789`
- BASIC MQTT：`172.20.10.7:1883`
- DRC MQTT WS：`ws://172.20.10.7:8083/mqtt`

## 0.1.1 这一轮已经实际做过的工作

1. 复盘了多次现场飞行日志，重点盯：
   - `takeoffToPoint request JSON`
   - `takeoffToPointProgress plannedPathPoints`
   - `returnHomeInfo`
   - `flyToPoint request JSON`
2. 确认第一阶段早期故障的直接根因是：
   - `target_height=30.0`
   - 飞机先爬到起飞点上方约 30 米
   - 又被错误绝对高目标拉低
3. 发现后端第一次兜底修正没有生效的原因：
   - 代码当时读取的是 `elevation`
   - 地面起飞前 `elevation=0.0`
   - 所以没有把旧请求改写成绝对高
4. 结合日志再次确认：
   - `height` 才是与 `planned_path_points.height` 一致的绝对高
   - `elevation` 更接近相对高度 / 离地高度语义
5. 已把前后端都改成按 `height` 作为绝对高度基线：
   - 第一阶段 `takeoff_to_point.target_height`
   - 第二阶段 `fly_to_point.points[].height`
6. 已补前端测试与后端单测，并分别编译通过
7. 已重新编译并重启前后端，确保当前运行实例和源码对齐
8. 已把仓库中代码里的默认 IP 从 `192.168.50.254` 改成 `172.20.10.7`

## 0.1.2 这轮日志里最关键的新结论

### 结论 A：第二阶段已经能执行，说明状态机主链路不是完全断的

用户最新一次测试中，第二阶段 `fly_to_point` 已经实际发送成功。这说明：

- “第二阶段完全不执行” 已不是当前主问题
- 当前主问题变成：第一阶段结束后，飞机仍会出现一段异常降高，随后才继续第二阶段

### 结论 B：出现过“源码是新的，但日志还是旧参数”的情况

这不是分析错误，而是运行实例确实曾不一致。

典型日志：

- `16:51:46 takeoffToPoint request JSON ... "target_height":30.0`
- `16:52:13 flyToPoint request JSON ... "height":38.4`

这两条都说明当时跑的还是旧逻辑：

- 第一阶段还是相对 30 被直接塞进绝对高字段
- 第二阶段还是拿 `elevation=8.4` 去做绝对高换算

但这段日志发生在新实例重启之前，因此不能用它来否定后续修正本身。

### 结论 C：后续接手必须先核对“本次测试对应的是哪个实例”

接下来每次复测，第一件事不是先猜飞控，而是先确认：

- 测试时间
- 后端启动时间
- 对应日志里的 `takeoffToPoint request JSON`

只要日志里还是：

- `target_height: 30.0`
- 或第二阶段 `height: 38.4`

那就说明测试没有命中新逻辑，不能继续拿这次结果推翻当前修复方向。

## 0.2 2026-04-20 新确认结论：第一阶段掉高的明确根因

这条结论已经可以视为本轮“官方起飞第二阶段不执行”的主根因，不再只是猜测。

### 0.1.1 明确根因

`takeoff_to_point` 的高度字段使用了两种不同坐标语义：

- `target_height` = 绝对高度，WGS84 椭球高
- `security_takeoff_height` = 相对起飞点高度
- `commander_flight_height` = 相对起飞点高度

但旧实现把三者都按“相对起飞点 30 米”处理，导致：

- 飞机会先按相对高度逻辑爬升到起飞点上方约 30 米
- 然后又按错误的 `target_height=30`（绝对椭球高 30 米）继续执行
- 对现场飞机而言，这个绝对高度远低于当前实际椭球高，所以飞机开始主动掉高

这就是现场看到“先接近 30 米，再掉到 5、6 米甚至更低”的直接原因。

### 0.1.2 官方依据

DJI Cloud API 官方文档：

- `takeoff_to_point` 中：
  - `target_height` = target point height
  - `commander_flight_height` = command flight height
- `takeoff_to_point_progress.planned_path_points.height` = trajectory point height, ellipsoid height
- `fly_to_point.points[].height` = target point height (ellipsoid height), using the WGS84 model

官方文档链接：

- `takeoff_to_point` / `takeoff_to_point_progress` / `fly_to_point`
  - https://github.com/dji-sdk/Cloud-API-Doc/blob/master/docs/en/60.api-reference/20.dock-to-cloud/00.mqtt/20.dock/10.dock2/110.drc.md

### 0.1.3 本地日志证据

#### 下发参数

本次故障任务 `flightId=1c54b55b-51bb-4679-9187-998e833d0c69`，后端实际下发：

- `target_height=30.0`
- `security_takeoff_height=30.0`
- `commander_flight_height=30.0`

见：

- [backend/uavfire/logs/cloud-api-sample.log:13837](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:13837)

#### 飞机规划路径

同一链路的 `takeoff_to_point_progress` 回传过：

- `354.78845`
- `30.000458`

以及更早一次回传过：

- `354.72708`
- `384.72708`
- `30.001068`

见：

- [backend/uavfire/logs/cloud-api-sample.log:14004](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:14004)
- [backend/uavfire/logs/cloud-api-sample.log:9598](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:9598)

这正说明飞机同时在处理：

- 起飞点附近约 `354.x` 的绝对椭球高
- 起飞点上方约 `384.x` 的“相对 30 米爬升”
- 以及错误的目标绝对高 `30.000xxx`

#### 现场实际掉高

同一次任务后续 `returnHomeInfo` 回传高度：

- `29.874`
- `6.733`
- `5.01`
- `3.508`
- `1.369`

见：

- [backend/uavfire/logs/cloud-api-sample.log:13912](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:13912)
- [backend/uavfire/logs/cloud-api-sample.log:13953](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:13953)
- [backend/uavfire/logs/cloud-api-sample.log:13996](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:13996)
- [backend/uavfire/logs/cloud-api-sample.log:14047](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:14047)
- [backend/uavfire/logs/cloud-api-sample.log:14075](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:14075)

因此可以明确排除：

- “前端没发 30 米”
- “后端把 30 米改坏了”
- “第二阶段逻辑自己先坏了”

真正的问题是：

**第一阶段把 `target_height` 错当成相对高度发送，导致飞机在爬升后又朝绝对高 30 米去下降。**

### 0.1.4 修复原则

应改为：

- `target_height = 起飞点当前椭球高 + 30`
- `security_takeoff_height = 30`
- `commander_flight_height = 30`

并且第二阶段 `fly_to_point.height` 也必须使用绝对椭球高：

- `fly_to_point.height = 起飞点当前椭球高 + 30`

不能继续把第二阶段 `height` 写死成 `30`，否则即使第一阶段修好，第二阶段仍会把飞机往绝对高 `30m` 拉。

## 0. 2026-04-19 晚间补充说明

这份文档前半部分保留了当时阶段性的判断，其中有些结论在晚间继续排查后已经被更新。接手时请同时看：

- 第 8 节“当前未解决的问题”
- 第 12 节“2026-04-19 晚间新增工作记录”

不要只看前面较早的阶段性结论。

## 1. 这份交接文档的目的

这份文档是给下一位接手的人和新的 GPT 账户直接上手用的。

重点目标：

- 不要再重复已经犯过的错误
- 不要再把 DRC、摇杆失效、云控授权混为一谈
- 不要再凭感觉改飞行高度语义
- 按“官方文档 + 现场日志 + 当前代码”三者一致的方式继续排查

## 2. 当前问题拆分

当前实际上有两条问题线，不能混在一起处理：

### 2.1 问题 A：官方起飞后提示“云端操控已断开”

当前结论：

- 这个提示不能再简单理解成“MQTT 断了”或者“DRC 断了”
- 这次现场日志里，真正能确认的是：`cloud_control_auth` 变成了空数组
- 也就是云控授权被释放了

### 2.2 问题 B：官方起飞没有真正起飞 / 任务很快失败

当前结论：

- `takeoff_to_point` 服务调用成功
- 但飞机端任务很快进入 `wayline_failed`
- 失败发生在飞机侧 / 飞控侧，不是网页按钮没发出去
- 当前日志里失败码只有 `UNKNOWN`，还没有拿到更细错误原因

## 3. 本次已经确认的官方文档结论

以下内容已经查过 DJI 官方文档，不要再反复猜：

### 3.1 DRC / 云控授权 / 摇杆失效是三套概念

官方依据：

- DRC 流程图：
  `docs/en/30.feature-set/10.pilot-feature-set/90.drc.md`
- Pilot to Cloud DRC API：
  `docs/en/60.api-reference/10.pilot-to-cloud/00.mqtt/20.rc-pro/30.drc.md`

结论：

- `cloud_control_auth_request` / `cloud_control_auth_notify` / `cloud_control_auth` 表示云控授权
- `drc_mode_enter` / `drc_status_notify` 表示 DRC 链路状态
- `joystick_invalid_notify` 表示摇杆失效原因
- 这三者不能互相代替

### 3.2 `drc_status_notify` 不是“授权丢失”

官方定义：

- `drc_state`
  - `0` = Not connected
  - `1` = Connecting
  - `2` = Connected

所以：

- `drc_status_notify` 只能说明 Live Flight Controls 链路状态
- 不能直接等同于“云端操控已断开”

### 3.3 `joystick_invalid_notify` 不是“应该销毁整条会话”

官方定义（Dock 文档中写得更完整）：

- `reason`
  - `0` = 遥控器失联
  - `1` = 低电返航
  - `2` = 低电降落
  - `3` = 接近禁飞区
  - `4` = 遥控器接管控制权

所以：

- 这是“摇杆控制当前失效原因”
- 不是“请立即 destroyRemoteControlClient()”

### 3.4 `takeoff_to_point` 的高度语义

官方依据：

- `docs/en/60.api-reference/20.dock-to-cloud/00.mqtt/20.dock/10.dock2/110.drc.md`

已经确认：

- `target_height` = WGS84 ellipsoid height
- `security_takeoff_height` = 相对起飞点高度
- `commander_flight_height` = 相对起飞点高度

这个是之前最容易犯错的地方。

## 4. 之前已经踩过的坑，禁止重复

### 4.1 错误修法：`osd.height + 30`

曾经错误地把：

- `osd.height`

当成“起飞点高度基线”，然后把第一阶段高度改成：

- `target_height = initialHeight + 30`

结果现场飞到 130 米以上，非常危险。

这个修法已经回退。

禁止再做：

- 未经官方文档确认就把 `osd.height` 当起飞点基线
- 未经验证就把相对高改成绝对高

### 4.2 错误状态机：收到 DRC 事件就直接销毁会话

之前 `tsa.vue` 里存在：

- `JoystickInvalidNotify` -> 直接 `destroyRemoteControlClient()`
- `DrcStatusNotify(DISCONNECT)` -> 直接 `destroyRemoteControlClient()`

这不符合官方语义，已经修正。

## 5. 当前日志能确认的事实

### 5.1 一次典型的失败起飞链路

关键日志在：

- [backend/uavfire/logs/cloud-api-sample.log:23064](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:23064)
- [backend/uavfire/logs/cloud-api-sample.log:23078](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:23078)
- [backend/uavfire/logs/cloud-api-sample.log:23116](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:23116)

时序：

1. `takeoff_to_point` 被成功下发
2. `services_reply result=0`
3. 进度先到 `task_ready`
4. 然后到 `wayline_progress`
5. 紧接着出现 `wayline_failed`, `result={code='-1', message=UNKNOWN}`
6. 又收到 `task_finish`
7. 约 10 秒后，`cloud_control_auth=[]`

结论：

- 服务调用成功，不代表飞控执行成功
- 真正的失败点是飞机侧任务执行阶段
- 授权释放发生在任务失败之后

### 5.2 当前没有拿到更细失败原因

现在最关键的限制是：

- 飞机返回的失败码只有 `code=-1`
- `message=UNKNOWN`

因此当前不能严谨地下结论说一定是：

- 高度错误
- GEO 管控
- 机载避障
- 遥控器接管
- 飞行安全预检查失败

这些都只能算可疑方向，不能冒充已确认根因。

## 6. 本次已经完成的代码改动

### 6.1 前端新增的策略文件

- [frontend/src/pages/page-web/projects/drc-connection-policy.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/drc-connection-policy.mjs)
  - 作用：MQTT `close/error` 不再立刻视为断链，增加 10 秒宽限

- [frontend/src/pages/page-web/projects/drc-ws-event-policy.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/drc-ws-event-policy.mjs)
  - 作用：把 WebSocket 层 DRC 事件拆分为：
    - DRC 链路状态变更
    - 摇杆可用性变更
    - 不再直接销毁远控会话

- [frontend/src/pages/page-web/projects/official-takeoff-flow.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/official-takeoff-flow.mjs)
  - 作用：官方起飞两阶段参数构造与阶段状态判断
  - 当前已回退到固定 `30` 高度逻辑，未再使用 `osd.height + 30`

- [frontend/src/pages/page-web/projects/cloud-control-auth-policy.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/cloud-control-auth-policy.mjs)
  - 作用：把 `cloud_control_auth` / `is_cloud_control_auth` 解析成前端单独授权状态

- [frontend/src/pages/page-web/projects/official-takeoff-session-policy.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/official-takeoff-session-policy.mjs)
  - 作用：官方起飞执行期间锁定遥控会话，禁止会话切换/退出

### 6.2 前端主要改动文件

- [frontend/src/pages/page-web/projects/tsa.vue](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/tsa.vue)

当前改动要点：

- MQTT 断链改为宽限处理
- `JoystickInvalidNotify` 不再销毁会话
- `DrcStatusNotify` 不再直接等同于“云端操控已断开”
- `cloud_control_auth_update` 已单独接入前端授权状态
- 官方起飞执行期间禁止“重新授权 / 进入遥控 / 退出遥控”
- 官方起飞执行期间如果 DRC MQTT 短暂波动，优先保留当前会话等待恢复
- UI 上区分：
  - 有远控会话但 DRC / 摇杆不可用
  - 有远控会话但飞行授权已释放
  - 真正可执行遥控飞行指令

### 6.3 后端主要改动文件

- [backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/SDKControlService.java](/Users/likewang/uavfire/backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/SDKControlService.java)
  - 增加 `takeoff_to_point_progress` 详细日志：
    - `flightId`
    - `status`
    - `result`
    - `remainingDistance`
    - `remainingTime`
    - `wayPointIndex`
    - `plannedPathPoints`

- [backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/ControlServiceImpl.java](/Users/likewang/uavfire/backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/ControlServiceImpl.java)
  - 增加 `takeoff_to_point` 请求 JSON 输出
  - 增加 `flight authority` 抢占日志

- [backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/CloudControlAuthStateResolver.java](/Users/likewang/uavfire/backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/CloudControlAuthStateResolver.java)
  - 作用：从未知 `state` 消息中解析 `cloud_control_auth` / `is_cloud_control_auth`

- [backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/CloudControlAuthStatePushService.java](/Users/likewang/uavfire/backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/CloudControlAuthStatePushService.java)
  - 作用：把设备侧授权状态推送成 WebSocket 事件 `cloud_control_auth_update`

- [backend/uavfire/src/main/java/com/yx/uavfire/component/websocket/model/BizCodeEnum.java](/Users/likewang/uavfire/backend/uavfire/src/main/java/com/yx/uavfire/component/websocket/model/BizCodeEnum.java)
  - 新增 `CLOUD_CONTROL_AUTH_UPDATE`

### 6.4 测试文件

- [frontend/scripts/drc-connection-policy.test.mjs](/Users/likewang/uavfire/frontend/scripts/drc-connection-policy.test.mjs)
- [frontend/scripts/drc-ws-event-policy.test.mjs](/Users/likewang/uavfire/frontend/scripts/drc-ws-event-policy.test.mjs)
- [frontend/scripts/official-takeoff-flow.test.mjs](/Users/likewang/uavfire/frontend/scripts/official-takeoff-flow.test.mjs)
- [frontend/scripts/cloud-control-auth-policy.test.mjs](/Users/likewang/uavfire/frontend/scripts/cloud-control-auth-policy.test.mjs)
- [frontend/scripts/official-takeoff-session-policy.test.mjs](/Users/likewang/uavfire/frontend/scripts/official-takeoff-session-policy.test.mjs)
- [backend/uavfire/src/test/java/com/yx/uavfire/manage/service/CloudControlAuthStateResolverTest.java](/Users/likewang/uavfire/backend/uavfire/src/test/java/com/yx/uavfire/manage/service/CloudControlAuthStateResolverTest.java)

## 7. 本次已经做过的验证

### 7.1 前端测试

已通过：

```bash
node --test frontend/scripts/drc-ws-event-policy.test.mjs frontend/scripts/drc-connection-policy.test.mjs frontend/scripts/official-takeoff-flow.test.mjs frontend/scripts/cloud-control-auth-policy.test.mjs frontend/scripts/official-takeoff-session-policy.test.mjs
```

### 7.2 前端构建

已通过：

```bash
npm --prefix frontend run build
```

构建中有历史遗留 warning：

- Sass `@import` deprecation
- `::v-deep` deprecation
- 大 chunk warning

这些都不是本次问题引起的新增阻塞。

### 7.3 后端状态

这次最后一次确认时：

- 后端可用端口：`6789`
- 前端可用端口：`8080`
- Java 必须使用 `openjdk@11`

但交接时不要假设服务仍在，接手后重新检查端口。

### 7.4 后端单测

已通过：

```bash
export JAVA_HOME="$(brew --prefix openjdk@11)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl uavfire -Dtest=CloudControlAuthStateResolverTest test
```

## 8. 当前未解决的问题

### 8.1 未解决：为什么 `takeoff_to_point` 很快 `wayline_failed`

这是当前最核心的未解决问题。

现状：

- 后端和前端都能成功发起任务
- 飞控立刻失败
- 原因码不充分

必须继续向“飞机为什么失败”挖，而不是继续围绕前端提示打转。

### 8.2 未解决：为什么以及何时 `cloud_control_auth` 被释放

晚间更新后，结论要更细：

- 早期测试里，确实出现过 `wayline_failed` 后不久 `cloud_control_auth=[]`
- 但晚间最新一轮全流程测试表明，`wayline_failed` 之后授权并没有立刻释放
- 最新一轮里真正明确的一次授权释放，是前端/用户侧会话切换后主动触发了：
  - `drc_mode_exit`
  - `cloud_control_release`
  - 随后 `cloud_control_auth=[]`

还没确认：

- 这是 Pilot 的默认行为
- 还是 RC / 飞机在失败后主动撤权
- 还是某个事件导致 Pilot UI 自动取消授权

### 8.3 已完成：前端已单独监听并建模授权状态

这条在文档前半段里已经过期，晚间已完成：

- 后端已把 `cloud_control_auth` / `is_cloud_control_auth` 推送为 `cloud_control_auth_update`
- 前端已把授权状态单独建模，不再拿 DRC 链路状态猜授权状态
- 页面已能区分：
  - DRC 链路异常
  - 摇杆不可用
  - 飞行授权已释放

因此后续重点不再是“补授权状态建模”，而是继续查设备侧失败根因和剩余的授权释放路径。

## 9. 下一位接手的人应该怎么干

严格按下面顺序，不要跳步。

### 第一步：重新确认服务状态

```bash
lsof -nP -iTCP -sTCP:LISTEN | rg '(:6379|:1883|:8083|:6789|:8080)'
redis-cli ping
curl -I http://127.0.0.1:6789
curl -I http://127.0.0.1:8080
```

如果后端没起来：

```bash
export JAVA_HOME="$(brew --prefix openjdk@11)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/likewang/uavfire/backend
mvn -pl uavfire spring-boot:run
```

### 第二步：先看当前前端代码状态，不要急着飞

重点文件：

- `frontend/src/pages/page-web/projects/tsa.vue`
- `frontend/src/pages/page-web/projects/drc-ws-event-policy.mjs`
- `frontend/src/pages/page-web/projects/drc-connection-policy.mjs`
- `frontend/src/pages/page-web/projects/cloud-control-auth-policy.mjs`
- `frontend/src/pages/page-web/projects/official-takeoff-flow.mjs`
- `frontend/src/pages/page-web/projects/official-takeoff-session-policy.mjs`

### 第三步：继续查官方文档，不要只看 demo 代码

必须优先看的官方文件：

- `/tmp/Cloud-API-Doc/docs/en/30.feature-set/10.pilot-feature-set/90.drc.md`
- `/tmp/Cloud-API-Doc/docs/en/60.api-reference/10.pilot-to-cloud/00.mqtt/20.rc-pro/30.drc.md`
- `/tmp/Cloud-API-Doc/docs/en/60.api-reference/20.dock-to-cloud/00.mqtt/20.dock/10.dock2/110.drc.md`
- `/tmp/Cloud-API-Doc/docs/en/00.index.md`

如果 `/tmp/Cloud-API-Doc` 不在了，重新拉：

```bash
git clone --depth=1 https://github.com/dji-sdk/Cloud-API-Doc.git /tmp/Cloud-API-Doc
```

### 第四步：重点补“失败原因日志”

当前最值得做的是：

1. 查飞控在 `wayline_failed` 前后还有没有别的事件
2. 查是不是有官方文档里的安全预检或 GEO 相关状态能在 state / osd / events 中体现
3. 查最新测试中 `Pilot` 的“云端操控已断开”提示，究竟对应哪一次授权释放
4. 如果后端目前没把对应事件透传到前端，就先加日志，不要先改飞行逻辑

建议搜索范围：

```bash
rg -n "wayline_failed|takeoff_to_point_progress|cloud_control_auth=\\[\\]|joystick_invalid_notify|drc_status_notify|hms|airsense|mode_code|current_commander_flight_mode" backend/uavfire/logs/cloud-api-sample.log
```

### 第五步：如果要改代码，优先级如下

优先级 1：

- 继续查 `takeoff_to_point -> wayline_failed` 的设备侧根因

优先级 2：

- 补充后端日志，拿到 `takeoff_to_point` 失败前后的更多飞机侧信号
- 查 Pilot 提示与 `cloud_control_auth_update` 的精确时序关系

优先级 3：

- 在确认官方语义和现场时序后，再决定是否需要自动重拿授权或自动重建 DRC

## 10. 明确禁止的做法

禁止：

- 没查官方文档就改高度语义
- 把 `osd.height` 直接当作起飞点高度基线
- 把 `joystick_invalid_notify` 直接当成“云端操控断开”
- 把 `drc_status_notify` 直接当成“授权失效”
- 看到 `services_reply result=0` 就说“起飞成功”
- 在未确认服务启动前就告诉用户“可以测试”

## 11. 本次最重要的结论，给接手的人一句话版本

现在真正的问题不是“网页为什么弹窗”，而是：

**`takeoff_to_point` 的设备侧执行仍然会失败；与此同时，云控授权释放既可能来自设备/Pilot 侧，也可能来自前端触发的会话切换。前端弹“云端操控已断开”只是表现层，不等于根因。**

接下来的工作核心应当是：

**继续查清 `takeoff_to_point` 为什么在飞机侧失败，并继续缩小剩余的授权释放路径。**

## 12. 2026-04-19 晚间新增工作记录

### 12.1 服务与环境确认

晚间这轮实际确认过：

- `6379 / 1883 / 8083` 可用
- 后端 `6789` 已启动
- 前端 `8080` 已启动
- 后端必须使用 `openjdk@11`

对应启动命令：

```bash
export JAVA_HOME="$(brew --prefix openjdk@11)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/likewang/uavfire/backend
mvn -pl uavfire spring-boot:run
```

前端：

```bash
npm --prefix frontend run serve
```

### 12.2 晚间新增的核心判断

这轮排查后，关于“云端操控已断开”的判断要更新：

- 官方文档明确写的是：`heart_beat` 用于维持 DRC 链路活跃
- 文档写明“超过 1 分钟没有 heartbeat，设备会认为 DRC link idle 并退出 DRC”
- 但官方文档没有写“长时间不发飞行指令/摇杆指令就自动释放 flight 授权”

因此不能再说：

- “前端没持续发飞控指令，所以 Pilot 自动释放了云控授权”

更准确的说法是：

- 不持续发 `stick_control` / `drone_control`，不会自动推出“授权一定会被释放”
- 真正已确认需要持续的是 DRC `heart_beat`

### 12.3 晚间最新全流程日志结论

这轮最新测试和前面“立即失败”的测试不同。

关键日志：

- [backend/uavfire/logs/cloud-api-sample.log:29802](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:29802)
- [backend/uavfire/logs/cloud-api-sample.log:29819](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:29819)
- [backend/uavfire/logs/cloud-api-sample.log:29858](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:29858)
- [backend/uavfire/logs/cloud-api-sample.log:30078](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:30078)
- [backend/uavfire/logs/cloud-api-sample.log:30339](/Users/likewang/uavfire/backend/uavfire/logs/cloud-api-sample.log:30339)

时序要点：

1. `21:15:20` 发起 `takeoff_to_point`
2. `21:15:23` 另一路会话触发了 `drc_mode_exit`
3. 紧接着同一路又触发 `cloud_control_release`
4. 所以当场出现 `cloud_control_auth=[]`
5. 随后系统又重新发起 `cloud_control_auth_request + drc_mode_enter`
6. `21:15:25` 起再次明确 `authorized=true`
7. 这次官方起飞不是立刻失败，而是持续 `wayline_progress` 近 1 分钟
8. `21:16:20` 才进入 `wayline_failed`
9. `wayline_failed` 后并没有立刻看到授权释放
10. 到 `21:18:41` 才再次看到 `authorized=false`

这个结论非常关键：

- 最新日志已经证明，至少有一次明确的授权释放并不是“设备自动空闲释放”，而是前端/用户侧会话切换后主动执行了 `drc_mode_exit + cloud_control_release`
- 同时也证明“没有持续人工摇杆指令”本身，不会立刻导致授权丢失，因为重新授权后授权维持了较长时间

### 12.4 晚间新增代码修正

目的：

- 避免官方起飞执行期间，前端自己触发会话切换，进而导致 `drc_mode_exit + cloud_control_release`
- 避免官方起飞执行期间，短暂的 DRC MQTT 波动被前端误处理成主动销毁会话

新增/更新文件：

- [frontend/src/pages/page-web/projects/official-takeoff-session-policy.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/official-takeoff-session-policy.mjs)
  - 官方起飞执行期间锁定遥控会话

- [frontend/src/pages/page-web/projects/drc-connection-policy.mjs](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/drc-connection-policy.mjs)
  - 新增 `officialTakeoffLocked` 分支
  - 官方起飞期间遇到 `close/error` 时优先 `preserve_session`

- [frontend/src/pages/page-web/projects/tsa.vue](/Users/likewang/uavfire/frontend/src/pages/page-web/projects/tsa.vue)
  - 官方起飞执行中禁用“进入遥控 / 重新授权 / 退出遥控”
  - 官方起飞执行中若 DRC 短暂波动，不立刻销毁当前会话

### 12.5 晚间新增验证

已通过：

```bash
node --test frontend/scripts/drc-ws-event-policy.test.mjs frontend/scripts/drc-connection-policy.test.mjs frontend/scripts/official-takeoff-flow.test.mjs frontend/scripts/cloud-control-auth-policy.test.mjs frontend/scripts/official-takeoff-session-policy.test.mjs
```

已通过：

```bash
npm --prefix frontend run build
```

已通过：

```bash
export JAVA_HOME="$(brew --prefix openjdk@11)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl uavfire -Dtest=CloudControlAuthStateResolverTest test
```

### 12.6 如果下一轮还能测试，最该盯什么

下一轮飞行验证优先盯这两类现象：

1. 官方起飞执行中，页面是否还会允许或诱发“重新授权 / 退出遥控”
2. 如果 Pilot 再提示“云端操控已断开”，该提示前后是否真的出现：
   - `cloud_control_auth_update authorized=false`
   - `drc_mode_exit`
   - `cloud_control_release`

如果下一轮已经不再出现前端主动释放授权，但 `wayline_failed` 仍然存在，那排查重心就应完全转向飞机侧失败根因，而不是继续怀疑前端授权状态机。

## 13. 2026-04-19 新工作线：Workspace 内 Livestream + Media

### 13.1 为什么要单独开这条工作线

用户已明确提出：`web 端 workspace 内可用的 livestream + media 页面入口` 是项目必需功能。

这条工作线和前面的 DRC / 官方起飞问题并行，但不是同一个模块。后续接手时不要把这两条线混在一起。

### 13.2 当前仓库现状

已经存在的基础：

- 侧边栏已有 `Livestream` / `Media Files` 入口
- 路由已有 `LIVESTREAM` / `MEDIA`
- `workspace.vue` 已支持 `MEDIA` 右侧覆盖层
- `MediaPanel.vue` 已支持媒体列表和下载
- `livestream-agora.vue` / `livestream-others.vue` 已有官方 demo 风格的直播逻辑
- `api/manage.ts` 已暴露直播相关 API：
  - `getLiveCapacity`
  - `startLivestream`
  - `stopLivestream`
  - `setLivestreamQuality`
  - `changeLivestreamLens`

当前缺口：

- `media.vue` 仍然是空壳
- `livestream.vue` 仍然是 demo 风格的拖拽窗口入口，不适合正式 workspace 指挥面板
- `workspace.vue` 还没有统一接管 livestream 业务面板

### 13.3 为什么直播首期选择 Agora/WebRTC

结合需求说明书，首期推荐用 `Agora/WebRTC` 作为 workspace 主播放链路，而不是先用 `RTMP/RTSP/GB28181`。

依据：

- 需求强调 `Web 指挥端`
- 需求强调 `现场画面直播`
- 需求强调 `视频回传延迟 ≤ 1 秒`
- 需求强调 `多终端数据实时同步`

所以首期判断：

- `Agora/WebRTC` 更适合浏览器直接播放和低延迟指挥场景
- `RTMP/RTSP/GB28181` 更适合作为后续外部视频平台对接/录像转发链路

这不是否定 `GB28181`，而是明确阶段优先级：

- 一期先让 workspace 内直播可用
- 二期再考虑监控平台/国标平台/录像系统联通

### 13.4 已写入的设计规格

本轮已经新增规格文档：

- [docs/superpowers/specs/2026-04-19-workspace-livestream-media-design.md](/Users/likewang/uavfire/docs/superpowers/specs/2026-04-19-workspace-livestream-media-design.md:1)

这份规格写清了：

- 为什么首期优先 `Agora/WebRTC`
- 为什么不先拿 `RTMP/GB28181` 做 workspace 主链路
- `workspace` 内采用“地图为主 + 右侧业务面板覆盖”的结构
- `media` 首期只做列表/分页/下载
- `livestream` 首期只做：
  - 获取能力
  - 选择设备/相机
  - 开始 / 停止
  - Web 播放

### 13.5 当前推荐的实现方向

当前推荐方案是：

- 保留 `workspace` 的地图主视图
- 把 `Livestream` 和 `Media` 都做成右侧正式业务面板
- `Media` 直接复用 `MediaPanel.vue`
- `Livestream` 首期优先复用 `livestream-agora.vue` 的能力和调用链，但要改成 workspace 面板化，而不是沿用当前拖拽 demo 窗口

### 13.6 这条工作线后续怎么接

在用户确认规格后，按下面顺序实现：

1. `workspace.vue` 统一接管 `LIVESTREAM` / `MEDIA` 面板覆盖
2. `media.vue` 从空壳改成正式 route wrapper
3. 新建 workspace 风格的 livestream 面板组件
4. 先打通 Agora 播放链路
5. 再做 build / route / API 调用验证

### 13.7 明确约束

这条工作线首期不要做：

- 多画面同时播放
- 录制
- 截图
- 媒体预览
- 批量下载
- GB28181 平台级接入
- 全量指挥中心大屏化改造

先把 `workspace 内可用` 这件事做实，再往后扩展。

## 14. 2026-04-20 交接给下一阶段的明确执行清单

这一节不是背景说明，而是下一位接手时应该直接执行的顺序。

### 14.1 飞行链路优先级

下一阶段第一优先级仍然是官方起飞两阶段链路，不是 livestream / media。

原因：

- 两阶段飞行虽然已经能进入第二阶段
- 但第一阶段结束后仍存在一段非预期降高
- 这个问题直接影响户外测试安全性和任务可重复性

### 14.2 下一轮现场验证时必须先看什么

每次现场测试结束后，先看下面四条日志，顺序不要乱：

1. `takeoffToPoint request JSON`
2. `takeoffToPointProgress received`
3. `flyToPoint request JSON`
4. `returnHomeInfo event received`

验证目标：

- 第一阶段 `target_height` 不能再是 `30.0`
- 第二阶段 `points[].height` 不能再是 `38.4` 或其他明显相对高值
- 如果仍然出现降高，要先确认降高开始时飞机收到的绝对目标高度到底是多少

### 14.3 下一阶段需要重点回答的技术问题

如果下一轮测试确认请求参数已经是正确绝对高，但第一阶段结束后仍然降高，那么后续就不要再纠缠“前端是不是又发错高度”，而应转到下面两个方向：

1. `takeoff_to_point` 在 RC2 / 当前机型上的真实完成语义  
   重点确认：
   - `wayline_ok`
   - `task_finish`
   - `current_commander_flight_mode`
   - `mode_code`
   在第一阶段结束瞬间到底意味着“悬停待命”，还是“退出指挥飞行并允许飞控自行调整高度”

2. 第一阶段结束到第二阶段发出之间，是否还存在设备侧自动高度收敛  
   重点确认：
   - `returnHomeInfo` 的规划点高度变化
   - OSD `height / elevation / vertical_speed`
   - `current_commander_flight_mode`
   是否出现了“第一阶段结束后飞控自动下撤到安全高度，再接受第二阶段”的设备语义

### 14.4 下一阶段建议的最小代码动作

在再次证明参数已经正确之后，下一阶段代码不要大改，优先做最小观测增强：

- 给第一阶段结束点附近增加更明确的结构化日志：
  - 当前 `mode_code`
  - `current_commander_flight_mode`
  - OSD `height`
  - OSD `elevation`
  - `vertical_speed`
- 给第二阶段发送前增加一条单独日志：
  - 发送时飞机当前位置
  - 当前绝对高
  - 当前相对高
  - 目标绝对高

目标不是先改策略，而是先把“到底是谁让飞机往下掉”的证据链补完整。

### 14.5 运行环境约束

后续接手时要继续遵守下面这组运行约束，否则很容易再次误判：

- 后端必须使用 `openjdk@11`
- 前端启动命令是 `npm --prefix frontend run serve`
- 测试结论必须带绝对时间，不能只说“刚才那次”
- 每次怀疑修复没生效时，先核对：
  - 后端启动时间
  - 当前监听进程 PID
  - 本次飞行对应日志时间

### 14.6 网络配置现状

本轮已把代码默认 IP 改为 `172.20.10.7`，后续若本机网络再次变化，需要同时检查：

- [backend/uavfire/src/main/resources/application.yml](/Users/likewang/uavfire/backend/uavfire/src/main/resources/application.yml:57)
- [backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/RootController.java](/Users/likewang/uavfire/backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/RootController.java:13)
- [frontend/src/api/http/config.ts](/Users/likewang/uavfire/frontend/src/api/http/config.ts:9)

不要只改前端地址或只改 MQTT 地址，否则会再次出现：

- Web 能登录
- Pilot / Thing / MQTT 其中一条链路实际连错地址

### 14.7 如果飞行链路暂时稳定，再切回 Workspace 工作线

只有在两阶段飞行链路基本稳定后，才建议继续第 13 节的 workspace livestream / media 任务。切回那条线时，按下面顺序接：

1. `workspace.vue` 接管 `LIVESTREAM`
2. `workspace.vue` 接管 `MEDIA`
3. 先做 Agora 单路直播可用
4. 再补 media 正式面板

不要在飞行链路还不稳定时，同时大幅推进直播和媒体面板，容易把排障节奏打乱。
