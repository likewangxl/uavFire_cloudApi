# 前后端工作记录

## 0. 2026-04-21 M4T Dual-Stream Runtime Closure

本轮新增完成的主线闭环如下：

- `backend/sample`：
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
cd backend && mvn -pl sample -Dtest=DualStreamControllerTest,DualStreamServiceImplTest,CloudControlAuthStateResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
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

- RC Plus 可访问本机 backend 所在网段地址 `192.168.50.254:6789`
- Android 端 `AGENT_BACKEND_BASE_URL` 已改为 `http://192.168.50.254:6789/`
- 但 backend 之前对 `/manage/api/v1/dual-stream/agents/**` 也套用了统一 `AuthInterceptor`
- `rcplus-msdk-agent` 当前没有登录态，也不会附带 `x-auth-token`
- 所以之前的真机 loop 实际被 backend `401` 拦截，不是设备网络不通

本轮已完成：

- `backend/sample` 新增 `GlobalMVCConfigurerTest`
  - 约束只放行 `/manage/api/v1/dual-stream/agents/**`
  - 明确不放行整个 `/manage/api/v1/dual-stream/**`
- `GlobalMVCConfigurer` 已新增：
  - `/" + managePrefix + manageVersion + "/dual-stream/agents/**"`
- `rcplus-msdk-agent/gradle.properties` 已新增：
  - `agentBackendBaseUrl=http://192.168.50.254:6789/`
- backend 已重启到最新代码
- RC Plus 上已重新拉起 `com.yinxin.uavfir`

本轮验证结果：

```bash
cd backend && mvn -pl sample -Dtest=GlobalMVCConfigurerTest,DualStreamControllerTest test
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
  - `192.168.50.254:6789 -> 192.168.50.254:57938 (ESTABLISHED)`
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
cd backend && mvn -pl sample -Dtest=GlobalMVCConfigurerTest,DualStreamControllerTest,DualStreamServiceImplTest test
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
backend/sample/src/main/java/com/dji/sample/control/service/impl/ControlServiceImpl.java
backend/sample/src/main/java/com/dji/sample/control/model/param/TakeoffToPointParam.java
```

已完成内容：

- 调整起飞前置检查逻辑。
- 将机场 Dock 的空闲状态检查限定在 Dock 网关上。
- RC Plus 2 场景下不再使用 Dock 专属状态作为起飞前置条件。
- 将 `securityTakeoffHeight` 的校验最小值调整为 20，匹配官方文档限制。

已验证：

```bash
mvn -pl sample -DskipTests compile
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
- 关键踩坑：`mvn -pl sample ... compile` 不会重新编译 cloud-sdk 模块。sample 运行时仍使用 `D:\localRepository` 里的旧 cloud-sdk-1.0.3.jar，修改看起来"没生效"。必须先 `mvn -pl cloud-sdk clean install` 刷本地仓库。

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
| `backend/sample/src/main/java/com/dji/sample/control/service/impl/ControlServiceImpl.java` | `takeoffToPoint` 增加入参 / 网关类型 / 请求 JSON / `reply.output` 四段诊断日志 |
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

- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`
- `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/livestream/LivestreamAgoraUrl.java`
- `backend/sample/pom.xml`
- `backend/sample/src/main/resources/application.yml`
- `backend/sample/src/test/java/com/dji/sample/manage/service/impl/LiveStreamServiceImplAgoraConfigTest.java`

已验证：

```bash
mvn -pl sample -am -Dtest=LiveStreamServiceImplAgoraConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
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
  - `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`
  - `backend/sample/src/main/resources/application.yml`
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
  - `mvn -pl sample spring-boot:run` 启动后 Tomcat 能短暂拉起。
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
| 前端 Vite | `http://192.168.50.254:8080/` | 正常 |
| 后端 sample | `http://192.168.50.254:6789/` | 正常 |
| BASIC MQTT | `192.168.50.254:1883` | 正常 |
| DRC MQTT WS | `ws://192.168.50.254:8083/mqtt` | 正常 |
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
   - `backend/sample/src/main/java/com/dji/sample/wayline/service/impl/SDKWaylineService.java`
   - `backend/sample/src/main/java/com/dji/sample/wayline/service/impl/FlightTaskServiceImpl.java`

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
mvn -pl sample -DskipTests clean compile
mvn -pl sample spring-boot:run
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
| `backend/sample/src/main/java/com/dji/sample/wayline/service/impl/SDKWaylineService.java` | 直接接收返航事件并记录日志 |
| `backend/sample/src/main/java/com/dji/sample/wayline/service/impl/FlightTaskServiceImpl.java` | 直接接收返航事件并记录日志 |
| `backend/sample/src/main/java/com/dji/sample/control/model/dto/ReturnHomeState.java` | 返航前置判断兼容 RC OSD |
| `backend/sample/src/main/java/com/dji/sample/control/model/dto/ReturnHomeCancelState.java` | 取消返航前置判断兼容 RC OSD |
| `backend/sample/src/main/java/com/dji/sample/manage/service/impl/DeviceServiceImpl.java` | 设备模式判断优先兼容 RC OSD |

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
| MQTT 默认地址 | `application.yml` 里的 `192.168.50.254`（Mac 本机使用，无需额外参数） |
| 后端日志 | `backend/sample/logs/cloud-api-sample.log` |

Windows 上的 `run_sample.ps1` 使用 `AI/.jdk17` 并传 `--mqtt.BASIC.host=192.168.50.10`，Mac 上**不要照搬**，MQTT 地址不同。

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
mvn -pl sample \
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
mvn -pl sample spring-boot:run
```

### 11.5 反模式（不要再做）

- ❌ 用 `/usr/libexec/java_home -V` 判断有没有 JDK 11 —— 它看不到 Homebrew 的 `openjdk@11`。
- ❌ 直接 `java -jar sample/target/sample-1.10.0.jar` —— sample 的 pom 没有配 `spring-boot-maven-plugin` 的 `repackage`，打出来的 jar 不是可执行 jar，会报 "中没有主清单属性"。必须用 `spring-boot:run`。
- ❌ 把 Windows `run_sample.ps1` 里的 `--mqtt.BASIC.host=192.168.50.10` 搬到 Mac 上 —— Mac 网络里那个 IP 不通，用 `application.yml` 的默认即可。
- ❌ 只跑 `mvn -pl sample clean package` 不跑 `cloud-sdk install` —— 如果同时改了 cloud-sdk，sample 会继续依赖本地 `~/.m2/` 里的旧 jar，改动看不到。

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
sed -n '1,120p' backend/sample/src/main/resources/application.yml

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
mvn -pl sample spring-boot:run
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
- 局域网可访问 `http://192.168.50.254:8080/`

本机本次启动成功信息：

| 项 | 值 |
| --- | --- |
| 本机地址 | `http://localhost:8080/` |
| 局域网地址 | `http://192.168.50.254:8080/` |

### 12.4 本次可复用的结论

- 不能只看代码改完或测试通过，就默认“可以开始飞行测试”。
- 必须先确认前端和后端都已启动并且端口可访问。
- 后端启动前必须显式切换到 Java 11。
- 前端 `.env` 当前后端地址为 `VITE_APP_APIGATEWAY_BACKEND_HOST='http://192.168.50.254:6789'`，与本机当前启动地址一致。

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
> [HANDOFF_2026-04-19_DRC_TAKEOFF.md](/Users/likewang/uavfire/HANDOFF_2026-04-19_DRC_TAKEOFF.md)

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
  - `backend/sample/src/main/java/com/dji/sample/manage/model/dto/DualStreamLiveGroupDTO.java`
    - 新增 `playbackStatus`
    - 新增 `visiblePlayUrl`
    - 新增 `thermalPlayUrl`
  - `backend/sample/src/main/java/com/dji/sample/manage/model/dto/DualStreamAgentStatusDTO.java`
    - 新增 `playbackStatus`
    - 新增 `visiblePlayUrl`
    - 新增 `thermalPlayUrl`
  - `backend/sample/src/main/java/com/dji/sample/manage/service/impl/DualStreamServiceImpl.java`
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
mvn -pl sample -Dtest=DualStreamControllerTest,DualStreamServiceImplTest test
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

- `frontend/scripts/pilot-liveshare-config.test.mjs` 仍断言旧地址 `192.168.0.12` 和旧变量名 `config.rtmpURL`
- 当前源码实际契约已经是：
  - `CURRENT_CONFIG.rtmpURL = rtmp://192.168.50.254:1935/live/`
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
