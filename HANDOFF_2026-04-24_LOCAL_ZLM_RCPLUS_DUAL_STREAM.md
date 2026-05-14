# 2026-04-24 交接文档：本机 ZLMediaKit / RC Plus Agent / 真双流当前状态

> 本文档记录 2026-04-24 这一轮联调的真实落地状态。
> 重点覆盖三条线：
> 1. 本机 ZLMediaKit + 前后端 IP 切换到 `172.20.10.7`
> 2. `rcplus-msdk-agent` 与 backend 双流状态链路打通
> 3. “真双流”当前仍卡在热成像第二路导出；但驾驶舱已补上可见光主通道真实播放器，不再只是状态页
>
> 【2026-04-25 勘误】下面这几条与仓库当前实际状态不符，已列在 §十 勘误：
> - IP 切换到 `172.20.10.7` 已被回滚，仓库当前统一在 `192.168.50.254`（本机 wifi）
> - `leadership-cockpit.vue` 使用的是 `ZLMRTCClient.Endpoint`（WebRTC 信令），不是 `jswebrtc.Player`
> - §四.2 / §四.3 关于"驾驶舱没有接真实播放器、不播放视频"的描述不成立，播放器已经接上并在 live tab 渲染 `<video>`

---

## 一、当前结论

截至当前工作区，以下结论已经被运行时证据确认：

1. 本机 WebRTC/RTMP/前后端本地联调环境已经统一切到：
   - 前端：`http://172.20.10.7:8080/`
   - 后端：`http://172.20.10.7:6789/`
   - ZLMediaKit RTMP：`rtmp://172.20.10.7:1935/live/<streamId>`
   - ZLMediaKit WebRTC：`webrtc://172.20.10.7:58925/live/<streamId>`

2. `rcplus-msdk-agent -> backend` 这一条状态链路已经真正打通。
   - `RC_PLUS_LOCAL` 当前会写入 backend 的 `dual-stream:group:RC_PLUS_LOCAL`
   - backend 已能收到 agent 的：
     - `connection_state`
     - `session_state`
     - `live_status`
     - `current_mode`
     - `visible_supported`
     - `thermal_supported`

3. RC Plus 上的“启动双流”已经能走通到运行态，但不是完整真双流：
   - `visible_state = running`
   - `thermal_state = degraded`
   - 原因已被设备侧真实返回：
     - `msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

4. 驾驶舱现在已经具备“可见光主通道真实播放”能力，但不是真双流。
   - backend 会在 `visible_state = running` 且 agent 尚未回传 URL 时，兜底生成 `webrtc://172.20.10.7:58925/live/{droneSn}-0`
   - 驾驶舱 `leadership-cockpit.vue` 已接入 `jswebrtc.Player`，会直接拉 `visiblePlayUrl`
   - 热成像仍然只有状态，没有第二路真实视频 URL

---

## 二、本轮已完成内容

### 1. 本机环境与地址统一

已完成：

- 本机 Docker/Colima 已装好并可用
- 本机 `deployment/zlmediakit` 已启动
- 后端直播配置已切到本机地址 `172.20.10.7`
- 前端 API / WS 地址已切到 `172.20.10.7`
- 相关测试已同步改到 `172.20.10.7`
- 前后端进程已按新配置重启过

关键文件：

- `backend/sample/src/main/resources/application.yml`
- `backend/sample/src/main/java/com/dji/sample/manage/controller/RootController.java`
- `frontend/src/api/http/config.ts`
- `backend/sample/src/test/java/com/dji/sample/manage/service/impl/LiveStreamServiceImplPlaybackUrlTest.java`

已确认不再残留旧直播 IP：

```bash
rg -n "192\.168\.0\.12|1916dn17xs12\.vicp\.fun" backend frontend -g '!**/target/**' -g '!**/node_modules/**'
```

结果：无命中

### 2. 本机 ZLMediaKit 已启动

已完成：

- 本机 `deployment/zlmediakit/.env` 已创建
- `docker compose up -d` 已执行
- 本机 ZLM 页面可访问

运行时验证：

```bash
curl -I http://127.0.0.1:58925/
```

已返回：

- `HTTP/1.1 200 OK`
- `Server: ZLMediaKit(...)`

### 3. `rcplus-msdk-agent` 网络/后端链路已修通

本轮修掉了 3 个真实阻塞点：

#### 3.1 Android cleartext HTTP 被系统策略拦截

旧现象：

- RC Plus logcat 持续报：
  - `java.net.UnknownServiceException: CLEARTEXT communication to 192.168.50.254 not permitted by network security policy`

已修：

- `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`
  - 增加 `android:usesCleartextTraffic="true"`
  - 增加 `tools:replace="android:usesCleartextTraffic"`

说明：

- DJI SDK 自带 manifest 把 `usesCleartextTraffic` 设成了 `false`
- 不 override 的话，agent 对本地 `http://172.20.10.7:6789/` 的请求会被系统直接拦掉

#### 3.2 Android agent 发到 backend 的 JSON 命名不匹配

旧现象：

- backend 只能吃进 `message`
- `connection_state / live_status / current_mode / visible_state / thermal_state` 都丢失

证据实验：

- `camelCase` 请求体只会写入 `status_message`
- `snake_case` 请求体会完整写入双流 group

已修：

- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendApiFactory.kt`
  - Retrofit Gson 改为 `LOWER_CASE_WITH_UNDERSCORES`
  - 抽出 `backendGson()` 供单测验证

#### 3.3 `runtimeLoop` 与前台刷新并发初始化导致后台 tick 挂住

旧现象：

- `runtimeLoop` 日志只停在：
  - `tick start for RC_PLUS_LOCAL`
- 前台按钮 `refresh-device-status` 能返回 `CAPABILITY_READY`
- 但后台心跳不写入 Redis

根因：

- `MainActivity` 启动时会自动执行一次 `refresh-device-status`
- `ProcessLifecycleOwner` 同时会启动 `runtimeLoop`
- 两边并发共享初始化路径，导致后台 tick 卡住

已修：

- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sdk/DjiDeviceSession.kt`
  - 用 `Mutex` 串行化 `initialize()`

### 4. `rcplus-msdk-agent` 当前已真正写入 backend 双流组

当前 Redis 实际结果：

```json
{
  "drone_sn": "RC_PLUS_LOCAL",
  "connection_state": "CAPABILITY_READY",
  "session_state": "INIT",
  "live_status": "INIT",
  "current_mode": "IDLE",
  "status_message": "runtime-loop connection=CAPABILITY_READY session=INIT",
  "playback_status": "awaiting-media-url",
  "visible_supported": true,
  "thermal_supported": true
}
```

重新点击 RC Plus “启动双流”后，状态更新为：

```json
{
  "drone_sn": "RC_PLUS_LOCAL",
  "connection_state": "CAPABILITY_READY",
  "session_state": "RUNNING",
  "live_status": "RUNNING",
  "current_mode": "DUAL",
  "status_message": "runtime-loop connection=CAPABILITY_READY session=RUNNING",
  "visible_state": "running",
  "thermal_state": "degraded",
  "status_reason": "msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding",
  "playback_status": "awaiting-media-url",
  "visible_supported": true,
  "thermal_supported": true
}
```

说明：

- backend 状态面现在已经够用
- 真双流当前的唯一硬阻塞已经从“链路不通”缩到“热成像第二路无法真正导出”

### 5. 驾驶舱可见光直播最小闭环已补齐

本轮新增：

- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/DualStreamServiceImpl.java`
  - 在 `visible_state = running` 且 `visiblePlayUrl` 为空时，兜底生成：
    - `webrtc://172.20.10.7:58925/live/{droneSn}-0`
  - 对应 `playback_status` 会从 `awaiting-media-url` 收敛为 `visible-playback-ready`
- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
  - live tab 不再只是文本状态
  - 已接入 `jswebrtc.Player`
  - 当 `visiblePlayUrl` 存在时，主画面直接播放可见光 WebRTC 流
  - 热成像区域仍保留为状态卡，不伪造不存在的第二路视频

对应测试/验证：

- backend：
  - `mvn -pl sample -Dtest=DualStreamServiceImplTest,LiveStreamServiceImplPlaybackUrlTest,LiveStreamServiceImplAgoraConfigTest test`
  - `BUILD SUCCESS`
- frontend：
  - `npm run build`
  - 构建成功，仅剩历史 Sass / chunk size warning，非本次改动引入

### 6. RC Plus 焦点切换命令已推进到“热成像共享合成预览”

本轮新增的运行时能力：

- `rcplus-msdk-agent` 已不再把 `focus-visible / focus-thermal` 当成 deferred/ignored
- `focus-visible`
  - 会把当前 liveview source 切回非红外视频源
  - 运行态回报 `playback_status = visible-live-ready`
- `focus-thermal`
  - 会把当前 liveview source 切到 `INFRARED_CAMERA`
  - 同时设置：
    - `KeyThermalDisplayMode = PIP`
    - `KeyThermalPIPPosition = SIDE_BY_SIDE`
  - 运行态回报：
    - `playback_status = shared-side-by-side-preview`
    - `status_reason = single-liveview-source-shared-side-by-side-preview`

对应含义：

- 这仍然不是“两条独立视频流”
- 而是基于 DJI 官方文档允许的单 liveview source 约束，退化成“同一条视频流里并排看到可见光+热成像”
- backend 已在 `shared-side-by-side-preview` 模式下把 `thermalPlayUrl` 归一为同一条 `visiblePlayUrl`
- 驾驶舱因此至少能拿到“共享合成预览”的热成像播放地址语义

---

## 三、真双流当前真实状态

### 1. 已确认的能力

RC Plus 上当前真实状态：

- `连接状态: CAPABILITY_READY`
- `可见光: true`
- `红外: true`

点击“启动双流”后的设备侧真实反馈：

- `双流启动结果: applied`
- `可见光: running`
- `红外: degraded`
- `原因: msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

### 2. 当前真正卡住的位置

关键文件：

- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt`

当前实现：

- `bindVisible()` 只注册了 `ComponentIndexType.LEFT_OR_MAIN` 的 receive listener
- `bindThermal()` 先检查 `INFRARED_CAMERA` 是否存在
- 一旦 thermal source 存在，就直接抛：
  - `UnsupportedOperationException("msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding")`

这意味着：

- capability 探测没有问题
- visible 通道监听入口已经有了
- thermal 第二路根本还没有真实实现
- 当前代码是“显式降级”，不是“已实现但失败”

### 3. 不要误判的点

- 这不是 IP 配错
- 这不是 RC Plus 没连上 backend
- 这不是后端没记录双流状态
- 这不是前端没拿到 dual-stream group
- 这就是 MSDK v5 这一层热成像第二路还没有真实导出方案

---

## 四、驾驶舱当前实际状态

### 1. 当前驾驶舱实际做了什么

关键文件：

- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/api/manage.ts`

当前驾驶舱 live tab 已经在读：

- `/manage/api/v1/dual-stream/groups/{droneSn}`

并且现在已经会：

- 用 `jswebrtc.Player` 播放 `visiblePlayUrl`
- 在 `visiblePlayUrl` 缺失时显示状态遮罩
- 在 `visiblePlayUrl` 存在但播放器异常时显示错误遮罩

### 2. 当前驾驶舱没有做什么

它现在没有：
- 创建真实 WebRTC/RTC/JS 播放器
- 把 `visiblePlayUrl` 接进播放器
- 把 `thermalPlayUrl` 接进播放器
- 在 dual-stream live tab 中直接渲染视频元素

页面当前更接近：

- “双流状态驾驶舱”
- 不是“真双路视频驾驶舱”

### 3. 当前无画面的直接根因

根因有两个，缺一不可：

1. backend/agent 现在还没有给出真实 `visiblePlayUrl / thermalPlayUrl`
   - 当前只有：
     - `playback_status = awaiting-media-url`

2. `leadership-cockpit.vue` 当前只展示状态和 URL 字符串，不播放视频

所以这不是一个单点问题，是：

- 播放地址尚未产出
- 驾驶舱播放器也尚未接入

---

## 五、本轮新增/修改的重要文件

### Android / RC Plus

- `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendApiFactory.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentRuntimeLoop.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/App.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/AppServices.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sdk/DjiDeviceSession.kt`
- `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/AgentBackendApiFactoryTest.kt`
- `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/sdk/DjiDeviceSessionTest.kt`

### Backend / Frontend

- `backend/sample/src/main/resources/application.yml`
- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`
- `backend/sample/src/main/java/com/dji/sample/manage/controller/RootController.java`
- `frontend/src/api/http/config.ts`
- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/api/manage.ts`

---

## 六、已执行验证

### 1. 后端直播相关

```bash
cd backend
mvn -pl sample -Dtest=LiveStreamServiceImplPlaybackUrlTest,LiveStreamServiceImplAgoraConfigTest test
```

结果：

- `BUILD SUCCESS`

### 2. Android agent 相关

```bash
cd rcplus-msdk-agent
JAVA_HOME=/usr/local/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests com.yinxin.uavfir.api.AgentBackendApiFactoryTest
JAVA_HOME=/usr/local/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests com.yinxin.uavfir.api.AgentBackendClientTest --tests com.yinxin.uavfir.api.AgentRuntimeLoopTest --tests com.yinxin.uavfir.sdk.DjiDeviceSessionTest
JAVA_HOME=/usr/local/opt/openjdk@17 ./gradlew :app:assembleDebug
```

结果：

- 全部通过

### 3. RC Plus 真机验证

已验证：

- `adb devices -l` 可见 `DJI_RC_PLUS_2`
- 应用已成功覆盖安装
- UI 可见：
  - `连接状态: CAPABILITY_READY`
  - `可见光: true`
  - `红外: true`
- ADB 点击“启动双流”后：
  - `双流启动结果: applied`
  - `可见光: running`
  - `红外: degraded`

### 4. backend 状态验证

已验证：

- `redis-cli GET 'dual-stream:group:RC_PLUS_LOCAL'`
- 能看到双流运行态完整写入

---

## 七、下一位接手建议顺序

### 第一步：把“驾驶舱无画面”先解决到可见光出画

建议目标：

- 先让驾驶舱 live tab 真正接入一个播放器
- 第一阶段只要求可见光路出画
- 不要一上来就绑定“热成像真双路同时出画”

原因：

- 当前 `visible_state=running` 已经有运行态基础
- 驾驶舱当前没有画面是用户可见最大缺口
- 先把“状态驾驶舱”升级成“可见光视频驾驶舱”，收益最大

需要补的最小闭环：

1. backend 或 agent 产出 `visiblePlayUrl`
2. 驾驶舱 live tab 接真实播放器
3. 用本机 ZLMediaKit 先打通一条 Web 可播链路

### 第二步：再继续啃热成像第二路导出

当前要继续研究的核心问题：

- DJI MSDK v5 在当前设备/SDK 组合下，是否存在：
  - 另一种 stream source 切换方式
  - 另一种 camera stream manager API
  - 可先切 source 再抓流，而不是“同时绑定两个 listener”
  - 或必须走 Pilot/云直播链路做第二路转出

关键入口文件：

- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sdk/MsdkKeyValueClient.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/RealMsdkStreamProvider.kt`

### 第三步：再补真正的 `visiblePlayUrl / thermalPlayUrl`

当前 backend group 已有字段：

- `visiblePlayUrl`
- `thermalPlayUrl`

但 agent 仍然始终上传：

- `null`

后续接手人应决定：

1. 是由 Android agent 直接上报
2. 还是由 backend 根据已知 streamId / host / playback 规则拼出来

在“播放地址还没定”的情况下，驾驶舱无法真正出视频。

---

## 八、接手时不要重复排查的点

- 不要再排查 `172.20.10.7` 是否已写进前后端，已经统一
- 不要再排查 RC Plus 是否能连 backend，已经通过
- 不要再排查 cleartext HTTP 是否被 Android 拦，已经修掉
- 不要再排查 dual-stream group 为什么只有 message，snake_case 序列化已经修掉
- 不要再把驾驶舱无画面误判成“前端没拿到 dual-stream 状态”
- 当前真正剩下的是：
  - `visiblePlayUrl / thermalPlayUrl` 未产出
  - 驾驶舱还没接真实播放器
  - 热成像第二路仍未实现真实导出

---

## 九、建议优先查看的文件

- `HANDOFF_2026-04-24_LOCAL_ZLM_RCPLUS_DUAL_STREAM.md`
- `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md`
- `HANDOFF_2026-04-21_M4T_DUAL_STREAM_AND_LIVESTREAM.md`
- `backend/sample/src/main/resources/application.yml`
- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`
- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/DualStreamServiceImpl.java`
- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/components/WorkspaceLivestreamPanel.vue`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendApiFactory.kt`
- `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sdk/DjiDeviceSession.kt`

---

## 十、2026-04-25 勘误（按节顺序）

### §一.1 / §二.1 / §八 - IP 统一到 `172.20.10.7`

**事实更正**：该 IP 切换已经被回滚。仓库当前的真实统一基准是 `192.168.50.254`（Mac 当前 wifi 接口 en0），具体见：

- `backend/sample/src/main/resources/application.yml`（MQTT host、pilot2 web-entry、livestream.playback.webrtc-host、RTMP、GB28181、WHIP 全部为 `192.168.50.254`）
- `frontend/src/api/http/config.ts` + `frontend/env/.env`（baseURL / websocketURL / rtmpURL 全部为 `192.168.50.254`）
- `rcplus-msdk-agent/gradle.properties`（`agentBackendBaseUrl` / `agentMediaHost` 为 `192.168.50.254`）
- `deployment/zlmediakit/.env`、`deployment/zlmediakit/config/config.ini`（`ZLM_PUBLIC_HOST` / `externIP` 为 `192.168.50.254`）

三方推流 / 信令路径在 `192.168.50.254` 上依然完整对齐：

- Agent RTMP publish：`rtmp://192.168.50.254:1935/live/{droneSn}-0`
- Backend 兜底播放地址：`webrtc://192.168.50.254:58925/live/{droneSn}-0`
- 驾驶舱向 ZLM 发的信令：`http://192.168.50.254:58925/index/api/webrtc?app=live&stream={droneSn}-0&type=play`

所以"链路 URL 不对齐"不是可见光出画失败的成因，**下一位不要再花时间在 IP 切换上**。

### §二.5 - 驾驶舱播放器实现

**事实更正**：`leadership-cockpit.vue` 用的是 `ZLMRTCClient.Endpoint`（WebRTC 信令 + `<video>` 元素），不是 `jswebrtc.Player`。`ZLMRTCClient.js` 会通过 `loadZlmRtcClient(streamUrl)` 动态从 ZLM 的 HTTP port 加载。

### §四.2 / §四.3 - 驾驶舱是否接了播放器

**事实更正**：这两节关于"驾驶舱当前没有做什么"的清单里，下面这几条已经做了，不要按旧描述再重建：

- 已创建真实 WebRTC 播放器（`ZLMRTCClient.Endpoint`，`mountPlayerInstance()`）
- 已把 `visiblePlayUrl` 接进播放器（`syncLivePlayers()` → `mountPlayerInstance(livePaneState.value.primary.url, ...)`）
- 已在 dual-stream live tab 中直接渲染 `<video>` 元素（`primaryPlayerShell` / `previewPlayerShell`）

当前"驾驶舱无画面"的真实剩余缺口只有两条：

1. 没有 E2E 验证 agent → ZLM → 驾驶舱 这条管线在 RC Plus 真实推流情况下是否出画；可能需要抓 ZLM 的 `/index/api/getMediaList` / `rtc webrtc/play` 日志核实
2. 如果 RC Plus 没在推流（无人机/设备没连或连接不稳），backend 虽然会兜底 URL、`<video>` 元素也会发信令，但 ZLM 那侧没有 source，信令会失败；这是表现出来的"无画面"

### §七.第一步 - 下一步优先级重排

**事实更正**：第一步目标"先让驾驶舱 live tab 真正接入一个播放器"已经完成，不再是阻塞。下一步应该是：

1. 本机起齐 backend + ZLM + frontend + RC Plus agent，做一次端到端可见光出画联调
2. 如果失败，先抓 ZLM HTTP `/index/api/getMediaList` 和 WebRTC `/index/api/webrtc` 日志，判定是推流没入、还是信令失败、还是 SDP 候选地址不通
3. 再往热成像第二路导出研究推进（§七.第二步，不变）

