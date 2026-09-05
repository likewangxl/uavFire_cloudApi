# 前后端工作记录

## 0. 2026-04-21 M4T Dual-Stream Runtime Closure

本轮新增完成的主线闭环如下：

- `backend/uavfire`：
  - `dual-stream` 已从状态缓存升级为运行时协调器
  - 已具备 `issueCommand / pollCommand / acknowledgeCommand` 最小命令闭环
  - 已具备 task event 的写入、查询、Redis 恢复 fallback
- `rcplus-msdk-agent`：
  - 已具备命令拉取 + ack 最小闭环
  - `CommandPollingCoordinator` 会拉取 backend 命令、执行 `start/stop`、回传 `applied/ignored/failed`
  - 已增加 `droneSn` 不匹配防御，避免误执行到错误设备
  - 命令拉取已对齐 backend `HttpResultResponse.data` 包装层
- `ai-service`：
  - 已新增最小 `TaskRunner`
  - `TaskRegistry.start()` 现在会触发一次真实 runner 执行
  - 已形成 `start -> fusion -> detection event -> backend callback/local query` 最小链路

本轮验证结果：

```bash
cd backend && mvn -pl uavfire -Dtest=DualStreamControllerTest,DualStreamServiceImplTest,CloudControlAuthStateResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest
cd ai-service && ./.venv/bin/python -m pytest tests -q && ./.venv/bin/python -m compileall app
```

结果：

- backend: `16 tests, 0 failures`
- rcplus-msdk-agent: `BUILD SUCCESSFUL`
- ai-service: `11 passed`，`compileall` 通过

当前仍未完成项：

- `rcplus-msdk-agent` 已完成 runtime adapter 与双路绑定桥接入口，但尚未完成真机联调和真实媒体消费链路验证
- `ai-service` 仍是单次 runner，不是持续视频消费循环
- 前端双通道主画面 + 小窗直播仍为暂缓待办

## 0.1. 2026-04-21 RCPlus 真机前置接入桥接

本轮继续把 `rcplus-msdk-agent` 往真机接入方向推进，新增完成：

- `DjiSdkGatewayImpl` 已改成基于 `DjiRuntimeAdapter` 的桥接结构，不再是固定返回值
- `RealMsdkStreamProvider` 已具备 visible / thermal 双路绑定入口和本地绑定状态
- `DualStreamSessionManager` 已补充 provider 失败场景测试，确保绑定失败会落到 `FAILED`

本轮验证结果：

```bash
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiSdkGatewayImplTest" --tests "*DjiDeviceSessionTest" --tests "*RealMsdkStreamProviderTest" --tests "*DualStreamSessionManagerTest" --tests "*CommandPollingCoordinatorTest"
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest
```

结果：

- rcplus focused tests: `BUILD SUCCESSFUL`
- rcplus full unit tests: `BUILD SUCCESSFUL`

## 0.2. 2026-04-21 RCPlus DJI MSDK 工程级接入

本轮继续完成了 `rcplus-msdk-agent` 的 MSDK 工程接入与真机前置桥接，新增完成：

- Android 工程已接入 DJI MSDK 5.17.0 依赖，并升级到 Kotlin 2.1.21 以兼容 DJI SDK 元数据
- `DjiMsdkRuntimeAdapter` 已接入真实 `SDKManager.init/registerApp`、`KeyManager` 能力读取和连接态读取
- `DjiMsdkStreamBinder` 已接入真实 `MediaDataCenter.getInstance().cameraStreamManager`
- `App` 已增加 `attachBaseContext` 兼容安装钩子，优先尝试 `com.cySdkyc.clx.Helper.install(...)`
- Debug APK 已可真实组装，MSDK 工程级依赖与打包链路通过

当前真机行为边界：

- visible 流监听入口已接通
- thermal 流仍然是显式保守实现：
  - 若设备能力里无红外源，直接报 `thermal-stream-source-unavailable`
  - 若设备声明存在红外源，当前会显式抛出 `msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`
- 也就是说，本轮完成的是“真实 SDK 初始化 + 连接态/能力读取 + 真实流绑定入口”，不是“真机双流并播”

本轮验证结果：

```bash
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiMsdkRuntimeAdapterTest" --tests "*DjiSdkGatewayImplTest" --tests "*DjiDeviceSessionTest" --tests "*RealMsdkStreamProviderTest" --tests "*DualStreamSessionManagerTest"
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:assembleDebug
```

结果：

- rcplus focused tests: `BUILD SUCCESSFUL`
- rcplus full unit tests: `BUILD SUCCESSFUL`
- rcplus debug assemble: `BUILD SUCCESSFUL`

当前仍未完成项：

- 仍缺真实 RC Plus / M4T 设备上的首次注册与连接联调
- 仍缺填入有效 `djiApiKey`
- `RealMsdkStreamProvider` 仍未把真实媒体数据向下游转发，只完成了绑定入口
- App 生命周期内的持续命令轮询、心跳和状态上报 loop 还没接通

## 0.3. 2026-04-22 RC Plus / M4T 真机验证与 visible 优先降级策略

本轮已完成真实 RC Plus 2 + M4T 设备验证，并据此调整 `rcplus-msdk-agent` 的双流启动策略。

真机验证结论：

- `Helper.install(Application)` 已接通，`SDKManager` 初始化链路恢复，之前的 `VerifyError` 已消失
- App 在 RC Plus 2 上可正常安装、启动并读取真实设备状态
- 页面已实测进入 `CAPABILITY_READY`
- 当前设备 capability 可读到：
  - `可见光: true`
  - `红外: true`
- 执行 “启动双流” 时，真实结果不是 `thermal-stream-source-unavailable`
- 当前命中的真实限制是：
  - `msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

当前定性：

- MSDK 初始化、注册、连机识别和 capability 读取已完成真机验证
- 红外源能力存在，但 MSDK v5 当前这条 `cameraStreamManager` 路径不支持同 payload 同时绑定 visible/thermal
- 因此双流运行策略需要从“thermal 失败即整体 failed”切换为“visible 优先、thermal 显式降级”

下一步实现方向：

- `RealMsdkStreamProvider.start()` 改为先绑定 visible，再尝试 thermal
- 只要 visible 绑定成功，整体 `start` 视为 `applied`
- thermal 失败时保留 visible 运行中状态，并显式透出降级原因
- UI 与命令执行结果需要同时暴露：
  - visible 已运行
  - thermal 已降级
  - 降级原因原样透传

## 0.4. 2026-04-22 visible 优先降级策略落地完成，待交接

本轮已把上一节的降级策略真实落代码并回归到 RC Plus 2。

已完成：

- `StreamProvider.start()` 已扩展为返回标准化启动结果，而不是仅靠抛异常表达失败
- `RealMsdkStreamProvider` 已调整为：
  - 先绑定 visible
  - thermal 失败时不撤销 visible
  - 返回 visible 运行中、thermal 降级和失败原因
- `DualStreamSessionManager` 已调整为：
  - visible 成功时整体 `start` 返回 `applied`
  - session 进入 `RUNNING`
  - 同时把 thermal 降级原因和分路状态向上透传
- `ValidationConsoleController` 已调整为在 UI 中显式显示：
  - `双流启动结果: applied`
  - `可见光: running`
  - `红外: degraded`
  - `原因: ...`

本轮验证结果：

```bash
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:testDebugUnitTest
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:assembleDebug
adb install -r rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk
```

真实设备界面结果：

```text
双流启动结果: applied
可见光: running
红外: degraded
原因: msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding
```

当前定性：

- Android 设备侧双流启动策略已经稳定收敛为“visible 优先 + thermal 显式降级”
- 当前还没有把该运行态持续回传到 backend 生命周期 loop
- 当前驾驶舱直播画面仍然来自旧的 Agora 单路直播链，不来自 `rcplus-msdk-agent` 新链路

明确交接给下一位时不要误判的点：

- 驾驶舱当前直播不是新双流链路
- 这次改造只完成了 Android 真机接入、能力读取和 visible 优先降级
- 还没有完成 App 生命周期里的心跳/状态 loop
- 也还没有完成“废弃 Agora，驾驶舱直接切到 rcplus 新链路”

## 0.5. 2026-04-22 App 生命周期 runtime loop 接入完成，本机验证通过

本轮继续沿交接计划推进，把 `rcplus-msdk-agent` 的后台上报/轮询能力接进了应用生命周期。

已完成：

- 新增 `AgentRuntimeLoop`
  - 周期性刷新 `DjiDeviceSession`
  - 上报 heartbeat
  - 上报 status
  - 在 capability 变化时上报 capability
  - 轮询 backend command
- 新增 `CommandPoller` 抽象，`CommandPollingCoordinator` 已实现该接口
- 新增 `AgentBackendApiFactory`
  - 用 Retrofit + Gson converter 实例化 `DualStreamApi`
- 新增 `AppServices`
  - 统一持有 `DjiDeviceSession`、`DualStreamSessionManager`、`ValidationConsoleController`
  - 让 UI 与 runtime loop 共用同一套 session state，不再各自 new 一份
- `App` 已接入 `ProcessLifecycleOwner`
  - App 进入前台时启动 runtime loop
  - App 退到后台时停止 runtime loop
- `MainActivity` 已改成从 `AppServices` 读取共享 controller
- 新增 `AGENT_BACKEND_BASE_URL` build config 注入
  - 当前默认值为 `http://127.0.0.1:6789/`

本轮新增测试：

- `AgentRuntimeLoopTest`

本轮验证结果：

```bash
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:testDebugUnitTest --tests "*AgentRuntimeLoopTest" --tests "*CommandPollingCoordinatorTest" --tests "*AgentReporterTest"
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

结果：

- runtime loop focused tests: `BUILD SUCCESSFUL`
- rcplus full unit tests: `BUILD SUCCESSFUL`
- rcplus debug assemble: `BUILD SUCCESSFUL`

当前未完成与注意事项：

- 本轮没有完成 RC Plus 真机复验
  - 原因：当前机器 `adb devices` 为空，没有在线设备
- `AGENT_BACKEND_BASE_URL` 现默认指向 `127.0.0.1:6789`
  - 这对 Android 真机不是最终可用值
  - 真机联调前需要改成 RC Plus 实际可访问的 backend 地址
- 这轮仍未触碰驾驶舱切流
  - 驾驶舱直播仍走旧 Agora 链路
  - 当前只是为后续切流补齐 agent 生命周期闭环前提

## 0.6. 2026-04-22 backend agent 免鉴权修复完成，RC Plus 生命周期 loop 真机回归通过

本轮继续推进 `rcplus-msdk-agent` 的真机联调，目标是确认上一节接入的 runtime loop 能否在 RC Plus 上真实打到本机 backend。

根因定位：

- RC Plus 可访问本机 backend 所在网段地址 `172.20.10.7:6789`
- Android 端 `AGENT_BACKEND_BASE_URL` 已改为 `http://172.20.10.7:6789/`
- 但 backend 之前对 `/manage/api/v1/dual-stream/agents/**` 也套用了统一 `AuthInterceptor`
- `rcplus-msdk-agent` 当前没有登录态，也不会附带 `x-auth-token`
- 所以之前的真机 loop 实际被 backend `401` 拦截，不是设备网络不通

本轮已完成：

- `backend/uavfire` 新增 `GlobalMVCConfigurerTest`
  - 约束只放行 `/manage/api/v1/dual-stream/agents/**`
  - 明确不放行整个 `/manage/api/v1/dual-stream/**`
- `GlobalMVCConfigurer` 已新增：
  - `/" + managePrefix + manageVersion + "/dual-stream/agents/**"`
- `rcplus-msdk-agent/gradle.properties` 已新增：
  - `agentBackendBaseUrl=http://172.20.10.7:6789/`
- backend 已重启到最新代码
- RC Plus 上已重新拉起 `com.yinxin.uavfir`

本轮验证结果：

```bash
cd backend && mvn -pl uavfire -Dtest=GlobalMVCConfigurerTest,DualStreamControllerTest test
curl -i http://127.0.0.1:6789/manage/api/v1/dual-stream/agents/RC_PLUS_LOCAL/command
adb shell am force-stop com.yinxin.uavfir
adb shell am start -n com.yinxin.uavfir/.MainActivity
lsof -nP -iTCP:6789
adb logcat -d | rg "ValidationConsole"
```

结果：

- backend tests: `BUILD SUCCESS`
- 本机直接访问 agent poll 接口已从 `401` 变为 `200`
- `6789` 上可观察到活动连接：
  - `172.20.10.7:6789 -> 172.20.10.7:57938 (ESTABLISHED)`
- RC Plus 侧回归日志显示：
  - `refresh-device-status => 连接状态: CAPABILITY_READY`
  - `可见光: true`
  - `红外: true`
- 本轮设备 logcat 未再出现 `401`、`ConnectException`、`UnknownHostException`

当前定性：

- `rcplus-msdk-agent` 生命周期 loop 已在 RC Plus 上恢复真实 backend 联通
- backend 现在能够接受 agent heartbeat/status/capability/command poll 入口
- 这轮完成的是“agent -> backend 可达且不再被鉴权拦截”
- 还没有完成“驾驶舱可直接查询并消费这组运行态”

下一步：

- backend 需要把 `visible running / thermal degraded / reason` 这组状态更明确地暴露给前端查询面
- 然后再推进驾驶舱从旧 Agora 直播链切到 rcplus 新链路

## 0.7. 2026-04-22 dual-stream 查询面落地，驾驶舱 live tab 已切到 rcplus 运行态

本轮继续推进驾驶舱切流，但先只做“状态面切流”，不伪造不存在的新媒体播放地址。

关键结论：

- 当前仓库仍然没有一条来自 `rcplus-msdk-agent` 的可供 Web 直接播放的新媒体地址
- 驾驶舱之前使用的 `WorkspaceLivestreamPanel` 依赖旧的 Agora 配置和订阅逻辑
- 因此这轮不能诚实地声称“驾驶舱画面已切到新媒体流”
- 本轮完成的是：
  - Android agent 把 `visible/thermal/reason` 真正上报给 backend
  - backend 将这些字段写入 `DualStreamLiveGroupDTO`
  - 驾驶舱 live tab 改为直接读取 dual-stream group 运行态，而不再消费旧 Agora 状态

已完成：

- Android：
  - `DualStreamCommandExecutor` 已增加 `RuntimeStatus`
  - `DualStreamSessionManager.runtimeStatus()` 已暴露 visible/thermal/failureReason
  - `AgentRuntimeLoop` 上报 status 时会携带：
    - `liveStatus`
    - `currentMode`
    - `visibleState`
    - `thermalState`
    - `statusReason`
- backend：
  - `DualStreamAgentStatusDTO` / `DualStreamLiveGroupDTO` 已扩展上述字段
  - `DualStreamServiceImpl.acceptStatus()` 已按非空字段 merge，避免 status 请求把 heartbeat 的连接态覆盖成 `null`
- frontend：
  - `manage.ts` 已新增 `getDualStreamGroup`
  - `leadership-cockpit.vue` 的 live tab 已移除对 `WorkspaceLivestreamPanel` 的依赖
  - 当前 live tab 改为展示：
    - 可见光状态
    - 红外状态
    - 会话状态
    - 连接状态
    - 运行模式
    - capability
    - 最后命令
    - 当前限制原因

本轮验证结果：

```bash
cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd backend && mvn -pl uavfire -Dtest=GlobalMVCConfigurerTest,DualStreamControllerTest,DualStreamServiceImplTest test
cd frontend && npm run build
adb install -r rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk
```

结果：

- Android tests + assemble: `BUILD SUCCESSFUL`
- backend tests: `BUILD SUCCESS`
- frontend build: `vite build` 成功
- RC Plus 上重新安装新 APK 后，设备日志仍显示：
  - `start-dual-stream => 双流启动结果: applied`
  - `原因: msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

当前定性：

- 驾驶舱 live tab 已不再依赖旧 Agora 的运行态判断
- 但驾驶舱当前看到的仍是“rcplus 运行态面板”，不是“新链路真实视频画面”
- 若要完成真正的视频切流，还需要 rcplus/backend 先产出 Web 可直接消费的媒体地址或播放配置

记录日期：2026-04-16

本文档只记录本仓库中的 `frontend/` 和 `backend/` 两个工程。

## 1. 仓库整理

已将当前工作目录中的两个工程整理到 GitHub 仓库：

- `Cloud-API-Demo-Web-main/` -> `frontend/`
- `DJI-Cloud-API-Demo-main/` -> `backend/`

提交时已排除以下内容：

- 前端依赖目录：`node_modules/`
- 前端构建产物：`dist/`
- 后端构建产物：`target/`
- 运行日志：`logs/`、`*.log`
- IDE 元数据：`.idea/`
- 原工程中的 `.git/`

## 2. 前端修改记录

主要文件：

```text
frontend/src/pages/page-web/projects/tsa.vue
```

已完成内容：

- 将原先的模拟摇杆起飞逻辑调整为官方 `takeoff_to_point` 起飞流程。
- 起飞参数使用当前 OSD 中的经纬度作为目标点。
- 起飞流程增加确认提示，避免误认为这是 1 米本地试飞。
- 移除了前端通过持续发送 stick 控制量模拟起飞的旧逻辑。
- 保留 DRC 授权、设备选择、OSD 展示等原页面能力。

当前限制：

- 官方 `takeoff_to_point` 必须传经纬度。
- 官方 `security_takeoff_height` 最小值为 20 米。
- 该接口不是 1 米低空试飞接口。
- 起飞前必须确认 OSD 经纬度有效，否则可能向错误目标点发起起飞。

已验证：

```bash
npm run build
```

构建可以完成，存在既有 Sass 或依赖相关警告。

## 3. 后端修改记录

主要文件：

```text
backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/ControlServiceImpl.java
backend/uavfire/src/main/java/com/yx/uavfire/control/model/param/TakeoffToPointParam.java
```

已完成内容：

- 调整起飞前置检查逻辑。
- 将机场 Dock 的空闲状态检查限定在 Dock 网关上。
- RC Plus 2 场景下不再使用 Dock 专属状态作为起飞前置条件。
- 将 `securityTakeoffHeight` 的校验最小值调整为 20，匹配官方文档限制。

已验证：

```bash
mvn -pl uavfire -DskipTests compile
```

编译可以完成，存在 Maven 配置层面的既有警告。

## 4. 控制台乱码处理记录

后端本地启动验证时，已使用 UTF-8 方式启动并输出日志，确认可以看到可读日志。

建议后续 Windows PowerShell 启动前设置：

```powershell
chcp 65001
$OutputEncoding = [System.Text.UTF8Encoding]::new()
```

如果日志文件仍乱码，需要继续检查：

- 控制台编码。
- JVM 参数 `-Dfile.encoding=UTF-8`。
- Logback 文件编码。
- 终端软件字体和编码设置。

## 5. GitHub 提交记录

目标仓库：

```text
https://github.com/likewangxl/uavFire_cloudApi.git
```

已完成提交：

- `0985186 Initialize UAV fire Cloud API frontend and backend`
- `05744fe Add AI work records`

后续文档修复和运行说明会作为新的提交继续推送。

## 6. 后续排查建议

如果继续排查“点击起飞后遥控器提示云端操控断开”的问题，建议按以下顺序采集证据：

1. 前端点击起飞时实际请求参数。
2. 后端调用 `takeoff_to_point` 前后的日志。
3. MQTT `thing/product/{sn}/services` 发送内容。
4. MQTT `thing/product/{sn}/services_reply` 回复内容。
5. MQTT `thing/product/{sn}/events` 事件内容。
6. 遥控器界面提示和错误码。
7. 起飞前后的 DRC WebSocket MQTT 连接状态。

只有拿到 `services_reply` 或 `events` 的具体错误码，才能判断是参数问题、权限问题、飞行状态问题，还是 DRC 链路被设备侧主动断开。

---

## 7. 2026-04-17 `takeoff_to_point` 调试会话（RC Plus 2 + M4T）

本节记录在 2026-04-17 对 RC Plus 2 遥控器 + Matrice 4T 飞机"正式起飞"按钮的诊断过程，最终形成 commit `f0ef95b` 与 tag `v0.2.0-takeoff-coord-offset`。

### 7.1 错误码推进链路

| 阶段 | 错误码 | 含义 | 拦截点 |
| --- | --- | --- | --- |
| 初始 | `210003` | `DEVICE_TYPE_NOT_SUPPORT` | SDK AOP（`CloudSDKHandler.checkCloudSDK`） |
| 中间 | `336002` | Unknown，`output` 为空 | 飞机飞控预检 |
| 当前 | `336003` | 进入 `takeoff_to_point_progress` 流程后中止 | 飞机飞控预检后半段 |

### 7.2 210003 根因与修复

- 现象：调用 `takeoff_to_point` 直接抛 `CloudSDKException(DEVICE_TYPE_NOT_SUPPORT)`，命令根本未到 MQTT。
- 根因：RC Plus 2 虽然在 `GatewayTypeEnum` 里已单独拆成 `RC2`（见首次提交），但 `AbstractControlService.takeoffToPoint` 的 `@CloudSDKVersion(exclude = GatewayTypeEnum.RC)` 注解本来就只排除旧 RC，不排除 RC2；然而现场实测 `SDKManager` 识别出的网关类型有时仍为 `RC`，被 AOP 拦截。
- 修复：去掉 `exclude = GatewayTypeEnum.RC`，放开 SDK 层拦截，改由飞机端判定。见 `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/control/api/AbstractControlService.java`。
- 关键踩坑：`mvn -pl uavfire ... compile` 不会重新编译 cloud-sdk 模块。sample 运行时仍使用 `D:\localRepository` 里的旧 cloud-sdk-1.0.3.jar，修改看起来"没生效"。必须先 `mvn -pl cloud-sdk clean install` 刷本地仓库。

### 7.3 336002 根因与修复

- 现象：命令成功下发，飞机回 `services_reply.result=336002`，`output` 为空，MQTT 链路完全正常。
- 排查过程：
    1. 在三个位置增加详细诊断日志（ControlServiceImpl、MqttGatewayPublish、ServicesReplyHandler），确认请求 JSON 真正下发到飞机的字段名、值与 DJI 文档一致。
    2. 观察到飞机**推送了 `takeoff_to_point_progress` 事件**（说明飞机进入了起飞流程），然后才回 336002，排除"飞机不支持此命令"这一猜想。
    3. 对比 DJI 文档字段范围，将起飞参数从边缘值调整为推荐值：`max_speed` 1 → 5（DJI 范围 2-15），`rth_altitude` 20 → 100，`target_height` / `security_takeoff_height` 20 → 30。
    4. 调整后仍 336002，但起飞前 OSD 日志显示 target 坐标 = 当前 OSD 坐标（水平距离 = 0）。
- 根因：M4T 飞控在**目标点等于当前点（零水平距离）**场景下直接判为无效目标，拒绝起飞，并在 `output` 里不返回任何细节。
- 修复：前端 `handleTakeoff` 把目标点相对当前 OSD 位置向北偏约 16 m（`latitude + 0.00015°`）。修改后错误码从 336002 → 336003，并出现持续的 `takeoff_to_point_progress` 事件推送，证明已进入飞控预检流程。

### 7.4 336003 现状（未完成项）

- 现象：发送一条 `takeoff_to_point`，SDK 内置 3 秒超时会重发共 3 条（11:23:42 / 45 / 48）。飞机回 3 条 336003，同时持续推送 `takeoff_to_point_progress`。高度 OSD 显示海拔从 458.55 轻微上升到峰值 458.93（抬升约 0.4-0.7 m），随后回落——未真正起飞。
- 判断：第一条指令被飞机接受并进入 `takeoff_to_point` 流程，预检阶段因某种现场条件中止；后两条因"命令重复"被 336003 拒掉。
- 未解决原因：`services_reply.output` 为空，飞控层的拒飞原因只能在 RC Plus 2 屏幕上读取橙色/红色横幅提示（如电量过低、指南针需校准、障碍物检测、头顶遮挡等）。
- 现场需核查项（下次测试前逐项确认）：
    - [ ] 电池电量 ≥ 50%（历史日志出现过 35%）
    - [ ] GPS 状态为 GPS 固定（非 ATTI 或搜索中），卫星数 ≥ 10
    - [ ] 指南针无需校准（换地点或强磁场后通常需重新校准）
    - [ ] 遥控器新手模式已关闭
    - [ ] 起飞点头顶 30 m、周围 30 m 内无障碍物（垂直爬升高度为 `security_takeoff_height=30`）
    - [ ] 物理摇杆未被触碰
    - [ ] 遥控器屏幕出现的任何横幅提示文本

### 7.5 本次代码改动清单

| 文件 | 改动 |
| --- | --- |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/control/api/AbstractControlService.java` | 移除 `takeoffToPoint` 的 `exclude = GatewayTypeEnum.RC`；加注释说明原因 |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/mqtt/MqttGatewayPublish.java` | publish 日志从 debug 升到 info，用 `ObjectMapper.writeValueAsBytes` 序列化真实下发字节 |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/mqtt/services/ServicesReplyHandler.java` | `services_reply raw payload` 从 debug 升到 info |
| `backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/ControlServiceImpl.java` | `takeoffToPoint` 增加入参 / 网关类型 / 请求 JSON / `reply.output` 四段诊断日志 |
| `frontend/src/pages/page-web/projects/tsa.vue` | 起飞参数改为 30/30/5/100；目标点北偏 `0.00015°` (~16 m)；确认弹窗同时显示当前与目标坐标 |

### 7.6 诊断日志关键位置

启动后端后，`backend.log` 中排查起飞问题应优先关注：

```
ControlServiceImpl         : takeoffToPoint called. sn=..., param=...
ControlServiceImpl         : takeoffToPoint gateway info. gatewayType=RC2, sdkVersion=V0_0_1
ControlServiceImpl         : takeoffToPoint request JSON. json={...}
WebSocketMessageSend       : MQTT send topic: thing/product/.../services, payload: {...}
ServicesReplyHandler       : services_reply raw payload: {...}
ControlServiceImpl         : takeoffToPoint reply. result={errorCode=..., errorMsg=...}, output=...
```

配合 OSD 日志中飞机的 `latitude / longitude / height` 时间序列，可以重建完整飞行事件链路。

### 7.7 Git 备份信息

- 提交：`f0ef95b` on `main`
- Tag：`v0.2.0-takeoff-coord-offset`（annotated tag，带详细说明）
- 推送至：`https://github.com/likewangxl/uavFire_cloudApi`
- 回滚方式：`git checkout v0.2.0-takeoff-coord-offset`
- Tag 标注的状态：210003 已解除 / 336002 已通过坐标偏移绕过 / 错误码推进到 336003 待 RC 屏幕抓提示

### 7.8 下一步工作建议

1. **先处理现场条件**：按 7.4 清单逐项核查，电量、GPS、指南针是最容易忽视的三项。
2. **RC 屏幕抓图**：下次点击起飞失败瞬间，立即对遥控器屏幕拍照，记录横幅提示文字。这是目前定位飞控拒飞原因的唯一途径。
3. **考虑 SDK 重试去重**：当前 SDK 3 秒超时会发 3 条同样的 `takeoff_to_point`，飞机对第 2、3 条必然 336003。如果影响排查，可在 sample 层把 `retryCount` 传 0（单次发布）。
4. **OSD 原始字段诊断**：如需确认飞机上报的卫星数、电量、飞行模式等具体字段值，可把 `OsdRouter` 中 `log.debug("OSD原始数据 [{}] keys: {}", ...)` 临时升为 `log.info`，或在 `application.yml` 打开 `logging.level.com.dji.sdk.mqtt.osd=DEBUG`。

---

## 8. 2026-04-17 功能扩展：点选飞行 / 航线规划 可行性摸底（M4T + RC Plus 2 + DRC）

记录日期：2026-04-17（与 §7 同一日）

本节目标：在没有现场测试环境时，先把 `fly_to_point` 与点选飞行/航线规划 的资料、代码、验证步骤整理清楚，落到代码与文档两处，供下次飞行直接验证。

### 8.1 现有代码盘点

前端（`frontend/src/api/drone-control/drone.ts`）已封装：

| 函数 | 路由 | 入参 |
| --- | --- | --- |
| `postFlyToPoint(sn, body)` | `POST /control/api/v1/devices/{sn}/jobs/fly-to-point` | `{ max_speed, points: [{ latitude, longitude, height }] }` |
| `deleteFlyToPoint(sn)` | `DELETE /control/api/v1/devices/{sn}/jobs/fly-to-point` | 无 |
| `postTakeoffToPoint(sn, body)` | `POST /control/api/v1/devices/{sn}/jobs/takeoff-to-point` | 见 §7 |

前端 `components/g-map/DroneControlPanel.vue`（Dock 场景使用）已经有基于 `useDroneControl().flyToPoint` 的 "点选/手动输入 lat/lng/height" Popover，但该面板只在 Dock workspace 下挂载，RC Plus 2 场景看不到。

前端 `components/g-map/use-drone-control-ws-event.ts` 已经在处理 `EBizCode.FlyToPointProgress` 事件（54 行附近），WS 链路已经通。

后端 `cloud-sdk/...AbstractControlService.flyToPoint` 上的 `@CloudSDKVersion` 注解**不做** `exclude = GatewayTypeEnum.RC` 过滤，换言之 SDK AOP 层不会因为网关是 RC / RC2 而拦截该命令。实际可用性最终由飞机飞控决定。

### 8.2 DJI 文档 vs 实际代码差异

- DJI Cloud API 1.9.0 官方文档将 `fly_to_point` 放在 Dock 场景章节下，未明确标注 RC 场景可用。
- 但 `cloud-sdk` 与后端 sample 的实际代码允许任何网关类型调用。与 §7 里 `takeoff_to_point` 的情况一致："**SDK 允许，飞机飞控判定**"。
- 合理推测：RC2 + DRC 下 `fly_to_point` 大概率可用（本质是 commander_flight 模式下飞控接受目标点），但需要实飞验证。

### 8.3 适用前置条件

调用 `fly_to_point` 前必须全部满足：

- 飞机已经**空中**（由 `takeoff_to_point` 或手动起飞得来），`mode_code` 处于"在飞"状态
- DRC WebSocket 仍处于连接状态（`remoteControlState.connected === true`）
- 目标点与当前 OSD 位置**水平距离 ≥ 16 m**（避免重演 336002）
- 目标高度（相对起飞点）≥ `security_takeoff_height`，推荐与当前悬停高度一致以避免额外爬升

### 8.4 验证计划（下次飞行需按序完成）

1. 通过 tsa.vue "Official Takeoff" 起飞，悬停 30 m，确认 OSD `height ≈ 30`。
2. 点击新增的 "Fly Forward 20m" 测试按钮（见 §8.5），观察：
   - 后端 `services` MQTT 发送的 JSON（`ControlServiceImpl` 日志）
   - `services_reply.result` 与 `output`
   - RC Plus 2 屏幕是否出现拒飞横幅
3. 若 `result=0`，再点击 "Fly To Point (Manual)" 手动输入远距离点，验证多次调用是否稳定。
4. 若 `result ≠ 0`，记录错误码并对照 §7 错误码表，重点留意 `210003`（SDK 层拒 → 需去掉注解） / `336xxx`（飞控拒 → 查屏幕）。
5. 最后点 "Stop Fly To Point" 调用 `deleteFlyToPoint`，确认飞机停止平移并悬停。

---

## 9. 2026-04-21 M4T 双流专项续作：rcplus-msdk-agent 环境解锁与 Task 3-5

记录日期：2026-04-21

本节记录基于 `HANDOFF_2026-04-21_M4T_DUAL_STREAM_AND_LIVESTREAM.md` 和 `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md` 继续推进的结果，范围聚焦 `rcplus-msdk-agent/`。

### 9.1 构建环境根因与处理

- 初始失败不在代码，而在本机环境：
  - shell 默认 `java -version` 为 Java 8
  - Gradle Wrapper 实际拾取的是 Homebrew Java 11
  - Android Gradle Plugin 8.5.2 因此报 `Android Gradle plugin requires Java 17 to run`
- 现场核实后确认：
  - `openjdk@17` / `openjdk@21` 当时并未真正安装
  - Android SDK 位置也未配置，后续进一步暴露出 `SDK location not found`
- 已完成的环境修复：
  - 安装 `openjdk@17`
  - 安装 `android-commandlinetools`
  - 安装 `android-platform-tools`
  - 通过 `sdkmanager` 安装：
    - `platform-tools`
    - `platforms;android-34`
    - `build-tools;34.0.0`
  - 接受 Android SDK licenses

本轮用于验证的环境变量：

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools
```

### 9.2 rcplus-msdk-agent Task 2 最终状态

- `rcplus-msdk-agent` 最小 Android app 壳已在当前机器真实构建通过
- 验证命令：

```bash
cd rcplus-msdk-agent
./gradlew :app:assembleDebug
```

- 结论：
  - 交接文档中“先解 Java 环境”的阻塞已解除
  - 现阶段可以继续执行 Task 3 之后的 Kotlin / Android 实现

### 9.3 Task 3：状态模型 + MockStreamProvider + DualStreamSessionManager

新增文件：

- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/AgentConnectionState.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionState.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/StreamChannelType.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/StreamProvider.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/MockStreamProvider.kt`
- `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt`

同步调整：

- `rcplus-msdk-agent/app/build.gradle.kts`
  - 增加 `kotlinx-coroutines-test` 以支持 `runTest`

已完成内容：

- 固化 `AgentConnectionState` / `DualStreamSessionState` / `StreamChannelType`
- 抽出 `StreamProvider` 接口
- 提供始终成功的 `MockStreamProvider`
- 用 `MutableStateFlow` 实现最小 `DualStreamSessionManager`
- 通过 TDD 先写失败测试，再补最小实现

验证：

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests "*DualStreamSessionManagerTest"
./gradlew :app:assembleDebug
```

### 9.4 Task 4：MSDK 能力查询抽象

新增文件：

- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/CameraCapability.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGateway.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/CapabilityRepository.kt`
- `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/CapabilityRepositoryTest.kt`

同步调整：

- `rcplus-msdk-agent/app/build.gradle.kts`
  - 增加 `retrofit`
  - 增加 `okhttp`
  - 增加 `kotlinx-coroutines-core`

已完成内容：

- 定义 `CameraCapability` 能力模型
- 定义 `DjiSdkGateway` 初始化与能力读取接口
- 提供 `DjiSdkGatewayImpl` 安全占位实现
  - 当前不伪造真机集成结果
  - `initialize()` 返回 `false`
  - `loadCapability()` 返回 visible / thermal 均为 `false`
- 提供 `CapabilityRepository`
  - 先尝试 `initialize()`
  - 初始化失败时回退到全 `false`
  - 初始化成功时转发 `loadCapability()`

验证：

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests "*CapabilityRepositoryTest"
./gradlew :app:assembleDebug
```

### 9.5 Task 5：RC 执行层与后端 HTTP 契约

新增文件：

- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/DualStreamApi.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentHeartbeatRequest.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentStatusRequest.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/CapabilityReportRequest.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentBackendClient.kt`
- `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentBackendClientTest.kt`

已完成内容：

- 固化三类请求体：
  - heartbeat
  - status
  - capability report
- 增加 `DualStreamApi` Retrofit 契约，占位三条内部接口：
  - `/internal/dual-stream/agents/{droneSn}/heartbeat`
  - `/internal/dual-stream/agents/{droneSn}/status`
  - `/internal/dual-stream/agents/{droneSn}/capabilities`
- 增加 `AgentBackendClient`
  - 负责组装 heartbeat/status/capability 三类请求体
  - 第一版不做轮询发送
- 通过 TDD 先写失败测试，再补最小实现

验证：

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest --tests "*AgentBackendClientTest"
./gradlew :app:assembleDebug
```

### 9.6 当前专项进度结论

- `rcplus-msdk-agent`：
  - Task 2 已真实构建通过
  - Task 3 已完成
  - Task 4 已完成
  - Task 5 已完成
- `ai-service`：
  - 仍停留在工程骨架阶段，Task 6-9 尚未开始

### 9.7 当前遗留风险

- 本机构建目前依赖显式导出 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT`
- `DjiSdkGatewayImpl` 还是安全占位，不代表已接入真实 DJI MSDK
- `MockStreamProvider` 当前永远成功，`FAILED` 分支与更复杂会话约束仍未覆盖
- `AgentBackendClient` 当前只组装 DTO，不做真实发送和轮询

### 9.8 ai-service Task 6-9：FastAPI 骨架、生命周期、融合占位与运行配置

本节记录 `ai-service/` 按专项计划继续推进的结果。

#### Task 6：FastAPI 入口与任务 API 骨架

新增文件：

- `ai-service/app/__init__.py`
- `ai-service/app/main.py`
- `ai-service/app/api/__init__.py`
- `ai-service/app/api/routes.py`
- `ai-service/app/models/__init__.py`
- `ai-service/app/models/task.py`
- `ai-service/app/services/__init__.py`
- `ai-service/app/services/task_registry.py`
- `ai-service/tests/test_routes.py`

同步调整：

- `ai-service/pyproject.toml`
  - 增加测试依赖声明：`pytest`、`httpx`

已完成内容：

- 提供最小 FastAPI `app`
- 提供 `/healthz`
- 提供 `POST /api/v1/dual-stream/tasks`
- 任务先保存在进程内 `TaskRegistry`

验证：

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_routes.py -q
```

#### Task 7：任务生命周期与事件模型

新增文件：

- `ai-service/app/models/event.py`
- `ai-service/tests/test_task_lifecycle.py`

修改文件：

- `ai-service/app/models/task.py`
- `ai-service/app/api/routes.py`
- `ai-service/app/services/task_registry.py`

已完成内容：

- 增加 `DualStreamTaskStatus`
  - `created`
  - `running`
  - `stopped`
  - `failed`
- `TaskRegistry` 补齐：
  - `create`
  - `start`
  - `stop`
  - `get`
  - `list_events`
- 路由层补齐：
  - `POST /api/v1/dual-stream/tasks/{task_id}/start`
  - `POST /api/v1/dual-stream/tasks/{task_id}/stop`
  - `GET /api/v1/dual-stream/tasks/{task_id}`
  - `GET /api/v1/dual-stream/tasks/{task_id}/events`

验证：

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_routes.py tests/test_task_lifecycle.py -q
```

#### Task 8：视频源 / 可见光 / 红外 / 融合占位

新增文件：

- `ai-service/app/video/__init__.py`
- `ai-service/app/video/source.py`
- `ai-service/app/inference/__init__.py`
- `ai-service/app/inference/visible/__init__.py`
- `ai-service/app/inference/visible/detector.py`
- `ai-service/app/inference/thermal/__init__.py`
- `ai-service/app/inference/thermal/analyzer.py`
- `ai-service/app/fusion/__init__.py`
- `ai-service/app/fusion/service.py`
- `ai-service/app/models/frame.py`
- `ai-service/tests/test_fusion_service.py`

修改文件：

- `ai-service/app/models/event.py`
  - 增加 `DualStreamEvent`

已完成内容：

- 定义 `VideoSource` 协议
- 定义 `FramePacket`
- 提供规则占位版：
  - `VisibleDetector`
  - `ThermalAnalyzer`
- 提供最小 `DualStreamFusionService`
  - `fusion_score = visible * 0.6 + thermal * 0.4`
  - 风险等级：
    - `HIGH`
    - `MEDIUM`
    - `LOW`

验证：

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_fusion_service.py -q
```

#### Task 9：运行配置与本地启动入口

新增文件：

- `ai-service/app/config/__init__.py`
- `ai-service/app/config/settings.py`
- `ai-service/scripts/run-dev.sh`
- `ai-service/tests/test_settings.py`

修改文件：

- `ai-service/README.md`
- `ai-service/pyproject.toml`
  - 增加运行配置依赖：`pydantic-settings`

已完成内容：

- 提供 `Settings(BaseSettings)`：
  - `host=0.0.0.0`
  - `port=9000`
  - `log_level=INFO`
  - `max_concurrent_tasks=2`
- 提供 `run-dev.sh`
  - 直接使用项目 `.venv` 启动 `uvicorn`
- README 补齐本地运行与健康检查说明

验证：

```bash
cd ai-service
./.venv/bin/python -m pytest tests/test_settings.py -q
./.venv/bin/python -m compileall app
./scripts/run-dev.sh
curl http://127.0.0.1:9000/healthz
```

实际健康检查返回：

```json
{"status":"ok"}
```

#### 当前 ai-service 阶段结论

- Task 6 已完成
- Task 7 已完成
- Task 8 已完成
- Task 9 已完成
- 当前已具备：
  - 可运行的 FastAPI 最小骨架
  - 任务创建 / start / stop / query / events
  - 双流融合占位评分
  - 本地运行脚本和配置默认值

#### 当前 ai-service 遗留风险

- `TaskRegistry` 仍是进程内内存存储，未做持久化
- 未做重复 `task_id` / 非法状态迁移 / 并发访问保护
- `VisibleDetector` / `ThermalAnalyzer` 仍是规则占位，不代表真实模型效果
- 真实视频流读取、模型权重接入、GPU 优化、生产级任务调度仍未开始

### 9.9 下一轮计划与交接入口

为下一轮“真机接入方案 + 后端对接”已经补齐两份文档：

- 实施计划：
  - `docs/superpowers/plans/2026-04-21-m4t-device-backend-integration.md`
- 最新交接：
  - `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md`

使用建议：

1. 先读最新 handoff，确认“已完成 / 已验证 / 未验证 / 下一轮边界”。
2. 再按新 plan 执行后端协调层、rcplus 主动上报、真机前置接入和 AI 回传。
3. 不要再以旧 handoff 中“Java 17 未解决 / ai-service 仅骨架”为准，那些状态已经过期。

---

## 9. 2026-04-21 当前工作进度总记录（直播驾驶舱 / Agora 动态 Token / 前端中文化 / M4T 双流专项）

记录日期：2026-04-21

本节补充 2026-04-21 的连续开发内容，方便后续继续接手。涉及前端、后端，以及仓库内新增的两个 PoC 子工程目录。

### 9.1 已完成：领导驾驶舱直播集成

目标：把现有 `livestream` 页的 Agora/WebRTC 直播能力复用到 `leadership-cockpit` 页面，并保留直播 HUD。

已完成内容：

- 在 `leadership-cockpit` 主视觉卡片内新增 `态势图 / 直播画面` tab 切换。
- 直播 tab 复用共享组件 `frontend/src/components/WorkspaceLivestreamPanel.vue`，保留 HUD。
- 切换到直播时，卡片底部 KPI 改为直播态指标，而不是继续显示态势图指标。
- 处理了两轮布局问题：
  - 卡片内部固定高度导致直播下半部分被裁切。
  - 全局 `overflow: hidden` 导致页面整体不可滚动。
- 现状是 `leadership-cockpit` 页面可以切到直播画面，并能完整向下滚动查看内容。

主要文件：

- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/components/WorkspaceLivestreamPanel.vue`
- `frontend/src/pages/page-web/home.vue`
- `frontend/scripts/leadership-cockpit-livestream.test.mjs`

已验证：

```bash
node --test frontend/scripts/leadership-cockpit-livestream.test.mjs
npm --prefix frontend run build
```

说明：

- 构建通过。
- 仍有仓库原有的 Sass 弃用警告、`::v-deep` 警告和 chunk size warning，不是本轮新增错误。

### 9.2 已完成：Agora token 改为后端动态生成

背景：

- 原实现把一个临时 Agora token 写死在 `application.yml` 中返回给前端。
- 运行一段时间后会出现：

```text
AgoraRTCError CAN_NOT_GET_GATEWAY_SERVER: dynamic key or token timeout
```

已完成内容：

- 后端 `/manage/api/v1/live/agora/config` 已改为每次请求动态生成新的短时 RTC token。
- 前端接口契约未改，仍然拿 `appid / channel / token`。
- 推流启动路径也同步改为动态签发。

主要文件：

- `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/LiveStreamServiceImpl.java`
- `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/livestream/LivestreamAgoraUrl.java`
- `backend/uavfire/pom.xml`
- `backend/uavfire/src/main/resources/application.yml`
- `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/impl/LiveStreamServiceImplAgoraConfigTest.java`

已验证：

```bash
mvn -pl uavfire -am -Dtest=LiveStreamServiceImplAgoraConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

说明：

- 后端本地启动已切到动态 token 版本。
- `application.yml` 中目前已经写入 Agora `app-certificate`，功能可用，但安全上后续建议迁到环境变量，避免长期留在本地配置文件里。

### 9.3 已完成：前端中文化替换

目标：

- 不做完整国际化框架。
- 直接把前端用户界面中能看到的英文、前端自身提示和关键调试日志改成中文。

已完成内容：

- Web 端、Pilot 端、直播组件、工作台、导航、常见业务页、提示文案做了大范围中文化。
- “领导驾驶舱”显示文案进一步改为“驾驶舱”。

代表文件：

- `frontend/src/pages/page-web/index.vue`
- `frontend/src/components/common/topbar.vue`
- `frontend/src/components/common/sidebar.vue`
- `frontend/src/pages/page-web/projects/livestream.vue`
- `frontend/src/components/WorkspaceLivestreamPanel.vue`
- 以及大量 Web/Pilot 页面

专项回归：

- `frontend/scripts/frontend-chinese-copy.test.mjs`

已验证：

```bash
node --test frontend/scripts/frontend-chinese-copy.test.mjs
node --test frontend/scripts/leadership-cockpit-livestream.test.mjs
npm --prefix frontend run build
```

### 9.4 已完成：M4T 双流专项 spec / plan 文档

用户提供文档：

- `m_4_t双流直播与火情识别专项详细设计方案（评审修订版）.md`

本轮已完成设计收敛与实施计划：

- 设计文档：
  - `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
- 实施计划：
  - `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`

设计结论：

- 在当前仓库内新增两个独立子工程：
  - `rcplus-msdk-agent/`
  - `ai-service/`
- AI 第一阶段只承诺：
  - 可见光火焰/烟雾检测
  - 红外伪彩图热点复核
  - 双流时间对齐与融合打分
- 不承诺：
  - 无真机情况下完成正式双流商用能力
  - 无热矩阵时做真实温度分析

### 9.5 已完成：Task 1 子工程骨架落地

已新增：

- `rcplus-msdk-agent/settings.gradle.kts`
- `rcplus-msdk-agent/build.gradle.kts`
- `rcplus-msdk-agent/gradle.properties`
- `rcplus-msdk-agent/README.md`
- `ai-service/pyproject.toml`
- `ai-service/README.md`
- `ai-service/.env.example`
- 根 `README.md` 已补“`双流 PoC 子工程`”说明

当前状态：

- 两个目录已经正式存在于仓库中。
- 还只是工程级骨架，不代表可运行实现已经完成。

### 9.6 已完成：Task 2 Android 最小 app 壳 + wrapper

已新增：

- `rcplus-msdk-agent/app/build.gradle.kts`
- `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/App.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/MainActivity.kt`
- `rcplus-msdk-agent/app/src/main/res/layout/activity_main.xml`
- `rcplus-msdk-agent/app/src/main/res/values/strings.xml`
- `rcplus-msdk-agent/app/src/main/res/values/themes.xml`
- `rcplus-msdk-agent/gradlew`
- `rcplus-msdk-agent/gradlew.bat`
- `rcplus-msdk-agent/gradle/wrapper/gradle-wrapper.properties`
- `rcplus-msdk-agent/gradle/wrapper/gradle-wrapper.jar`

已完成的真实验证：

```bash
cd rcplus-msdk-agent
./gradlew :app:assembleDebug
```

当前真实结果：

- wrapper 已正常工作，不再因缺少 `gradlew` 失败。
- 构建已经进入 Android Gradle Plugin 启动阶段。
- 当前阻塞不是代码文件缺失，而是本机 Java 版本。

当前关键报错：

```text
Android Gradle plugin requires Java 17 to run. You are currently using Java 11.
Your current JDK is located in /usr/local/Cellar/openjdk@11/11.0.30/libexec/openjdk.jdk/Contents/Home
```

额外环境事实：

- 默认 `java -version` 仍是 1.8。
- 机器上能找到：
  - Java 8
  - OpenJDK 11
  - OpenJDK 25
- 没有现成的 Java 17。
- 之前尝试用 Java 25 跑 Gradle 8.7 时，Kotlin DSL 配置阶段报 `java.lang.IllegalArgumentException: 25.0.2`，因此不能把 Java 25 当成稳定替代。

当前结论：

- Task 2 规格已通过，定性为“代码壳完成，当前受本机 JDK 环境阻塞”。
- 下一位接手人应优先补一个可用的 Java 17/21 环境，再继续构建验证。

### 9.7 尚未开始或未完成的内容

以下内容还没有进入实做：

- `rcplus-msdk-agent` Task 3：
  - 双流状态模型
  - `MockStreamProvider`
  - `DualStreamSessionManager`
- `rcplus-msdk-agent` Task 4：
  - MSDK 能力查询抽象
- `rcplus-msdk-agent` Task 5：
  - 与后端的 heartbeat / status / capability HTTP 契约
- `ai-service` Task 6-9：
  - FastAPI app
  - 任务生命周期
  - 视频源抽象
  - visible / thermal / fusion 占位实现
  - 配置与本地运行入口

换言之：

- `ai-service/` 现在还只有工程级骨架，没有 Python 服务代码。
- `rcplus-msdk-agent/` 现在只到最小 Android app 壳。

### 9.8 当前最建议的接手顺序

下一位接手建议严格按下面顺序做：

1. 先解决 `rcplus-msdk-agent` 的 Java 17/21 本地环境问题。
2. 重新执行：

```bash
cd rcplus-msdk-agent
./gradlew :app:assembleDebug
```

3. 如果继续失败，确认是否转为 Android SDK / Build Tools 缺失；把阻塞写回交接文档。
4. 然后按 `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md` 继续 Task 3、Task 4、Task 5。
5. RC 侧状态机和 API 契约稳定后，再开始 `ai-service` 的 Task 6-9。

### 9.9 本轮新增 / 关键文档索引

本轮后续接手最应该先看的文件：

- 交接与记录：
  - `WORK_RECORD.md`
  - `HANDOFF_2026-04-21_M4T_DUAL_STREAM_AND_LIVESTREAM.md`
- 专项设计 / 计划：
  - `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
  - `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`
- 驾驶舱直播：
  - `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
  - `frontend/src/components/WorkspaceLivestreamPanel.vue`
  - `frontend/src/pages/page-web/home.vue`
- Agora 动态 token：
  - `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/LiveStreamServiceImpl.java`
  - `backend/uavfire/src/main/resources/application.yml`
- 新子工程：
  - `rcplus-msdk-agent/`
  - `ai-service/`

---

## 9. 2026-04-17 功能收口：planned wayline 稳定性增强（持久化 / 恢复）

记录日期：2026-04-17

本节记录在 §8 第一版 `planned wayline` 原型基础上，继续做的“可恢复性”收口，目标是降低页面切换或浏览器刷新后丢失规划数据的风险。

### 9.1 本轮问题判断

- §8 的实现已经能在前端完成“点选航点 -> 顺序 `fly_to_point` 执行”，但所有规划数据都只保存在运行时内存中。
- 一旦刷新页面、热更新、浏览器崩溃或用户误切页面，目标机选择、默认高度、速度、航点列表都会丢失。
- 这类问题不会影响单次 demo 演示，但会直接影响下一次实飞排查效率，因此优先级高于继续堆新按钮。

### 9.2 本轮代码改动

| 文件 | 改动 |
| --- | --- |
| `frontend/src/types/enums.ts` | 为 `planned wayline` 增加独立本地存储键 `PlannedWaylineDraft` |
| `frontend/src/hooks/use-wayline-planning.ts` | 新增草稿序列化 / 反序列化；在目标机选择、航点增删改、执行结束时自动持久化；模块初始化时自动恢复 |
| `frontend/src/pages/page-web/projects/wayline.vue` | 页面挂载时优先用已恢复的 `planningState.aircraftSn` 回填目标机选择框 |

### 9.3 当前行为变化

- 重新进入 `wayline` 页面后，若浏览器本地已有草稿，会自动恢复：
  - 目标飞机 SN
  - 默认高度
  - 最大速度
  - 航点列表（GCJ / WGS / height）
- 恢复后不会自动继续“执行中”状态；执行状态统一回落为 `idle`，避免页面刷新后错误地把旧任务当成仍在运行。
- 若本地草稿损坏或 JSON 解析失败，会自动清掉坏数据，避免反复报错。

### 9.4 验证计划

本轮代码完成后，需重新执行：

```bash
cd frontend
npm.cmd run build
```

若构建通过，再补一次人工验证：

1. 打开 `wayline` 页面，选择飞机并添加 2-3 个航点。
2. 刷新页面。
3. 确认目标飞机、默认高度、最大速度、航点列表仍在。
4. 确认页面未误显示为“正在执行”。

### 9.5 本轮验证结果

- 已执行：

```bash
cd frontend
npm.cmd run build
```

- 结果：构建通过。
- 备注：仍存在项目原有的 Sass `@import` 弃用警告、legacy JS API 警告、`::v-deep` 警告和大 chunk 警告；本轮 `planned wayline` 持久化改动未引入新的构建错误。
- 尚未完成：浏览器侧“加点 -> 刷新 -> 自动恢复”的人工交互验证，需在下次打开页面时补做。

### 8.5 本次代码改动清单

| 文件 | 改动 |
| --- | --- |
| `frontend/src/pages/page-web/projects/tsa.vue` | 新增 3 个按钮：`Fly Forward 20m`、`Fly To Point (Manual)`、`Stop Fly To Point`；引入 `postFlyToPoint` / `deleteFlyToPoint`；新增对应 handler 与 manual 输入 Popover 的表单状态 |
| `frontend/src/api/drone-control/drone.ts` | 已存在所需接口，无需变更 |

按钮启用条件：

- 三个按钮都要求 `isCurrentRemoteGateway(device) === true`（DRC 已连接）。
- `Fly Forward 20m` 与 `Fly To Point (Manual)` 额外要求：飞机 OSD 存在，`mode_code !== Disconnected`，且 `height ≥ 15 m`（避免地面阶段误触发）。
- `Stop Fly To Point` 只要求 DRC 连接，方便在任何状态下中断。
- Manual 按钮点击 "发送" 前做基本数值校验（经纬度范围、height 数值型）。

### 8.6 航线规划（点选航线）初步方案

`/wayline` 页目前是纯 KMZ 文件管理，不是规划工具。针对 M4T + RC Plus 2 + DRC，短期内采取：

1. **不走官方 wayline 任务流**（`flighttask_create/prepare/execute` 是 Dock 专属，RC2 场景预期被拒）。
2. **自建点选航线**：在 `tsa.vue` / `workspace.vue` 上增加 "航点序列" 侧栏，用户依次点击地图增加航点；点 "开始执行" 后前端按顺序 `postFlyToPoint` → 监听 `FlyToPointProgress.reach_target` → 下一个点。
3. 航点在前端内存即可，不进后端数据库；执行过程中可随时 `Stop Fly To Point` 中断。

§8.5 的三个按钮是该方案的**第一步**（单点验证），在飞行验证通过之前不展开多点序列逻辑。

### 8.7 回滚参考

- 本次改动不触碰后端任何文件；前端改动限 `tsa.vue`。
- 如发现 `fly_to_point` 在 RC2 场景被 SDK AOP 拦截（返回 `210003`），参照 §7.2 的做法处理 `AbstractControlService.flyToPoint` 上的 `@CloudSDKVersion`（当前看代码无 `exclude`，应不需要改）。
- tsa.vue 回滚到 §7 tag 状态：`git checkout v0.2.0-takeoff-coord-offset -- frontend/src/pages/page-web/projects/tsa.vue`。

---

## 10. 2026-04-18 联调记录：服务恢复 / 返航事件修复 / 航线 execute 能力边界

记录日期：2026-04-18

本节记录 2026-04-18 晚间这一轮联调的三个重点：

1. 恢复本机联调环境，使前后端与必需基础服务重新可用。
2. 继续排查 `return_home` 在 `RC Plus 2 + M4T` 场景下的异常。
3. 对 `/wayline` 页面点击 `execute` 报“方法不支持”的现象做代码级归因，明确下一步不应再在 RC 场景上硬推官方 Dock 航线执行链。

### 10.1 基础服务恢复情况

本轮开始时，前端和 MQTT 服务可用，但后端 `sample` 启动后很快退出。最终确认根因不是命令错误，而是 **Redis 未启动**：

- 现象：
  - `mvn -pl uavfire spring-boot:run` 启动后 Tomcat 能短暂拉起。
  - 随后日志报 `Application finished with exit code: 1`。
  - 根因栈为：
    - `RedisConnectionFailureException: Unable to connect to Redis`
    - `Unable to connect to localhost:6379`
- 处理：
  - 直接本机拉起 `redis-server --save '' --appendonly no --port 6379`
  - Redis 恢复后，`sample` 可以稳定启动并监听 `6789`

本轮确认可用的服务端口：

| 服务 | 地址 / 端口 | 状态 |
| --- | --- | --- |
| 前端 Vite | `http://172.20.10.7:8080/` | 正常 |
| 后端 sample | `http://172.20.10.7:6789/` | 正常 |
| BASIC MQTT | `172.20.10.7:1883` | 正常 |
| DRC MQTT WS | `ws://172.20.10.7:8083/mqtt` | 正常 |
| Redis | `6379` | 已恢复 |
| MySQL | `3306` | 正常 |

### 10.2 当晚返航问题的实际进展

#### 10.2.1 现象分层

返航问题在今晚的日志里至少分成两层：

1. 业务侧前置判断仍可能返回：
   - `The current state of the dock does not support this function.`
2. 设备侧返航事件已经持续上报：
   - `return_home_info`

第二点很关键，它说明“返航相关链路并非完全没进设备侧”，至少返航事件 topic 已经有真实设备数据上来。

#### 10.2.2 确认到的 SDK / 事件路由错误

日志中长期反复出现：

```text
Method returnHomeInfo(com.dji.sdk.mqtt.events.TopicEventsRequest, org.springframework.messaging.MessageHeaders) cannot be found
```

具体落点：

- `SDKWaylineService.returnHomeInfo.serviceActivator`
- `flightTaskServiceImpl.returnHomeInfo.serviceActivator`

根因已经确认：

- `return_home_info` 在运行时是 **events** 通道，Spring Integration 实际派发的是：
  - `TopicEventsRequest<ReturnHomeInfo>`
- 但仓库里的 SDK 抽象类和 sample 实现类原先都把它写成了：
  - `TopicRequestsRequest<ReturnHomeInfo>`

这属于 **Cloud SDK / Demo 对 `return_home_info` 事件签名声明错误**，不是单纯现场状态问题。

#### 10.2.3 本轮对返航链做的修复

本轮已落地的修复分两部分：

1. 返航状态前置判断继续兼容 RC 场景
   - `ReturnHomeState.java`
   - `ReturnHomeCancelState.java`
   - `DeviceServiceImpl.java`

本轮之前已做过：

- 优先读取 `OsdRcDrone`
- 回退兼容 `OsdDockDrone`
- 不再只盯 Dock 侧 OSD
- 补充候选 SN、模式判断、RC/Dock OSD 检查日志

2. 修正 `return_home_info` 的事件处理签名
   - `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/wayline/api/AbstractWaylineService.java`
   - `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/SDKWaylineService.java`
   - `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/FlightTaskServiceImpl.java`

修复策略：

- 在 SDK 侧把 `returnHomeInfo` 从 `TopicRequestsRequest` 改为 `TopicEventsRequest`
- 在 sample 侧不再依赖错误的 `super.returnHomeInfo(...)`，而是直接接收事件并记录日志：
  - `returnHomeInfo event received`
  - `flightTask returnHomeInfo event received`

#### 10.2.4 编译与生效状态

本轮已执行：

```bash
cd backend
mvn -pl cloud-sdk clean install
mvn -pl uavfire -DskipTests clean compile
mvn -pl uavfire spring-boot:run
```

结果：

- `cloud-sdk` 已重新安装到本地仓库
- `sample` 已重新编译成功
- 后端已重启成功并监听 `6789`

这意味着下一次返航实测时，至少不应再出现今晚那种：

```text
Method returnHomeInfo(...) cannot be found
```

如果下一轮仍然返航失败，就可以把问题重新收敛到真正的业务前置判断或飞控返回，而不是事件路由本身。

### 10.3 当晚 `fly_to_point` 状态补充

本轮日志里依然有：

```text
flyToPoint precheck failed. reason=The current state of the drone does not support this function, please try again later.
```

说明当前 `fly_to_point` 还没有进入“稳定可随时调用”的状态，至少在某些现场模式码下仍会被 sample 层前置判断拦截。  
这条线没有在今晚继续深挖，优先级暂时低于返航与航线 execute 能力判断。

### 10.4 `/wayline` 页面点击 execute 报“不支持方法”的当前判断

用户在当晚测试中反馈：

- 航线规划完成后，点击 `execute` 报错
- 体感表现接近“当前设备不支持这个方法”

虽然本轮未抓到这一刻的完整接口错误包，但**从代码结构上已经基本可以下结论**：

`/wayline` 页面走的是 DJI Demo 原生的官方 wayline 任务流：

1. `flighttask_prepare`
2. `flighttask_execute`
3. 相关 wayline / task / media 回调

而后端 SDK 抽象类里这几条官方 wayline 方法都有同样的限制：

- `AbstractWaylineService.flighttaskPrepare(...)`
- `AbstractWaylineService.flighttaskExecute(...)`
- `AbstractWaylineService.flighttaskUndo(...)`
- `AbstractWaylineService.flighttaskPause(...)`
- `AbstractWaylineService.flighttaskRecovery(...)`

上述方法都带有：

```java
@CloudSDKVersion(exclude = GatewayTypeEnum.RC)
```

这和 `takeoff_to_point`、`fly_to_point` 的情况不同。  
也就是说，**官方 Demo 代码本身就把 wayline task 执行链定义成非 RC 场景能力**。对于 `RC Plus 2`：

- 如果网关被识别成 `RC`
- 或 DJI 官方本就只允许 Dock 场景执行 wayline task

那么 `/wayline` 点 `execute` 报“不支持方法”是符合当前代码与官方 Demo 设计的。

#### 10.4.1 当前判断

在 `RC Plus 2 + M4T + DRC` 组合下：

- 不应把 DJI Demo 里的 `/wayline` 官方任务执行链，当成当前主路径继续投入大量时间
- 它更像 Dock 场景能力
- 即使强行去掉 `exclude = GatewayTypeEnum.RC`，最终也大概率仍要面对飞控侧拒绝

#### 10.4.2 更务实的路线

对当前项目目标而言，更稳妥的是：

1. 先把单点能力稳定住
   - `takeoff_to_point`
   - `fly_to_point`
   - `return_home`

2. 再做 RC 场景下的“轻量航线执行器”
   - 前端维护一组航点
   - 顺序调用 `fly_to_point`
   - 监听 `fly_to_point_progress`
   - 支持暂停 / 停止 / 跳过

这条路线和 §8.6 的判断一致，即：

- **RC 场景短期不走官方 Dock wayline 任务流**
- 而是走“多点串行点飞”的 commander flight 方案

### 10.5 本轮涉及文件清单

| 文件 | 目的 |
| --- | --- |
| `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/wayline/api/AbstractWaylineService.java` | 修正 `return_home_info` 事件签名 |
| `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/SDKWaylineService.java` | 直接接收返航事件并记录日志 |
| `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/FlightTaskServiceImpl.java` | 直接接收返航事件并记录日志 |
| `backend/uavfire/src/main/java/com/yx/uavfire/control/model/dto/ReturnHomeState.java` | 返航前置判断兼容 RC OSD |
| `backend/uavfire/src/main/java/com/yx/uavfire/control/model/dto/ReturnHomeCancelState.java` | 取消返航前置判断兼容 RC OSD |
| `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DeviceServiceImpl.java` | 设备模式判断优先兼容 RC OSD |

### 10.6 当前停留点

截至 2026-04-18 夜间，项目停留在以下状态：

- 前后端与基础服务均可启动
- `takeoff_to_point` 已经具备现场起飞能力
- `fly_to_point` 曾有过实飞成功记录，但当前仍有模式前置判断未收口
- `return_home` 的事件签名错误已修正，尚待下一轮现场复测
- `/wayline` 官方 execute 链路对 `RC Plus 2` 大概率属于能力边界外，不建议继续作为主线推进

### 10.7 下一次测试的建议顺序

1. 优先复测 `return_home`
   - 先确认 `returnHomeInfo(...) cannot be found` 是否已消失
   - 再判断返航失败是否仍来自 `canPublish`

2. 如返航链可用，再复测：
   - `takeoff_to_point`
   - `fly_to_point`

3. 暂缓 `/wayline` 官方 execute 深挖
   - 除非后续明确要做 Dock 场景
   - 否则 RC 主线优先转向“多点串行点飞”

### 10.8 待办：确认自主飞行后云端控制权是否必须释放

- 现象：
  - `takeoff_to_point` 与 `fly_to_point` 都已成功执行
  - 但执行几秒后，设备状态会从 `cloud_control_auth=[flight]` 变为 `cloud_control_auth=[]`
  - 前端体感表现为“云端操控已断开”，需要重新接管 DRC 才能继续发返航等指令
- 当前结论：
  - 现有日志只能证明这是 **设备/固件当前实际行为**
  - 还不能证明这是 DJI 官方明确规定的标准流程
- 后续待确认：
  1. 查 DJI 官方文档 / 支持工单，确认 `RC Plus 2 + M4T` 在 `takeoff_to_point` / `fly_to_point` 后是否设计上必须释放云端控制权
  2. 如果官方允许持续保持控制权，再回头排查是否是本项目触发了额外释放
  3. 如果官方就是会释放，则产品层考虑“自动重新接管 DRC / 自动重新申请 flight authority”来优化体验

## 11. Mac 本机启动后端（JDK 11）标准步骤

记录日期：2026-04-19

本节是为了避免再次误判"本机没有 JDK 11"。Mac 上 JDK 11 通过 Homebrew 以 `openjdk@11` 的形式安装，不会出现在 `/Library/Java/JavaVirtualMachines/` 下，也不会被 `/usr/libexec/java_home -V` 枚举出来（只显示 JDK 8）。

### 11.1 环境事实（写死，不要再查）

| 项 | 值 |
| --- | --- |
| JDK 11 路径 | `/usr/local/opt/openjdk@11` |
| JDK 11 版本 | OpenJDK 11.0.30 (Homebrew) |
| Maven | `/usr/local/bin/mvn`（版本 3.9.13，默认绑的是 JDK 8，必须手动覆盖 `JAVA_HOME`） |
| 后端端口 | 6789 |
| MQTT 默认地址 | `application.yml` 里的 `172.20.10.7`（Mac 本机使用，无需额外参数） |
| 后端日志 | `backend/uavfire/logs/cloud-api-sample.log` |

Windows 上的 `run_sample.ps1` 使用 `AI/.jdk17` 并传 `--mqtt.BASIC.host=172.20.10.7`，Mac 上**不要照搬**，MQTT 地址不同。

### 11.2 一次性检查命令（如果必须确认环境）

```bash
/usr/local/opt/openjdk@11/bin/java -version   # 应输出 openjdk 11.0.30
brew list | grep openjdk                       # 应包含 openjdk@11
```

### 11.3 编译 + 启动（标准流程）

```bash
export JAVA_HOME=/usr/local/opt/openjdk@11
export PATH=$JAVA_HOME/bin:$PATH

cd /Users/likewang/uavfire/backend

# 1) 只改了 cloud-sdk：重新 install，让 sample 能取到新类
mvn -pl cloud-sdk -am clean install -DskipTests

# 2) 启动 sample（application.yml 默认 MQTT 即可，不需要额外参数）
mvn -pl uavfire \
  -Dproject.build.sourceEncoding=UTF-8 \
  -Dmaven.compiler.encoding=UTF-8 \
  spring-boot:run
```

启动成功标志：日志出现 `Tomcat started on port(s): 6789 (http)` 和 `Started CloudApiSampleApplication in X seconds`。常规启动耗时 7–10 秒。

### 11.4 仅在改了 sample 代码时的快捷路径

如果改动只在 `sample/`，不涉及 `cloud-sdk/`，第 1 步可以跳过：

```bash
export JAVA_HOME=/usr/local/opt/openjdk@11
export PATH=$JAVA_HOME/bin:$PATH
cd /Users/likewang/uavfire/backend
mvn -pl uavfire spring-boot:run
```

### 11.5 反模式（不要再做）

- ❌ 用 `/usr/libexec/java_home -V` 判断有没有 JDK 11 —— 它看不到 Homebrew 的 `openjdk@11`。
- ❌ 直接 `java -jar uavfire/target/sample-1.10.0.jar` —— sample 的 pom 没有配 `spring-boot-maven-plugin` 的 `repackage`，打出来的 jar 不是可执行 jar，会报 "中没有主清单属性"。必须用 `spring-boot:run`。
- ❌ 把 Windows `run_sample.ps1` 里的 `--mqtt.BASIC.host=172.20.10.7` 搬到 Mac 上 —— Mac 网络里那个 IP 不通，用 `application.yml` 的默认即可。
- ❌ 只跑 `mvn -pl uavfire clean package` 不跑 `cloud-sdk install` —— 如果同时改了 cloud-sdk，sample 会继续依赖本地 `~/.m2/` 里的旧 jar，改动看不到。

### 11.6 停止服务

```bash
lsof -iTCP:6789 -sTCP:LISTEN           # 找 PID
kill <pid>                             # 优雅停止
# 或：如果是通过 mvn spring-boot:run 启的且 Maven 进程还在前台，直接 Ctrl+C
```

## 12. 飞行测试前必须执行的本机检查与启动清单

记录日期：2026-04-19 19:51:20 CST

本节用于约束后续所有飞行测试前的操作顺序。以后**禁止**在未确认服务状态的前提下直接说“可以测试”。

### 12.1 必查项

按下面顺序检查，全部满足后才能开始现场测试：

1. Redis 是否启动
2. MQTT 是否启动
3. 后端是否已监听 `6789`
4. 前端是否已监听 `8080`
5. 前端环境变量是否指向正确后端地址
6. 后端是否使用 **Java 11** 启动，而不是 Java 8

对应检查命令：

```bash
# Redis / MQTT / 前后端端口
lsof -nP -iTCP -sTCP:LISTEN | rg '(:6379|:1883|:8083|:6789|:8080)'

# Redis 活性
redis-cli ping

# 前端后端配置
sed -n '1,80p' frontend/env/.env
sed -n '1,120p' backend/uavfire/src/main/resources/application.yml

# Java / Maven / Node 环境
mvn -v
node -v
npm -v
```

本机 2026-04-19 实际检查结果：

| 项 | 结果 |
| --- | --- |
| Redis `6379` | 正常 |
| MQTT `1883` | 正常 |
| DRC MQTT WS `8083` | 正常 |
| 后端 `6789` | 初始未启动，后已启动成功 |
| 前端 `8080` | 初始未启动，后已启动成功 |
| Java 默认版本 | 1.8.0_261，不可直接启动 sample |
| 可用 JDK 11 | Homebrew `openjdk@11 11.0.30` |

### 12.2 后端标准启动步骤

后端 `sample` 要求 Java 11。若直接用系统默认 Java 8 启动，会报：

```text
UnsupportedClassVersionError ... class file version 55.0 ... only recognizes up to 52.0
```

标准启动命令：

```bash
export JAVA_HOME="$(brew --prefix openjdk@11)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

cd /Users/likewang/uavfire/backend
mvn -pl uavfire spring-boot:run
```

启动成功判据：

- 日志出现 `Tomcat initialized with port(s): 6789 (http)`
- `lsof -nP -iTCP:6789 -sTCP:LISTEN` 能看到 Java 进程
- `curl -I http://127.0.0.1:6789` 返回 HTTP 响应（本机实测为 `302` 跳转到 Pilot 登录页）

本机本次启动成功信息：

| 项 | 值 |
| --- | --- |
| 启动 PID | `49977` |
| 监听端口 | `6789` |
| 验活结果 | `curl -I http://127.0.0.1:6789` 返回 `HTTP/1.1 302` |

### 12.3 前端标准启动步骤

标准启动命令：

```bash
cd /Users/likewang/uavfire/frontend
npm install
npm run serve -- --host 0.0.0.0 --port 8080
```

启动成功判据：

- 终端打印 `vite dev server running at:`
- 本机可访问 `http://localhost:8080/`
- 局域网可访问 `http://172.20.10.7:8080/`

本机本次启动成功信息：

| 项 | 值 |
| --- | --- |
| 本机地址 | `http://localhost:8080/` |
| 局域网地址 | `http://172.20.10.7:8080/` |

### 12.4 本次可复用的结论

- 不能只看代码改完或测试通过，就默认“可以开始飞行测试”。
- 必须先确认前端和后端都已启动并且端口可访问。
- 后端启动前必须显式切换到 Java 11。
- 前端 `.env` 当前后端地址为 `VITE_APP_APIGATEWAY_BACKEND_HOST='http://172.20.10.7:6789'`，与本机当前启动地址一致。

### 12.5 以后回答前的最低要求

以后只要用户问“现在能不能测试 / 要不要重启 / 服务有没有问题”，必须先执行以下检查再回答：

```bash
lsof -nP -iTCP -sTCP:LISTEN | rg '(:6379|:1883|:8083|:6789|:8080)'
redis-cli ping
mvn -v
```

若任一项不满足，就不能给出“可以测试”的结论。

## 13. DRC / 云控语义排查规则

> 2026-04-19 本轮详细交接文档：
> [HANDOFF_2026-04-19_DRC_TAKEOFF.md](HANDOFF_2026-04-19_DRC_TAKEOFF.md)

### 13.1 本次纠偏

这次在“点击官方起飞后立即提示云端操控已断开”的问题上，不能再凭现象自行推断。以后凡是涉及以下主题，必须先查 DJI 官方文档，再结合现场日志做判断：

- `cloud_control_auth_request` / `cloud_control_auth_notify`
- `drc_mode_enter` / `drc_status_notify`
- `joystick_invalid_notify`
- `takeoff_to_point` / `takeoff_to_point_progress`
- 高度字段语义（绝对高 / 相对起飞点）

### 13.2 本次已核对的官方结论

- 云控授权、DRC 链路状态、摇杆失效原因是三套不同概念，不能混用。
- `drc_status_notify` 表示 Live Flight Controls 链路状态，不等于云控授权丢失。
- `joystick_invalid_notify` 表示摇杆控制失效原因，不等于应该销毁整条云控会话。
- `takeoff_to_point_progress` 表示任务执行状态，不等于 DRC 必须断开。
- `takeoff_to_point` 中：
  - `target_height` 是 WGS84 椭球高
  - `security_takeoff_height`、`commander_flight_height` 是相对起飞点高度

### 13.3 后续处理最低要求

以后只要改 DRC / 云控 / 官方起飞相关逻辑，必须遵守：

1. 先查官方文档原文
2. 再查本地日志和当前代码
3. 只有当“文档定义 + 日志证据”一致时，才允许改状态机
4. 禁止把 `joystick_invalid_notify`、`drc_status_notify` 直接等同于“云端操控已断开”
5. 在回答用户前，要明确区分：
   - 云控授权是否存在
   - DRC 链路是否连接
   - 摇杆当前是否可用

### 13.4 下次现场测试新增核对项

- `frontend/src/pages/page-web/projects/tsa.vue` 已增加前端埋点：
  - 当 `disconnectRemoteControl()` 被调用时，会打印
    - `console.info('[RemoteSessionDisconnect]', { ... })`
  - 当前已区分的 `source`：
    - `user_click_exit`
    - `reconnect_before_new_enter`
- 下次只要再次出现“云控会话仍在，但飞行授权已释放，请重新申请授权”或后端出现 `DRC exit request`，必须先核对这一组时序：
  1. 前端控制台是否先出现 `[RemoteSessionDisconnect]`
  2. `source` 是什么
  3. 同一时刻后端是否出现 `DRC exit request`
  4. 随后是否出现：
     - `drc_mode_exit`
     - `cloud_control_release`
     - `cloud_control_auth_update authorized=false`
- 如果后端出现了 `DRC exit request`，但前端没有对应的 `[RemoteSessionDisconnect]`，说明这次退出不是当前 `tsa.vue` 这两个已知入口触发，必须继续排查其它调用链。

## 14. 2026-04-22 驾驶舱新链路播放契约补齐

### 14.1 本轮目标

- 把 `dual-stream group` 从“只有运行态”扩展到“可承载未来 Web 播放地址”的稳定契约。
- 明确告诉前端和后续接手人：驾驶舱当前只是切到了 `rcplus` 运行态查询，不是已经拿到新链路真实视频。
- 避免后续把“状态面板切换成功”误写成“直播画面已切过来”。

### 14.2 本轮代码改动

- backend:
  - `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamLiveGroupDTO.java`
    - 新增 `playbackStatus`
    - 新增 `visiblePlayUrl`
    - 新增 `thermalPlayUrl`
  - `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamAgentStatusDTO.java`
    - 新增 `playbackStatus`
    - 新增 `visiblePlayUrl`
    - 新增 `thermalPlayUrl`
  - `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`
    - `acceptStatus()` 增加上述字段的 merge 逻辑
    - `copyGroup()` 增加上述字段复制，避免缓存/redis 恢复时丢字段
- Android:
  - `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentStatusRequest.kt`
    - 新增 `playbackStatus`
    - 新增 `visiblePlayUrl`
    - 新增 `thermalPlayUrl`
  - `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
    - 当前显式上报 `playbackStatus = awaiting-media-url`
    - 当前显式上报 `visiblePlayUrl = null`
    - 当前显式上报 `thermalPlayUrl = null`
    - 这是“诚实暴露尚未接出 Web 媒体地址”，不是故障
- frontend:
  - `frontend/src/api/manage.ts`
    - `DualStreamGroup` 新增 `playbackStatus` / `visiblePlayUrl` / `thermalPlayUrl`
  - `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
    - live 面板新增 `Web 播放地址`、`可见光地址`、`红外地址`
    - 主描述文案改为“当前只读取 RC Plus runtime 状态；新链路 Web 播放地址尚未提供”
    - 不再暗示“驾驶舱已经完成真实画面切流”

### 14.3 本轮验证结果

- Android：

```bash
cd rcplus-msdk-agent
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:testDebugUnitTest --tests '*AgentBackendClientTest'
```

结果：`BUILD SUCCESSFUL`

- backend：

```bash
cd backend
mvn -pl uavfire -Dtest=DualStreamControllerTest,DualStreamServiceImplTest test
```

结果：`BUILD SUCCESS`

- frontend：

```bash
cd frontend
npm run build
```

结果：构建通过，仅有既有的 Sass deprecation / chunk size warning，无本轮新增错误。

### 14.4 当前真实结论

- 驾驶舱当前已经不再用旧 Agora 运行态来判断 live tab 状态。
- 但驾驶舱仍然**没有**拿到 `rcplus-msdk-agent` 新链路的 Web 可播放视频地址。
- 因此当前 live tab 是：
  - 新链路运行态面板：是
  - 新链路真实视频画面：否
- 当前 backend/group 契约已经准备好承接后续真实媒体地址，只差：
  - agent 真正产出可见光 Web 播放地址
  - 如有可能，再补 thermal 独立播放地址

### 14.5 给接手人的直接下一步

1. 在 `rcplus-msdk-agent` 或 backend 新增真实媒体输出层，产出 `visiblePlayUrl`
2. 让 agent/status 上报真实 `visiblePlayUrl`
3. 驾驶舱检测到 `visiblePlayUrl` 非空后，再切到真实播放器组件
4. 在真实新链路视频验证通过前，不删除仓库里的 Agora 代码

### 14.6 新增待办：ZLMediaKit 部署

- 已新增：
  - `deployment/zlmediakit/docker-compose.yml`
  - `deployment/zlmediakit/.env.example`
  - `deployment/zlmediakit/README.md`
- 当前状态：
  - 本机没有 `docker`
  - 无法在这台机器上直接启动 ZLMediaKit
  - 因此这项工作当前停在“部署材料已写好，待实际主机执行”
- 待办动作：
  1. 找一台已安装 Docker 的机器
  2. 复制 `.env.example` 为 `.env`
  3. 按 README 启动 ZLMediaKit
  4. 把实际可访问的 `RTMP` / `WebRTC` 地址回填到后续联调配置

## 15. 2026-04-22 `ai-service` 现状纠偏与下一阶段计划

### 15.1 当前 `ai-service` 的真实状态

- 当前 `ai-service` 不是“真实火情识别服务”
- 当前更准确的描述是：
  - FastAPI 服务骨架
  - task / event 生命周期接口
  - fusion / backend callback 链路
  - visible / thermal 占位分数逻辑

关键代码位置：

- `ai-service/app/services/task_runner.py`
- `ai-service/app/inference/visible/detector.py`
- `ai-service/app/inference/thermal/analyzer.py`

当前实际行为：

- `visible_stream_url` 有值时返回固定分数
- `thermal_stream_url` 有值时返回固定分数
- fusion 再生成 detection event

因此当前能验证的是：

- task 创建 / start / stop / query / events
- detection event 生成
- backend callback 闭环

当前不能据此声称：

- 已实现真实火情发现
- 已实现真实火情识别
- 已具备真实双流识别效果

### 15.2 下一阶段目标

把 `ai-service` 从“占位版”升级到“真实火情识别 PoC 版”。

### 15.3 下一阶段分解

1. **真实视频输入层**
   - 在 `ai-service/app/video/source.py` 落真实拉流与解码能力
   - 至少支持 `RTSP` 或录制文件
   - 输出统一 `FramePacket`

2. **visible 真检测**
   - 替换 `ai-service/app/inference/visible/detector.py` 固定分数逻辑
   - 接入真实火焰/烟雾检测模型
   - 输出 bbox / class / confidence / source_ts

3. **thermal 真分析**
   - 替换 `ai-service/app/inference/thermal/analyzer.py` 占位逻辑
   - 优先落红外伪彩图热点分析
   - 不把温度矩阵分析当作当前阻塞项

4. **持续 runner**
   - 把 `ai-service/app/services/task_runner.py` 从 `run_once()` 升级成持续消费循环
   - 增加重试、断流恢复、后台任务控制

5. **双流融合增强**
   - 在 `ai-service/app/fusion/service.py` 增加时间窗口对齐
   - 增加空间近邻匹配
   - 明确单路缺失时的降级策略

6. **事件与证据增强**
   - 扩展 detection event 字段
   - 增加风险状态
   - 预留关键帧/截图证据链

### 15.4 建议执行顺序

最短主线：

1. 真实 `VideoSource`
2. visible 真检测
3. 持续 runner
4. thermal 真分析
5. 双流 fusion
6. 事件与证据增强

### 15.5 待办事项

- [ ] 对外统一口径：当前 `ai-service` 仍是“服务骨架 + 占位识别分数器”，不能表述成“真实火情识别已可用”
- [ ] 优先完成 `ai-service/app/video/source.py` 真实视频输入层，至少支持 `RTSP` 或录制文件输入
- [ ] 完成 visible 真检测替换，占位打分逻辑下线
- [ ] 把 `ai-service/app/services/task_runner.py` 从 `run_once()` 升级为持续 runner
- [ ] 完成 thermal 真分析，再补双流 fusion 的时间/空间对齐
- [ ] 最后补 detection event 的证据字段、风险状态、关键帧/截图链路
### 15.6 资源判断

- 当前占位版 `ai-service` 做开发验证，不强依赖 GPU
- 一旦进入真实视频推理和真实火情识别，再评估 GPU 资源才有意义
- 不应再用当前占位版的资源占用，去反推真实 AI 阶段的最终配置

## 16. 2026-04-29 `ai-service` PoC 验证收口与前端脚本修正

### 16.1 本轮继续处理结果

- `ai-service` 当前已具备 PoC 级真实输入与启发式识别链路：
  - `OpenCvVideoSource` 支持 OpenCV 可打开的 RTSP/RTMP/HTTP/file/本地路径输入
  - visible 默认使用颜色启发式 `ColorFireVisibleDetector`
  - 配置 `AI_SERVICE_VISIBLE_YOLO_MODEL_PATH` 后切换为 `YoloVisibleDetector`
  - thermal 在持续 runner 开启时使用 `HotSpotThermalAnalyzer`
  - `ContinuousTaskRunner` / `ContinuousTaskSupervisor` 已支持后台持续消费和 cooperative stop
  - `BackendClient` 已支持 `adminPC/adminPC + flag=1` 登录取 token 后回传事件
- 口径修正：
  - 现在可以说 `ai-service` 进入“真实视频输入 + 启发式火情 PoC”阶段
  - 仍不能说已经完成生产级火情识别、GPU 推理优化、模型评测或真机双流识别效果验证

### 16.2 本轮修正的测试问题

- `frontend/scripts/pilot-liveshare-config.test.mjs` 仍断言旧地址 `172.20.10.7` 和旧变量名 `config.rtmpURL`
- 当前源码实际契约已经是：
  - `CURRENT_CONFIG.rtmpURL = rtmp://172.20.10.7:1935/live/`
  - `pilot-liveshare.vue` 使用 `CURRENT_CONFIG.rtmpURL + 'RC_PLUS_LOCAL-0'`
- 已将测试脚本更新到当前契约，避免把正确源码误判为失败

### 16.3 本轮验证结果

```bash
cd ai-service && ./.venv/bin/python -m pytest tests -q && ./.venv/bin/python -m compileall app
cd frontend && node scripts/leadership-cockpit-livestream.test.mjs && node scripts/workspace-livestream-panel-store.test.mjs && node scripts/no-agora-browser-sdk.test.mjs && node scripts/pilot-liveshare-config.test.mjs
cd frontend && npm run build
```

结果：

- `ai-service`: `66 passed`，`compileall` 通过
- frontend targeted scripts: `17 pass, 0 fail`
- frontend build: 通过

已知非本轮新增警告：

- Sass legacy JS API / `@import` deprecation warning
- Vue `::v-deep` deprecation warning
- Vite/Rollup `eval` warning
- 大 chunk warning

### 16.4 下一步

1. 用本地图片/短视频跑 `ai-service/scripts/run-online-media-smoke.sh`，确认正负样本事件分数符合预期
2. backend 启动后，带 `AI_SERVICE_BACKEND_BASE_URL` 做一次真实事件回传验证
3. 等 RC Plus 可用时，把驾驶舱 `RC_PLUS_LOCAL-0` 可见光流与 `ai-service` visible 输入串起来做端到端验证

## 17. 2026-05-21 Claude 最新资料校准与文档入口更新

本轮按 Claude Code 最新 memory 摘要、`docs/MSDK_MIGRATION_PLAN.md`、`docs/poc/pilot2-composite-stream.md`、当前代码和配置重新校准项目口径。

已确认的最新结论：

- Pilot 2 PIP 复合推流方案 B 已判定不可行：
  - Pilot 2 当前安装版本找不到自定义 RTMP 入口
  - 可用的是 Cloud SDK livestream UI
  - Cloud SDK livestream 只推当前主镜头 raw feed，PIP 小窗和 HUD 不进流
- Cloud SDK livestream 在 RC Plus + Pilot 2 + 手飞模式下单路可见光可用，但双流仍未确认。
- M4T + MSDK v5 当前测试组合不暴露 visible + thermal 两路独立 raw stream；同一个 `ComponentIndexType.LEFT_OR_MAIN` 下通过 `CameraVideoStreamSourceType` 切换镜头。
- 第一阶段方向是 MSDK Agent 数据面迁移，范围限定在直播、航线和 OSD/HMS；飞控迁移延后。
- 当前工作区配置基准已是 `172.20.10.7`，旧文档中的其他局域网地址不再作为当前运行基准。

本轮文档更新：

- 新增 `docs/CURRENT_PROJECT_STATUS_2026-05-21.md` 作为当前接手入口。
- 更新 `README.md`，把项目范围从“仅前后端”改为包含 frontend/backend/agent/ai-service/ZLM/docs。
- 更新 `RUNBOOK.md`，补齐 ZLM、ai-service、RC Plus agent 启动方式和当前 IP 基准。
- 更新 `rcplus-msdk-agent/README.md`，移除“只有骨架 / Java 17 阻塞”旧描述。
- 更新 `ai-service/README.md`，修正为“真实视频输入 + 启发式/YOLO PoC”，并记录 MSDK 迁移注意事项。
- 更新 `deployment/zlmediakit/README.md`，把旧 `{droneSn}_visible` / `{droneSn}_thermal` 命名改为当前 `{effectiveSn}-0`。
- 更新 `docs/COCKPIT_VISIBLE_LIVESTREAM_E2E_CHECKLIST.md`，把 E2E 清单同步到 `172.20.10.7` 和当前 stream id 语义。
- 更新 `docs/MSDK_MIGRATION_PLAN.md`，补充“已落地 / 未落地”状态边界。

代码评审中发现的当前重点调整项：

1. `leadership-cockpit.vue` 仍保留 `startPilotLivestreamOnce()` 和 `pilotLiveUrl` patch，会让 cockpit 在打开时继续触发 Cloud SDK livestream fallback；这与 MSDK agent 优先路线冲突，应改为显式 fallback 开关或移除自动启动。
2. `PlannedWaylineServiceImpl.triggerFireDetectionForWayline()` 仍通过 `AiServiceClient.defaultVideoIdForDrone()` 拼 Cloud SDK 风格 RTSP URL；航线触发 AI 时还没切到 agent stream。
3. `DjiMsdkStreamBinder.bindThermal()` 仍使用旧错误字符串，会误导成 “MSDK v5 不支持”，更准确应表达为 “M4T 单 gimbal / 单 ComponentIndex 下无独立双 raw stream”，并为 side-by-side slicing 留出状态。
4. `DjiLiveStreamController` 用 `AGENT_AIRCRAFT_SN` 生成 ZLM stream id，但 backend/cockpit 仍常以 `RC_PLUS_LOCAL` 查询 group；需要确认 `visiblePlayUrl` 中的 stream id 是否和 ZLM 实际 source 一致。
5. `OsdReporter`、`HmsReporter`、`WaylineMqttPublisher.publishCloudOsd/publishCloudEvent` 缺单测，JSON 字段名错误会直接导致 backend 无法消费。

## 18. 2026-05-21 MSDK Agent 数据面收口第一轮

本轮按上一节建议顺序完成了前四项代码收口，真机 E2E 仍需连接 RC Plus + M4T 后执行。

已完成：

- 移除 `leadership-cockpit.vue` 挂载时自动调用 Cloud SDK / Pilot 2 开播的 fallback hack；cockpit 现在只消费 backend DualStream group 返回的播放 URL。
- `PlannedWaylineServiceImpl.triggerFireDetectionForWayline()` 已从 Cloud SDK videoId URL 切换为 agent stream：`rtsp://<zlm-host>:8554/live/{droneSn}-0`。
- `DjiMsdkStreamBinder.bindThermal()` 的降级原因改为 `m4t-single-gimbal-only-exposes-single-component-index`，避免继续误导为 MSDK v5 全局不支持。
- `WaylineMqttPublisher` 新增 Cloud SDK OSD / event envelope builder，并用 `GsonBuilder().serializeNulls()` 保留 `mode_code:null`。
- 补充聚焦测试：
  - `frontend/scripts/leadership-cockpit-livestream.test.mjs`
  - `backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineServiceTest.java`
  - `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinderSourceTest.kt`
  - `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/wayline/WaylineMqttPublisherCloudPayloadTest.kt`

验证结果：

```bash
node --test frontend/scripts/leadership-cockpit-livestream.test.mjs
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire -Dtest=PlannedWaylineServiceTest#executeAgentWaylineStartsAiDetectionFromAgentStreamUrl -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools ./gradlew :app:testDebugUnitTest --tests "com.yinxin.uavfir.stream.DjiMsdkStreamBinderSourceTest" --tests "com.yinxin.uavfir.stream.RealMsdkStreamProviderTest.start_fallsBackToVisibleOnlyWhenThermalBindingFails" --tests "com.yinxin.uavfir.wayline.WaylineMqttPublisherCloudPayloadTest"
```

结果：

- frontend cockpit livestream tests: `16 pass, 0 fail`
- backend wayline AI URL test: `1 pass, 0 fail`
- rcplus-msdk-agent focused tests: `BUILD SUCCESSFUL`

下一步真机 E2E 顺序：

1. 启动基础服务：MySQL / Redis / ZLM / backend / frontend / ai-service，确认全部使用 `172.20.10.7`。
2. 安装并启动 RC Plus agent，确认 `AGENT_AIRCRAFT_SN`、`AGENT_MEDIA_HOST=172.20.10.7`、MQTT broker 均正确。
3. 在 agent UI 启动双流，确认 ZLM 出现 `live/{effectiveSn}-0`，backend DualStream group 的 `visiblePlayUrl` 指向同一 stream。
4. 打开 cockpit 直播 tab，确认 WebRTC 出画且没有 Cloud SDK 自动开播请求。
5. 执行一条 agent wayline，确认 backend 触发 ai-service 的 RTSP URL 为 `rtsp://172.20.10.7:8554/live/{droneSn}-0`。
6. 对准火焰/测试视频源完成 ai-service event 回传，确认 backend `/fire-events` 和 cockpit 风险事件面板出现记录。

## 19. 2026-05-21 真机 ADB 联调进展

RC Plus 2 已通过 ADB 识别：

```text
9N9CMA500100B8  DJI_RC_PLUS_2
```

现场网络发现：

- RC Plus Wi-Fi 地址是 `192.168.0.20/24`
- Mac 当前基准地址是 `172.20.10.7/24`
- RC Plus 无到 `172.20.10.7` 的路由，`ping 172.20.10.7` 100% 丢包

本轮采用 USB ADB reverse 联调：

```bash
adb reverse tcp:6789 tcp:6789
adb reverse tcp:1883 tcp:1883
adb reverse tcp:1935 tcp:1935
```

agent 以如下运行参数安装：

```bash
./gradlew :app:installDebug \
  -PagentBackendBaseUrl=http://127.0.0.1:6789/ \
  -PagentMediaHost=127.0.0.1 \
  -PagentMqttBrokerUrl=tcp://127.0.0.1:1883 \
  -PagentAircraftSn=1581F7K3D249E00AM3Q3 \
  -PagentGatewaySn=9N9CMA500100B8
```

验证结果：

- agent runtime 已用真实 aircraft SN `1581F7K3D249E00AM3Q3` 上报 heartbeat/status/capability。
- ZLM 已收到真实 M4T RTMP 源：`live/1581F7K3D249E00AM3Q3-0`，H264 1920x1080。
- backend DualStream group 已对齐同一 SN：
  - `session_state=RUNNING`
  - `live_status=RUNNING`
  - `visible_state=running`
  - `thermal_state=degraded`
  - `status_reason=m4t-single-gimbal-only-exposes-single-component-index`
  - `visible_play_url=webrtc://172.20.10.7:58925/live/1581F7K3D249E00AM3Q3-0`
- ai-service 已直接拉取真实 ZLM RTSP：
  - `rtsp://127.0.0.1:8554/live/1581F7K3D249E00AM3Q3-0`
  - task `fire-1581F7K3D249E00AM3Q3` 状态为 `running`
  - detection events 持续产生，当前画面为 `fusion_score=0.0` / `risk_level=LOW`

本轮发现并修正：

- agent 原先 runtime/group 使用 `RC_PLUS_LOCAL`，但 RTMP stream 使用 `AGENT_AIRCRAFT_SN`，导致 backend/cockpit URL 指向不存在的 `RC_PLUS_LOCAL-0`。
- 已改为：配置了 `AGENT_AIRCRAFT_SN` 时，agent runtime `LOCAL_DRONE_SN` 使用真实 aircraft SN。
- cockpit 查询 DualStream group 时优先使用当前 OSD/设备状态里的 SN，避免继续硬编码 `RC_PLUS_LOCAL`。

剩余 E2E 缺口：

1. cockpit 需要浏览器实测 WebRTC 画面；当前命令行已确认 frontend 200、ZLM source 存在、backend URL 正确。
2. 当前真实画面没有触发火情，ai-service 只产生 LOW 事件；需要对准火源/测试火焰图，或注入高风险样本，才能验证 `/api/fire/events` 和 cockpit 风险面板。
3. 若后续不用 USB reverse，应把 RC Plus 和 Mac 放回同一网段，或更新所有 IP 配置。

## 20. 2026-05-29 FC100 公网直播与 VM ai-service 部署记录

本轮将 FC100 公网直播和火情识别链路切到公网 VM，后续启动服务时不要再启动本地 `ai-service/scripts/run-dev.sh`。本机只运行前端、后端和 SSH 隧道，`127.0.0.1:9000` 由隧道转发到 VM 上的 ai-service。

VM 访问：

```bash
ssh -p 46691 djdev@1916dn17xs12.vicp.fun
```

花生壳当前映射：

```text
RTMP 推流: 1916dn17xs12.vicp.fun:56920 -> 192.168.50.200:8089
WebRTC 信令: 1916dn17xs12.vicp.fun:55932 -> 192.168.50.200:8099
WebRTC 媒体: 1916dn17xs12.vicp.fun:19586 -> 192.168.50.200:19586
SSH-200: 1916dn17xs12.vicp.fun:46691 -> 192.168.50.200:22
```

VM ZLMediaKit 运行在 Docker 容器 `uavfire-zlmediakit`，RTC 已改为 TCP 优先，媒体端口为 `19586`：

```text
[rtc]
port=19586
tcpPort=19586
preferred_tcp=1
externIP=1916dn17xs12.vicp.fun
```

本地配置已经指向公网媒体服务：

```text
backend/uavfire/src/main/resources/application.yml
  livestream.playback.webrtc-host=1916dn17xs12.vicp.fun
  livestream.playback.webrtc-port=55932
  livestream.url.rtmp.url=rtmp://1916dn17xs12.vicp.fun:56920/live/

frontend/src/api/http/config.ts
  rtmpURL=rtmp://1916dn17xs12.vicp.fun:56920/live/

rcplus-msdk-agent/gradle.properties
  agentMediaHost=1916dn17xs12.vicp.fun
  agentMediaRtmpPort=56920
```

ai-service 当前部署方式：

```text
VM systemd service: uavfire-ai.service
VM project dir: /home/djdev/uavfire-deploy/ai-service
VM model: /home/djdev/uavfire-deploy/ai-service/models/yolov26-fire-detection-best.pt
local model source: models/yolov26-fire-detection-best.pt
```

VM `.env` 关键项：

```dotenv
AI_SERVICE_USE_CONTINUOUS_RUNNER=true
AI_SERVICE_BACKEND_BASE_URL=http://127.0.0.1:6789
AI_SERVICE_BACKEND_USERNAME=adminPC
AI_SERVICE_BACKEND_PASSWORD=adminPC
AI_SERVICE_BACKEND_LOGIN_FLAG=1
AI_SERVICE_VISIBLE_YOLO_MODEL_PATH=/home/djdev/uavfire-deploy/ai-service/models/yolov26-fire-detection-best.pt
AI_SERVICE_VISIBLE_FIRE_SATURATION_RATIO=0.05
AI_SERVICE_VISIBLE_CONFIDENCE_FLOOR=0.05
AI_SERVICE_SNAPSHOT_DIR=/home/djdev/uavfire-deploy/ai-service/data/fire-snapshots
AI_SERVICE_SNAPSHOT_PUBLIC_BASE_URL=http://127.0.0.1:9000/api/v1/snapshots
OPENCV_FFMPEG_CAPTURE_OPTIONS=rtsp_transport;tcp
```

本机启动顺序：

```bash
tmux new-session -d -s uavfire-backend 'cd /Users/likewang/uavfire/backend && mvn -pl uavfire spring-boot:run'
tmux new-session -d -s uavfire-frontend 'cd /Users/likewang/uavfire/frontend && npm run serve -- --host 0.0.0.0 --port 8081'
tmux kill-session -t uavfire-ai-service 2>/dev/null || true
tmux new-session -d -s uavfire-ai-tunnel 'expect /tmp/uavfire-ai-tunnel.expect'
```

`/tmp/uavfire-ai-tunnel.expect` 隧道语义：

```text
本机 127.0.0.1:9000 -> VM 127.0.0.1:9000
VM 127.0.0.1:6789 -> 本机 127.0.0.1:6789
```

关键验证结果：

```text
本机 curl http://127.0.0.1:9000/healthz -> {"status":"ok"}
VM curl http://127.0.0.1:9000/healthz -> {"status":"ok"}
VM curl http://127.0.0.1:6789/ -> 302，证明反向隧道能访问本机后端
VM YOLO load -> model_loaded=YOLO，names={0:'fire',1:'other',2:'smoke'}
```

注意：

- 当前 ai-service 使用 CPU 版 PyTorch，不启用 VMware GPU 直通。
- 宿主机有 GTX 1660S 不能自动让 VM 使用 CUDA；除非 VM 内 `nvidia-smi` 能看到 NVIDIA 设备，否则仍是 CPU 推理。
- 花生壳 TCP 映射访问 WebRTC HTTP 信令可能返回“映射不支持网页访问”，如浏览器侧加载 ZLMRTCClient 失败，需要把 WebRTC 信令映射改成花生壳“网站应用类型”。

## 21. 2026-06-01 驾驶舱真实数据布局与火情数据链整合

> 主要提交：`8d5a701 feat: connect cockpit real data layout`

本轮是 5 月公网直播和 AI 服务部署之后的首次大规模收口，将驾驶舱、AI、后端火情事件和 RC Plus Agent 中分散的数据链接成真实数据布局。

### 21.1 驾驶舱与前端

- 重组领导驾驶舱布局，增加真实总览数据、飞机直播选择、FC100 执行态势、火情事件和灭火任务数据展示。
- 新增 `leadership-cockpit-summary.mjs`、交付执行 HUD 策略和多个布局/数据契约测试。
- 火情列表和灭火任务列表增强真实图片、状态、设备和任务信息展示。

### 21.2 AI 与火情事件

- `ai-service` 补强任务 API、连续运行注册、可见光/热成像分析、快照书写、视频源和后端事件上报。
- 新增火情坐标质量迁移，引入 `FireGeoSnapshotDTO`、坐标定位服务和 Ray-DEM 单帧定位实现。
- 火情空间合并、坐标更新、快照、置信度和历史记录链路完成扩展。

### 21.3 RC Plus Agent

- 新增热点监测、热快照上传、热帧探针、热区分类和帧热点检测。
- 完善 Agent 与 backend 的 dual-stream 事件报文和会话状态协作。
- 同步增加大量 Agent、backend、AI 和 frontend 契约/回归测试。

本次提交共涉及 91 个文件，约 9100 行新增。它建立了后续驾驶舱真实数据、火情事件融合和 Agent 热点管线的基础，但当时热成像链路仍属快速演进阶段，不应将此节的实现理解为 7 月末的最终生产链路。

## 22. 2026-06-10–06-12 MSDK 设备状态隔离、FC100 AGL 与火情监测服务化

> 主要提交：`b5d63ce`、`ed533e3`

### 22.1 MSDK 设备与航线选择隔离

- 将 MSDK 设备状态从航线选择和页面局部状态中抽离，避免不同页面/不同机型共用错误设备。
- 后端增强 `MsdkDeviceStateService`、航线事件监听和 planned wayline 查询。
- 前端新增设备选择策略、航线位置状态、飞行控制面板和航线选机逻辑。
- Agent 完善设备状态采集、命令执行和航线执行状态回传。

### 22.2 FC100 航点高度口径

- FC100 WPML 高度统一改为相对起飞点 AGL，巡航高度按“投放高度 + 20m”构建。
- 载荷释放从“经过点即执行”收紧为到点悬停后才能进入释放流程。
- `AltitudeLimitCheck` 与 `WaypointPlannerServiceImpl` 统一使用 AGL 口径，清理了与绝对椭球高混用的失效依赖。

### 22.3 火情监测服务化

- 新增 `FireDetectionService` 作为 backend 对 `AiServiceClient` 的统一门面。
- 新增 `FireDetectionActivityTracker`，航线到达首航点时启动检测，任务终态时关闭检测。
- AI HTTP 传输增加超时保护，事件回传失败时保留后续帧补报机会。
- 航线事件对乱序 completed/failed 回报增加健壮性判断，避免已有执行进度的任务被错误翻转为 failed。

## 23. 2026-06-12 航线规划页全面重构与 DEM 高程服务

> 设计与计划：[wayline planner 设计](docs/superpowers/specs/2026-06-12-wayline-planner-flighthub2-design.md)、[17 任务实施计划](docs/superpowers/plans/2026-06-12-wayline-planner-flighthub2.md)

本日按司空 2 / FlightHub 2 式交互对航线规划页进行了连续拆分和重构。

### 23.1 后端 DEM 与地形服务

- 新增 `.hgt` DEM 瓦片服务，支持双线性插值、瓦片 LRU 缓存和缺失瓦片降级。
- 增加 `dem-dir` 条件装配；无 DEM 目录时与 Missing 实现等价，不阻塞系统启动。
- 新增 `POST /terrain/elevations` 批量高程接口及对应 controller 测试。
- 增加 GLO-30 下载/转换脚本，用于补充规划区域 DEM 瓦片。

### 23.2 规划纯函数与状态抽离

- 抽离航线距离、预计时长、航点采样和预演时间轴纯函数。
- 新增规划 UI 状态 hook，统一管理页签、参数抽屉、高度剖面和模拟预演。
- FC100 投放区从航线页主文件抽离为 `Fc100DeliveryView` 和 `use-fc100-delivery` store，规划页改为巡检/FC100 双页签。

### 23.3 司空 2 风格规划工作区

- 新增 `PlannerWorkspace`、`MissionStatsBar`、`WaypointListPanel`、`WaypointParamDrawer`、`MissionParamsPanel` 和 `PlannerToolbar`。
- 原 `wayline.vue` 中大量规划交互和死样式被拆离，航点级参数进入右侧抽屉，任务级参数独立成面板。
- 地图默认切到卫星底图并叠加路网/矢量 POI 注记。
- 规划覆盖物从 `GMap.vue` 抽离到 `use-planner-overlays`，规划页与飞行页按页签切换显隐。

### 23.4 航点交互、高度剖面与预演

- 航点支持拖拽改位、航段中点插点和右键删除。
- 地图常显航点、航段距离、起飞点 H 标记和航向箭头。
- 高度剖面批量采样 DEM，同时展示航线高度和地形线；DEM 缺失时仅显示航线并明确提示降级。
- 新增 2D 模拟预演，支持幻影飞机、播放/暂停、倍速和进度控制，与真实任务执行互斥。

### 23.5 航点动作保存缺陷

- 定位到前端 API 层在保存和读回时重建航点对象，仅保留坐标/高度，导致 `actions`、速度、云台、朝向和转弯参数在请求前被静默剔除。
- 修复保存和读回两条路径的字段透传，并增加回归断言锁定后续行为。

## 24. 2026-06-15–06-18 航点动作完整性、生产构建修复与 MapLibre 迁移

### 24.1 航点动作字段再修复

> 提交：`2df9ff2`

- 解决全局 Jackson `SNAKE_CASE` 与前端 camelCase 冲突导致 `WaypointActionDTO` 入站字段静默丢失的问题。
- DTO 增加 `@JsonAlias`，前端增加 action 字段 snake_case 到 camelCase 归一化。
- 航线预览补全速度和动作，修复动作保存后再编辑显示 `?` 的问题。
- 航点/任务参数面板补齐中文标签、暗色字色和路由切换时抽屉清理。

### 24.2 生产构建白屏修复

> 提交：`c36c9a8`

- 生产构建中 `ant-design-vue` 被 `manualChunks` 拆成多个子 chunk，循环依赖初始化顺序导致 `PropTypes` 为 `undefined`。
- 开发模式不打包，因此只在生产包暴露整页白屏。
- 将 `ant-design-vue` 合并为单一 chunk，交由 Rollup 正确处理库内循环依赖。

### 24.3 MapLibre + 天地图迁移

> 提交：`3e4329f`

- 地图栈从原高德实现迁移到 MapLibre GL，接入天地图标准/影像/混合栅格样式。
- 天地图 CGCS2000 与飞机 WGS84 数据按当前精度需求直接叠加，消除互联网偏移地图造成的系统性位置偏差。
- 新增面状测区规划：弓字形蛇形航线、重叠率转间距、凸包和面状指纹判定。
- 新增基于 Haversine 球面距离的 MapLibre 测距工具。
- 对 `GMap.vue`、覆盖物 hooks、TSA 图层、鼠标工具和规划页进行成套迁移。

## 25. 2026-06-22–06-29 航线规划交互收口、飞行区合规和 DJI 限飞区

### 25.1 规划页交互收口

> 提交：`c331c8c`

- 修复同一飞机在规划层和 TSA 设备层显示两个图标的问题。
- 航线页默认影像底图真正生效，并改善图层加载时序。
- 航点拖动时实时预览航线连线；航线默认名称按航点/面状类型生成。
- 任务下发前增加贴边飞行器选择弹窗，强制明确选机。
- FC100 投放任务改为贴边弹窗，支持创建、执行和按 `plannedWaylineId` 反显已建任务。

### 25.2 规划阶段飞行区合规检查

> 设计文档：[禁飞区与合规飞行区方案](docs/禁飞区与合规飞行区-规划阶段接入-需求与方案.md)
> 提交：`4599676`

- 新增航点落入 NFZ、航段穿越 NFZ 和 DFENCE 作业区判定。
- 保存/下发前运行合规检查，并在地图叠加飞行区和 FC100 实时位置。
- 为几何合规算法增加 9 个定向测试。

### 25.3 DJI FlySafe 西安周边限飞区离线接入

> 提交：`7dc6ce3`
> 评估文档：[适飞空域接入可行性评估](docs/适飞空域接入-可行性评估与方案.md)

- 离线导入西安周边 100km DJI FlySafe 数据，将 12 个区展开为 13 个可渲染要素。
- GeoJSON 归一化为 `nfz`、`warning`、`dfence` 类型，复用已有规划期渲染和航线判定逻辑。
- 离线数据通过动态 import 分包，不进入前端主包。
- 明确产品边界：DJI GEO 数据表示限飞/警告区，不等于 UOM 正式适飞空域或授权结果。
- 验证记录：新增 5 个导入测试与原 9 个合规测试全部通过；Vite 构建产生独立 JSON 分包；浏览器实测 13 个要素正常展示。

### 25.4 月末参数调整

- 6 月 29 日将 TSA/航线页默认缩放级别对齐至约 100m 尺度（zoom 17），并升级缓存版本作废旧 zoom 设置。
- 开发环境 backend 地址一度切换到 `192.168.50.254`；该地址只是当时现场网络记录，后续运行以 `RUNBOOK.md` 为准。

## 26. 2026-07-01 领导驾驶舱态势面板重设计

> 提交：`229ee48 feat(cockpit): redesign leadership cockpit situation panels`

- 新增 `CockpitSituationMap.vue`、态势数据纯函数和 Tellux 适配层。
- 驾驶舱重组情况地图、设备摘要、任务摘要、视频选择和 FC100 交付态势。
- 接入 UOM 空域参考图层，并将西安/未央的参考 GeoJSON 用于地图态势展示。
- 同步调整 Delivery Sync adapter 的设备数据字段和前端交付面板。
- 新增态势、空域图层、布局和视觉上下文测试。

## 27. 2026-07-02 S1–S9 智能集群巡检灭火一期闭环

> 方案：`../02_需求与方案设计/智能集群巡检灭火详细设计与开发方案.md`
> 验收：[S8 一期联调验收报告](work-records/codex/S8-acceptance-report-20260702.md)

7 月 2 日按 S1–S9 任务卡集中完成了释放安全边界、处置事件编排、资源锁、指令队列、合规预检、巡检火情闭环、FC100 灭火闭环和前端处置工作台。

### 27.1 S1：释放安全边界

- `fc100_fire_mission` 落地 `release_policy` 和 `release_execution_mode`。
- 默认 `MANUAL_CONFIRM + OFFICIAL_HOOK_MANUAL`；`DRY_RUN` 只记录不下发；`CONTROLLED_TEST_AUTO` 受配置开关阻断；`DELIVERY_SYNC_REMOTE` 未确认能力时直接拒绝。
- 无操作人或无确认标记的释放请求被拒绝并写审计。
- 验证：前端策略 76/76 通过，frontend build 和 lint 通过，backend compile 通过；backend 全量测试当时仍受既有非本任务错误影响。

### 27.2 S2：处置事件与编排数据模型

- 新增 incident、assignment、resource lease、command event 数据表/实体/接口。
- 建立处置事件状态迁移表，非法迁移拒绝并记录。
- 火情确认、事件创建、监测/投送设备分配、派发、中止、关闭和时间线接口完成。
- 验证：S2 聚焦测试 11/11，S1 回归 45/45，均 `BUILD SUCCESS`。

### 27.3 S3：事件处置工作台骨架

- 完成事件列表、地图、详情、操作区和时间线五区布局。
- 复用 UOM 参考层，增加 mock 数据开关和操作按钮可见性策略。
- 验证：前端策略测试 81/81，Vite build 和 lint 通过。

### 27.4 S4：资源锁与持久化指令队列

- 实现资源 lease 获取、续约、过期和释放，两线程同时抢占同一 SN 时仅一个成功。
- 指令队列实现 `PENDING -> SENDING -> WAIT_ACK -> ACKED`，失败分支支持 `FAILED / TIMEOUT / DEAD`。
- 默认最大重试 3 次，发送超时 10s，ack 超时 30s，超限后标记待人工接管。
- 释放指令入队前仍必须通过 S1 释放策略检查。
- 验证：目标测试 28/28，S1 回归 46/46。

### 27.5 S5：R01–R15 安全合规门禁

- 建立可配置 `PreflightRuleEngine`，完成 15 项预检规则。
- 预检上下文整合火情、incident、FC100 任务草稿、设备状态、lease、指令队列、飞行申请、运行资质、DeliveryHub 健康和 UOM 参考层。
- 新增 `record-flight-application`、`record-takeoff-confirmation`、`record-landing-report` 和 qualifications 接口。
- R09 默认为无正式 UOM 接入时返回 WARN，不伪装已自动审批。
- 验证：预检/合规/编排范围 50/50，释放回归 31/31。

### 27.6 S6：巡检火情确认闭环

- AI/巡检上报只生成 `CANDIDATE`，不再在人工确认前自动创建可派发灭火任务。
- 新增火情 confirm/reject/recheck-result 接口。
- 仅 `PRECISE` 坐标在人工确认后创建 `CREATED` 草稿；其他坐标质量只生成复测/人工标注建议。
- 复测判定增加热成像饱和处理，不仅依赖绝对温度下降。
- 验证：backend 85/85，frontend policies 85/85，frontend build 通过。

### 27.7 S7：FC100 灭火闭环和人工释放留证

- Delivery Sync 任务状态统一通过 `DeliveryTaskStatusMapper` 映射到 `FireMissionEvent`。
- 创建/启动/轮询连续失败超限后进入 `MANUAL_TAKEOVER`。
- 进入 `PAYLOAD_RELEASE_PENDING` 时签发一次性释放 token；无 token、错 token、过期或重放均拒绝并写审计。
- 默认待释放超时 5 分钟，超时经 S4 指令队列发起返航，保留 FC100 满载悬停的返航余量。
- `OFFICIAL_HOOK_MANUAL` 只表示平台留证，真实开钩由飞手在官方遥控端完成；未实现 Delivery Sync 真实远程开钩。
- 验证：backend 129/129，frontend policies 86/86，frontend build 通过。

### 27.8 S8：一期 Mock 联调验收

- 新增 `EndToEndMockDrillTest`，演练 M4T 告警、人工确认、资源分配、预检、Delivery task、待释放、人工留证、返航、复测和归档。
- 主方案§12.2 的 8 个业务验收场景均建立自动化或脚本证据。
- backend 全量统计为 384 tests，379 passed、5 errors；5 个 error 来自已知环境问题，S8 新增演练通过。
- frontend policies 86/86，Vite build 通过。
- 遗留：Delivery task 开始后 incident `DISPATCHING -> RESPONDING` 当时缺明确的服务层自动桥接；UOM 仍是参考/留证而非正式自动对接。

### 27.9 S9：工作台处置链路接线

- 新增分配 FC100/巡检机入口，支持主/备投送和主/复测巡检角色。
- 前端真实调用预检接口，`CompliancePanel` 展示 R01–R15 及 BLOCK 明细。
- 新增飞行申请、起飞确认、落地报告和四类运行资质档案录入。
- 未臆造独立“生成任务” API，按后端实际语义显示“确认火情时自动创建草稿”。
- 验证：frontend policies 88/88，Vite production build 通过。

## 28. 2026-07-06–07-07 火情识别提速 T1/T2/T3

> 汇总：[火情识别提速最终报告](work-records/codex/FINAL-fire-detection-speedup-20260706.md)
> 提交：`7815e04`

### 28.1 Agent 事件驱动测温

- 热点探测间隔从 10s 缩短到 2s。
- `ThermalFrameProbe` 发现候选热点后以 2s 去抖触发测温，避免纯定时轮询带来的长等待。
- 首次事件先上报，热快照缺失时异步补图后重报，不再让快照上传阻塞告警。

### 28.2 Backend urgent 命令通道

- 热监测 cooldown 从 30s 缩短到 5s，timeout 从 120s 缩短到 20s。
- `DualStreamCommandDTO` 新增可空 `urgent`，旧 Agent/旧报文缺字段时保持普通通道语义。
- focus/monitor 等紧急命令可走 urgent 通道，Agent 轮询器增加普通/紧急并发去重。

### 28.3 构建与验证环境修复

- 新增最小 `uxsdk-stub`，外部 DJI UXSDK sample 不存在时仍可进行本地单测编译。
- Windows 中文工作区会导致 Gradle test worker `ClassNotFoundException`，验证改为 ASCII 路径副本/联接并使用 JDK 17。
- 最终验证：Agent 156 tests、0 failures；backend dual-stream 与 Spring context 68 tests、0 failures。

## 29. 2026-07-07–07-10 悬停确认、空间去重、抵近激光定位与双模型演进

### 29.1 T-A 悬停温度趋势确认

> 提交：`2f26e81`

- 热点达阈值后暂停航线，稳定 2s 后按默认 4 样本/1.5s 间隔持续测温，3/4 命中才确认。
- 连续两次测温失败时 fail-open 直接上报，选择“宁可误报，不静默漏火”。
- 悬停/恢复操作幂等，`finally` 强制恢复任务状态。
- Agent 全量 164 tests、0 failures。

### 29.2 T-B/TB.2 空间去重合并

> 提交：`8bf9873`、`9415aae`

- 火情合并从“同设备 10m”升级为“同 workspace 跨设备可配半径”，默认 40m/30min。
- 合并时更新 lastSeen/reportCount/最高温度，快照只补空，坐标仅在新误差半径更优时覆盖。
- 保留火等级升级时 `notificationVersion` 递增的驾驶舱重通知契约。
- TB.2 增加 MySQL `GET_LOCK` workspace 命名锁，保护并发查询/合并/新建临界区。
- 合并半径改为基于双方 `geoErrorRadiusM` 的自适应门限，限制在 15–60m；精确火点不再轻易吞并附近第二火点。
- `MISSION_CREATED` 事件豁免 30min 活跃窗，任务执行期复报不重复建事件。
- 验证节点：backend 396/396，随后 TB.2 节点 418/418。

### 29.3 T-C/TC1/TC.2 抵近确认与激光精确定位

> 实机检查清单：[外场验证清单](docs/superpowers/plans/2026-07-08-field-validation-checklist.md)

- `FireConfirmationProcessor` 建立 `FLY_TO -> MEASURE_CLOSE -> VISIBLE_CONFIRM -> RESET` 四阶段状态机。
- 通过 DJI MSDK 激光测距 key 读取 NORMAL 样本坐标，失败时降级为 standoff 悬停点。
- 自动抵近默认关闭，实飞验证前只能显式启用或手动下发 urgent `fire-confirmation-mission`。
- 后端 `FireApproachDispatcher` 为候选火情派发带坐标参数的抵近任务，事件冷却默认 10 分钟。
- ROI 中心偏移换算云台角度，最多 3 轮迭代对中；激光默认 5 次采样、至少 3 个有效值、离散不超 15m，取分量中位数。
- 仅 HIGH 置信的激光 fix 上报 `LASER_RANGEFINDER / 5m`；backend 保留该坐标，不再被 Ray-DEM 覆盖。
- 后续扩展两段递进抵近（默认 100m/60m）、离散环绕多观测点交会和上风向接近。
- 验证节点：Agent 173、180、182、192 tests 分阶段全绿；backend 396、404、409 tests 分阶段全绿。

### 29.4 7 月 9 日对抗性模拟与 TC.3 修复

> 发现记录：[对抗性模拟测试发现](docs/superpowers/plans/2026-07-09-simulation-findings.md)
> 修复提交：`2da68ed`

模拟探针暴露了原先单元测试未覆盖的端到端问题：抵近上报因缺热图被 backend 丢弃、激光背景误回波可把 222m 偏移写成 5m 精度、离散检查全弃、dwell 失败样本消耗预算、末段测温失败丢弃已获激光 fix。

TC.3 完成以下收紧：

- 末段近测快照上传并重试，解除 backend 热图门丢弃。
- 激光 fix 相对疑似点偏移不得超 80m；飞行高度保持机体当前高度。
- 新增 30% 电量门槛、6 分钟总时长上限和位置背离中止。
- 激光离散改为中位数修剪；dwell 失败样本不占有效样本预算。
- 末段测温失败时允许用前段合格结果上报；环绕后回上风点拍可见光证据。
- 5 个一次性探针场景转为正式回归测试，Agent 204 tests 全绿。

### 29.5 红外 YOLO 与新双模型

> 提交：`75ae77f`、`e9fc463`

- 新增 `YoloThermalAnalyzer`，支持 `brightness / yolo / max` 三模式，加载/推理失败时 fail-safe 返回 0 并降级 brightness。
- 热成像模型真图冒烟中，3 张正样本信度约 0.939–0.945，3 张负样本为 0。
- AI 验证：145 passed、1 skipped，真模型冒烟 1 passed。
- 纳入红外真值训练 yolov8n（记录 val mAP50 0.854）和可见光 hard-negative yolov8s 权重，并隔离测试对本地 `.env` 的依赖。

## 30. 2026-07-23 遥测与 HUD RTK 状态修正

> 提交：`33c9831`、`4ed4ff8`

- Agent 补采 GPS 卫星数和 RTK 卫星数，修复驾驶舱设备卡片 GPS/RTK 长期显示 `--`。
- RTK 定点状态接入 `KeyRTKLocation.positioningSolution`，仅 `FIXED_POINT` 上报 `positionFixed=true`。
- 前端 `is_fixed` 映射改为仅真实 `true` 时显示定点，避免 `null` 被错误当成已定点。
- HUD 中原简写 `R` 改为明确的 `RTK`。

## 31. 2026-07-24–07-27 实飞驱动的双模型、测温、去重和推流修复

> 主要提交：`48e286f`、`134a723`、`f4dabff`、`274587e`、`7c15e2f`、`4448335`

### 31.1 AI 断流自愈

- 红外 YOLO 框、测温 ROI 和快照标注按 7 月 24–25 日实飞数据调整。
- `continuous_runner` 在快速重连预算耗尽后转为 30s 慢速重试，Agent 恢复推流后自动恢复检测，不再因短时断流终止整个任务。
- AI 154 tests 全绿。

### 31.2 当时的串行红外确认链路

- 7 月 25 日实飞发现盛夏日晒地面和污染 HUD 温度可导致旧弱佐证/兜底规则误确认。
- backend 一度改为“红外 YOLO 命中 -> 框内实测温度 -> 温度达 80°C 才建事件”，可见光只作证据照。
- 实飞记录为三轮飞行 8 次以上确认、零误报，后端 422 tests 全绿。
- 注意：该链路在 7 月 27–30 日已被纯可见光生产路线取代，本节仅作历史演进记录。

### 31.3 空间去重时序与抵近护栏

- 实飞发现火情去重资格在 OSD 坐标回填之前判定，导致无入参坐标的串行链事件整体跳过去重。
- 将资格判定移到 OSD 回填后，同一火源复报恢复合并。
- `FireApproachDispatcher` 增加起飞护栏：OSD 相对高度 <2m 时不派发抵近任务，且不消耗冷却。
- 实飞暴露 DJI FlyTo 固件转场高度可爬升至类似 Pilot 返航高度，因此 auto-approach 继续默认关闭，并在 7 月 27 日增加 FlyTo 高度轮廓约束。

### 31.4 Agent 测温、身份和推流自愈

- 原 `ThermalHotspotMonitor` 从事件决策器降级为 HUD 供数，避免 45°C 触发线被 41–48°C 日晒地面持续击穿导致镜头拉锯。
- 框内测温改为近饱和亮块优先 + 框级区域测温兜底，珆火实测从原 39–68°C 提高到 147–153°C。
- Agent 会话 FAILED 后每 15s 退避重试起流；飞机晚于 Agent 上电时不再永久黑屏。
- 真实飞机身份回归后，若流曾以占位 SN 启动，则按真实 SN 重启推流。
- 修复 Agent 命令响应中 `params` 字段透传，抵近任务不再因坐标参数被反序列化丢弃而秒拒绝。
- Agent 195 tests 全绿。

### 31.5 驾驶舱显示

- 飞行 HUD 新增热点温度，监测期间按 dual-stream group 探针温度更新。
- MSDK 无定位解时上报的 `(0,0)` 不再显示为真实经纬度，改为占位符。

## 32. 2026-07-27–07-30 纯可见光火情识别与悬停激光定位生产路线

> 设计：[可见光悬停激光定位设计](docs/superpowers/specs/2026-07-28-visible-fire-hover-laser-geolocation-design.md)
> 计划：[可见光悬停激光定位实施计划](docs/superpowers/plans/2026-07-28-visible-fire-hover-laser-geolocation.md)
> 交接：[纯可见光火情识别交接](HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md)
> 主要提交：`a67f77d`、`6c91740`、`fec483d`

### 32.1 生产路线决策

7 月 27 日晚至 28 日凌晨，根据现场时延和检测机会分析，决定取消“可见光/红外 YOLO -> 自动切红外 -> 测温裁决 -> 可见光证据”串行链路，改为纯可见光生产检测：

```text
可见光 RTSP
  -> YOLO 检测
  -> 框内火色像素否决
  -> 连续两帧、中心位移符合
  -> 火情事件与驾驶舱告警
  -> 人工确认/驳回
```

红外代码保留，但不再作为当前生产火情确认的前置条件。

### 32.2 AI 可见光检测管线

- 修复 `fire_event_reporter` 生产装配为 `None` 导致可见光事件从未上报的接线缺口。
- 可见光上报阈值与画框阈值对齐为 0.25，保证“有事件就有可解释的检测框”。
- 连续两帧确认窗口 6s，归一化中心距离不超 0.3；可见光事件去抖 10s，空间去重交给 backend。
- 框内少于 5 个橙红火色像素的 fire 候选直接否决，降低夜间暗树丛高分误报。
- Apple Silicon 使用 MPS，实测推理从约 273ms 降到 53ms；`torchvision::nms` 通过 MPS fallback 回退 CPU。
- 新增 `LatestFrameVideoSource`：独立线程持续抓帧、检测线程只取最新帧，帧龄 10s 看门狗；解决 ZLM 只发 RTCP 保活时 OpenCV 读帧卡死 453s 且无日志的问题。
- 流未就绪时任务不失败，流恢复后自动开始识别。

### 32.3 端到端实火验证

- 夜间盆火多轮验证“识别 -> 两帧确认 -> 事件 -> OSD 回填 -> 空间合并 -> 驾驶舱通知”闭环。
- 事件保存带框标注图，同一火源复报增加 `report_count`。
- 火入画到事件生成的经验时延约 2–3s。
- 真火帧离线重跑分数/框位与运行时结果一致，确认黑树丛 fire 0.78 属模型误报而非画框坐标 bug。

### 32.4 悬停后激光定位

- 可见光两帧确认后立即创建同一火情事件，定位状态为 `LASER_LOCATING`，不等待悬停/测距完成才告警。
- 航线飞行时暂停，否则直接悬停；水平速度 ≤0.3m/s、垂直速度绝对值 ≤0.2m/s 稳定 1s 后开始定位。
- 取悬停后 1.5s 内新鲜 ROI，tap zoom 对准后再取帧。
- 激光目标必须落在 ROI 内；按 300ms 间隔取 3 个 NORMAL 样本，样本散布不超 15m。
- 成功时使用 DJI MSDK 直接返回的目标经纬高更新原事件为 `PRECISE`，暂定误差半径 5m；失败时更新同一事件为 `LASER_FAILED`。
- `LASER_LOCATING/LASER_FAILED` 阶段的飞机 OSD 坐标不得作为火点坐标参与去重、地图标注和任务规划。
- 定位流程结束后不自动恢复航线，由操作者确认后续动作。

### 32.5 验证与现场部署

交接文档记录的自动化验证：

- AI：168 passed、1 skipped。
- Backend：433 tests、0 failures、0 errors。0 skipped。
- RC Plus Agent：`:app:testDebugUnitTest` 通过。
- RC Plus APK：`:app:assembleDebug` 通过。
- Frontend policy tests：99 passed、0 failed。
- Frontend：`npm run build:test` 和 `npm run build` 通过。
- `git diff --check` 通过。

7 月 28 日现场完成 backend、AI 和 frontend 干净重启，RC Plus Agent 重新安装后心跳、命令轮询、OSD/HMS 上报和可见光检测正常。

现场曾发生同包名、同 `versionCode=1` 旧 APK 覆盖新包，导致 DJI UX 白屏且 Agent 不识别 `visible-fire-hold`。修复为 `versionCode=2`、`versionName=0.1.1`，重新安装后通过设备侧 DEX 检查、界面、真实 SN、推流和命令轮询反向验证。

### 32.6 7 月末尚未闭环事项

1. 完整动态流程仍待实火/实飞验收：两帧确认、悬停、tap zoom、激光三样本和同一事件 `LASER_LOCATING -> PRECISE`。
2. 夜间暗部高分误报、小余烬/稀薄烟雾漏检和真火低谷帧闪烁仍需新模型与样本。
3. Agent 冷启动时占位 SN 与真实 SN 在某些时序下仍会并存。
4. ai-service 重启/热加载后检测任务需人工重建，待 backend 定时对账自愈。
5. 旧 `FireConfirmationProcessor` 仍包含红外测温步骤，如重启自动抵近必须先拆除该生产依赖。
6. FC100 `ActionButtons` 的 antd-vue 弹窗参数遗留问题尚未处理。

## 33. 2026-06–07 阶段总结与交接口径

### 33.1 两个月的主要产出

- 6 月：完成驾驶舱真实数据整合、MSDK 设备状态隔离、FC100 AGL 口径、火情监测服务化、航线规划页大规模重构、DEM 高程、MapLibre/天地图、面状测区、高度剖面、模拟预演、规划期合规检查和 DJI 限飞区接入。
- 7 月上旬：完成 S1–S9 巡检灭火一期闭环、指令队列、15 项预检、工作台、检测提速、悬停确认、GPS 去重和激光抵近试验链。
- 7 月下旬：基于实飞修正遥测、RTK、测温、去重、推流和抵近护栏，最终将生产火情识别收敛到纯可见光 + 悬停后 DJI 激光定位。

### 33.2 文档事实源

- 当前生产状态：[CURRENT_PROJECT_STATUS_2026-08-05.md](docs/CURRENT_PROJECT_STATUS_2026-08-05.md)
- 当前运行方式：[RUNBOOK.md](RUNBOOK.md)
- 纯可见光与激光定位交接：[HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md](HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md)
- 7 月 2 日一期任务详细证据：`work-records/codex/S1–S9`
- 7 月 6–10 日检测/定位专项证据：`work-records/codex/T*`、`docs/superpowers/plans/2026-07-*`

### 33.3 阅读注意

- 6 月的 Ray-DEM、7 月上中旬的红外 YOLO/测温确认和多段抵近都是真实实现过的历史阶段，但不是 7 月末最终生产口径。
- 任务提示词、设计文档和 commit 不等于现场验收；本记录已尽量将“代码已实现”“自动化测试已通过”和“实机/实飞已验证”分开表述。
- 与当前代码冲突时，以生产分支 `feature/fire-precision-and-realtime-detection` 的已提交代码和最新状态文档为准。

## 34. 2026-09-05 Main 主线整合、分支清理与 M4T 评估

用户明确要求将 M300 分支合入 main 并清理可退役分支。已将 M300 最新提交 `2ee80d7` 快进合入并推送到 GitHub `main`，保留原有提交历史。上文第 33 节的分支说明为历史口径，后续源码以 `main` 为准；现场运行版本仍需通过实际部署产物确认。

- 当前开发目录仍为 `/Users/likewang/uavfire/.worktrees/m300-model-adaptation`，检出分支已经是 `main`。
- GitHub 分支 6 → 3，本地分支 9 → 2；旧 M300、cockpit、debug、已合入的本地开发分支及两个 PoC 分支名已清理。
- 10 个本轮归档标签已推送并核对实际 commit；独有 NCNN 方案及根目录未提交工作保持保留。
- 后端 483 项、Agent 256 项、小程序 4 项测试通过；前端仍为 322 通过、24 项既有失败。本轮未修改这些业务代码或试用规则。
- M4T 识别、内置相机和航线路径仍在；新监测命令要求配套新版 Agent。无 ADB 设备连接，本轮未安装、部署或实飞。
- 当前试用截止时间仍为北京时间 `2026-10-01 00:00`，对 M4T 和 M300 均有效。

完整清理清单、恢复标签、兼容性差异和测试依据见 [Main 整合与 M4T 兼容性评估](docs/main-integration-and-m4t-compatibility-2026-09-05.md)。
