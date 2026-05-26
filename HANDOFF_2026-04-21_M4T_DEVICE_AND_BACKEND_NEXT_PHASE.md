# 2026-04-21 交接文档：M4T 真机接入方案 / 后端对接下一阶段

> 本文档用于覆盖 2026-04-21 这轮之后的最新状态。
> 如需接手双流专项，请优先看本文，再看旧的 `HANDOFF_2026-04-21_M4T_DUAL_STREAM_AND_LIVESTREAM.md`。

## 一、当前真实状态摘要

截至当前工作区，M4T 双流专项已经完成三条主线的最小 runtime closure：

1. `rcplus-msdk-agent/`
   - 最小 Android app 壳已真实构建通过
   - 已具备命令拉取 + ack 最小闭环
   - 已具备真实 DJI MSDK 工程依赖、runtime adapter 结构、设备能力抽象、后端请求契约
   - 已接入真实 `SDKManager` 初始化 / 注册、`KeyManager` 连接态与 capability 读取
   - 已接入 visible 真实流监听入口，thermal 侧保持显式保守失败
2. `ai-service/`
   - FastAPI 骨架、任务生命周期、融合占位逻辑、配置与本地启动入口已完成
   - 已具备最小 `TaskRunner`，`start` 后会生成 detection event 并回传 backend
   - 本地 `/healthz`、测试与 `compileall` 已验证
   - 还没有接入真实视频流与真实模型
3. `backend/uavfire/`
   - `dual-stream` 已具备命令发放 / 拉取 / ack 最小闭环
   - 已具备 task events 写入、查询和 Redis fallback

当前下一轮最核心的工作重点转为：

- 真机接入前置能力：让 `rcplus-msdk-agent` 从 mock 过渡到真实设备会话入口
- AI 持续执行：让 `ai-service` 从单次 runner 过渡到持续视频消费循环
- 前端双通道直播：保持暂缓，等前两项稳定后再启动媒体链路论证

2026-04-22 补充真机结论：

- RC Plus 2 上已完成真实 APK 安装、启动和 UI 验证
- `SDKManager` 初始化链路已恢复，之前由错误 helper 签名导致的 `VerifyError` 已排除
- M4T 已被识别，页面真实显示：
  - `连接状态: CAPABILITY_READY`
  - `可见光: true`
  - `红外: true`
- 点击 “启动双流” 后，真实返回为：
  - `双流启动结果: failed`
  - `原因: msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

这轮结论非常明确：

- 不是 capability 缺红外
- 不是 `thermal-stream-source-unavailable`
- 是 MSDK v5 当前 `cameraStreamManager` 路径没有暴露同 payload 同时绑定 visible/thermal 的能力

因此 Android 侧下一步不应继续坚持“thermal 失败即整体 failed”，而应转为：

- visible 优先启动
- thermal 显式降级
- 整体 `start` 在 visible 成功时返回 `applied`
- 把 thermal 降级原因原样暴露给 UI / backend / handoff

2026-04-22 再补充一条已完成结果：

- 上述 “visible 优先 + thermal 显式降级” 已经落代码并在 RC Plus 2 上回归成功
- 设备侧当前真实显示为：
  - `双流启动结果: applied`
  - `可见光: running`
  - `红外: degraded`
  - `原因: msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

这意味着：

- 设备侧运行策略已经从“整体 failed”切到“visible 可运行、thermal 降级”
- 接手人不需要再重复修这一层
- 后续主线应转到“生命周期 loop + 驾驶舱切流”，不是继续纠缠 Android 单机 UI

2026-04-22 再补一条最新代码进展：

- `rcplus-msdk-agent` 已新增 `AgentRuntimeLoop`
- App 已通过 `ProcessLifecycleOwner` 在前台启动 loop、后台停止 loop
- loop 当前会执行：
  - `DjiDeviceSession.initialize()`
  - heartbeat 上报
  - status 上报
  - capability 变化上报
  - backend command poll
- `AppServices` 已把 UI 和 runtime loop 收敛到同一套共享 `DualStreamSessionManager`

本轮本机验证已通过：

- `:app:testDebugUnitTest`
- `:app:assembleDebug`

但本轮没有完成真机复验：

- 当前 `adb devices` 为空，没有在线 RC Plus
- 所以“loop 在 RC Plus 上真实运行的网络行为”还没有被重新观察

2026-04-22 最新补充：

- 上一条“没有真机复验”的缺口已经补上
- RC Plus 重新连上后，已完成 lifecycle loop 真机网络回归
- 真实根因不是设备网络不通，而是 backend 之前把 `/manage/api/v1/dual-stream/agents/**` 也纳入了统一 JWT 鉴权
- `rcplus-msdk-agent` 当前没有登录态，不会发送 `x-auth-token`，所以旧行为会被 backend 返回 `401`
- 现已完成最小修复：
  - backend `GlobalMVCConfigurer` 只放行 `/manage/api/v1/dual-stream/agents/**`
  - 不放行整个 `/manage/api/v1/dual-stream/**`
  - Android `agentBackendBaseUrl` 已改为 `http://192.168.0.30:6789/`
- backend 本机探测结果：
  - `curl /manage/api/v1/dual-stream/agents/RC_PLUS_LOCAL/command` 已返回 `200`
- 真机侧回归结果：
  - RC Plus 上 `refresh-device-status => 连接状态: CAPABILITY_READY`
  - `可见光: true`
  - `红外: true`
  - 设备 logcat 未再出现 `401` / `ConnectException` / `UnknownHostException`
  - 本机 `lsof -nP -iTCP:6789` 可见活动连接：
    - `192.168.0.30:6789 -> 192.168.0.30:57938 (ESTABLISHED)`

这意味着：

- `rcplus-msdk-agent` 的 lifecycle loop 现在已经能从 RC Plus 真实连到 backend
- 接手人不要再花时间排查“为什么 agent 一直 401 / backend 不通”
- 下一步重点应切到：
  - backend 查询面是否足够表达 `visible running / thermal degraded / reason`
  - 驾驶舱如何直接消费 rcplus 新链路

2026-04-22 再补一条最新进展：

- 上一条“backend 查询面是否足够表达运行态”的缺口已经补上
- Android agent 现在会通过 status 上报：
  - `liveStatus`
  - `currentMode`
  - `visibleState`
  - `thermalState`
  - `statusReason`
- backend `DualStreamAgentStatusDTO` / `DualStreamLiveGroupDTO` 已扩展这些字段
- `DualStreamServiceImpl.acceptStatus()` 已改成非空 merge，避免覆盖 heartbeat 已写入的连接态
- 驾驶舱 `leadership-cockpit.vue` 的 live tab 现在已经切到读取 `/manage/api/v1/dual-stream/groups/{droneSn}`
- 也就是说，驾驶舱 live tab 现在读的是 rcplus/backend 的 dual-stream 运行态，而不是旧 Agora 状态

但要特别注意：

- 这次切掉的是“状态面”，不是“真实视频面”
- 当前仓库里仍没有一条来自 rcplus 新链路、可供 Web 直接播放的视频地址
- 所以不能把这轮结果误判成“驾驶舱视频已彻底切到 rcplus 新媒体流”
- 真正的视频切流还差一个前提：
  - rcplus 或 backend 先产出 Web 可直接消费的媒体地址 / 播放配置

---

## 二、已完成工作详细标注

### 1. `rcplus-msdk-agent/` 已完成项

#### 1.1 环境与构建

已完成：

- 安装并验证 Java 17
- 安装 Android commandline tools
- 安装 `platforms;android-34`
- 安装 `build-tools;34.0.0`
- 接受 Android SDK licenses
- 真实跑通 `:app:assembleDebug`

本机当前用于验证的关键环境变量：

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools
```

#### 1.2 已落地代码范围

主要文件：

- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/AgentConnectionState.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionState.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/StreamChannelType.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/StreamProvider.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/MockStreamProvider.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/CameraCapability.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGateway.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiRuntimeAdapter.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiMsdkRuntimeAdapter.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/MsdkSdkClient.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/MsdkKeyValueClient.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/CapabilityRepository.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/BoundStreamState.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/MsdkStreamBinder.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/DjiMsdkStreamBinder.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/RealMsdkStreamProvider.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/App.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/AppContextHolder.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/DualStreamApi.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentApiEnvelope.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentBackendClient.kt`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/CommandPollingCoordinator.kt`
- DTO:
  - `AgentHeartbeatRequest.kt`
  - `AgentStatusRequest.kt`
  - `CapabilityReportRequest.kt`
  - `AgentCommandResponse.kt`
  - `AgentCommandAckRequest.kt`

当前定性：

- 已完成“状态机 + capability + HTTP 契约组装 + 命令 poll/ack 最小闭环”
- 已完成“MSDK 工程接入 + SDK 初始化/注册 + 连接态/能力读取 + visible 流监听入口”
- 未完成“真机联调 + thermal 双流能力确认 + 真实媒体转发 + 生命周期调度”

#### 1.3 已验证项

已验证命令：

```bash
cd rcplus-msdk-agent
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

已通过的测试范围：

- `DjiMsdkRuntimeAdapterTest`
- `DualStreamSessionManagerTest`
- `CapabilityRepositoryTest`
- `AgentBackendClientTest`
- `CommandPollingCoordinatorTest`
- `DjiDeviceSessionTest`
- `DjiSdkGatewayImplTest`
- `RealMsdkStreamProviderTest`

#### 1.4 不要误判的点

- `DjiSdkGatewayImpl` 现在会在存在 App context 时默认走 `DjiMsdkRuntimeAdapter`，不再是固定返回值
- `MockStreamProvider` 永远成功，不代表双流视频已经可用
- `RealMsdkStreamProvider` 现在已有双路绑定入口和本地绑定状态，且 visible 已走真实 `CameraStreamManager` 监听入口
- thermal 侧当前不是“已经成功接通”，而是显式保守失败，用来诚实暴露 MSDK v5 是否支持同 payload 同时 visible/infrared 绑定
- `AgentBackendClient` 已对齐 backend `data` 包装层，但 Retrofit 实例化和真实调度循环仍未接入 App 生命周期
- `App.attachBaseContext` 现在会优先尝试 `com.cySdkyc.clx.Helper.install(...)`，但这只是兼容钩子，不替代真机验证

### 2. `ai-service/` 已完成项

#### 2.1 已落地代码范围

主要文件：

- `ai-service/app/main.py`
- `ai-service/app/api/routes.py`
- `ai-service/app/models/task.py`
- `ai-service/app/models/event.py`
- `ai-service/app/models/frame.py`
- `ai-service/app/services/task_registry.py`
- `ai-service/app/services/task_runner.py`
- `ai-service/app/fusion/service.py`
- `ai-service/app/inference/visible/detector.py`
- `ai-service/app/inference/thermal/analyzer.py`
- `ai-service/app/video/source.py`
- `ai-service/app/config/settings.py`
- `ai-service/scripts/run-dev.sh`

当前定性：

- 已完成“可运行服务骨架 + 任务生命周期 + 融合占位 + 配置入口 + 最小 task runner”
- 未完成“真实流消费 + 持续任务循环 + 真实模型”

#### 2.2 已验证项

已验证命令：

```bash
cd ai-service
./.venv/bin/python -m pytest tests -q
./.venv/bin/python -m compileall app
./scripts/run-dev.sh
curl http://127.0.0.1:9000/healthz
```

真实结果：

- `11 passed`
- `compileall` 通过
- `uvicorn` 可启动
- `/healthz` 返回 `{"status":"ok"}`

#### 2.3 不要误判的点

- `VisibleDetector` / `ThermalAnalyzer` 还是规则占位，不代表真实模型效果
- `TaskRegistry` 仍是进程内存，不代表生产级任务调度
- 当前事件输出已经接上 backend callback，但 runner 仍是单次执行，不是持续消费

### 3. `backend/uavfire/` 已完成项

当前定性：

- 已完成 `dual-stream` runtime coordinator 最小闭环
- 已具备 `/groups/{drone_sn}/start|stop|focus`、`/agents/{drone_sn}/command`、`/command/ack`
- 已具备 task event POST/GET 以及 Redis fallback

已验证项：

```bash
cd backend
mvn -pl uavfire -Dtest=DualStreamControllerTest,DualStreamServiceImplTest,CloudControlAuthStateResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- `16 tests, 0 failures`

### 4. 文档与记录已更新

已经更新或新增：

- `WORK_RECORD.md`
- `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
- `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`

本轮另外新增了下一阶段实施计划：

- `docs/superpowers/plans/2026-04-21-m4t-device-backend-integration.md`

---

## 三、当前未完成核心缺口

### 1. `rcplus-msdk-agent` 还没有完成应用生命周期闭环

当前缺口：

- 真机注册、连接态、capability、visible 优先降级都已验证，不再是 blocker
- 还没有把 heartbeat / status / capability / command poll 持续 loop 接进 App 生命周期
- 还没有形成“前台运行时自动上报、退后台安全停止”的稳定执行模型
- 还没有把 visible 真实媒体数据向下游消费或分发

建议接手顺序：

1. 先把 `CommandPollingCoordinator`、heartbeat、status、capability 上报 loop 挂进 `App` 或前台 service 生命周期
2. 让 backend 可以持续看到 agent 在线状态、当前 capability 和 visible/thermal 运行态
3. 在此基础上再做真实媒体下游消费，不要反过来

当前这个顺序中的第 1 步已经完成到“代码落地 + 本机验证通过”，下一位需要继续完成：

- 真机复验 loop
- 把 backend 地址改成 RC Plus 可访问地址，而不是默认 `127.0.0.1:6789`
- 观察 backend 实际是否收到 heartbeat/status/capability

### 2. `ai-service` 还没有持续执行链

当前缺口：

- 没有持续 runner/worker
- 没有真实视频流消费
- 没有时间窗口对齐与证据落盘

### 3. 前端驾驶舱仍在使用旧的 Agora 单路直播链

当前缺口：

- 驾驶舱当前直播画面通过 `WorkspaceLivestreamPanel` 走旧的 `live/agora/config` + Agora RTC 订阅链
- 当前前端展示的不是 `rcplus-msdk-agent` 这次真机改造后的新链路
- 还没有完成“废弃 Agora，驾驶舱直接接 rcplus 新链路”

关键代码定位：

- 驾驶舱页面：`frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- 当前播放器：`frontend/src/components/WorkspaceLivestreamPanel.vue`
- 前端 Agora 配置请求：`frontend/src/api/manage.ts#getAgoraConfig`
- 后端旧直播控制器：`backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/LiveStreamController.java`
- 后端旧直播服务：`backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/LiveStreamServiceImpl.java`

建议接手顺序：

1. 不要先删旧 Agora 代码；先让新链路具备稳定的 agent 生命周期和可消费媒体输出
2. 等新链路具备前端可订阅或可播放入口后，再切驾驶舱播放器
3. 切流完成后，再下线 Agora 旧入口，避免中间出现驾驶舱无画面的空档

## 四、建议直接接手的工作计划

建议接手人优先执行：

- `docs/superpowers/plans/2026-04-22-rcplus-runtime-loop-and-cockpit-cutover.md`

该计划覆盖两条主线：

1. `rcplus-msdk-agent` 生命周期 loop 接入
2. 驾驶舱从旧 Agora 切到新 rcplus 链路的前置条件与切流顺序

补充说明：

- 其中第 1 条现在已经完成到“代码落地”
- 接手时应从“真机复验 + backend 可观测验证”继续，而不是从零开始设计 loop

---

## 四、下一轮建议目标

建议把下一轮目标严格限定为：

1. `rcplus-msdk-agent` 真机前置能力接入入口
2. `rcplus-msdk-agent` 真实双流源接入
3. `ai-service` 持续任务运行器与真实视频消费
4. 前端双通道直播方案 Spike 与正式选型

### 暂缓待办：前端双通道直播主画面 + 小窗模式

已经确认该事项**尚未开始**，且当前前后端直播链路仍是“单路直播 + 镜头切换”，不是“双通道并播”：

- `frontend/src/components/WorkspaceLivestreamPanel.vue` 当前只有单个播放器挂载点和单个 Agora client。
- `backend/uavfire` 当前 `live/streams/start` 语义仍围绕单个 `video_id`。
- 现阶段没有“可见光 + 红外”两路独立可订阅流的统一契约。

因此该事项先加入待办，但**不作为下一步主线**。处理顺序后置到以下主线完成之后再启动论证与落地：

1. 后端 dual-stream 协调器真正形成命令/任务协调闭环
2. `rcplus-msdk-agent` 真机能力与真实流接入完成最小闭环
3. `ai-service` 任务运行器、时间对齐、事件回传与查询链路稳定

后续单独开题时再决定：

- 继续沿用现有 Agora / WebRTC 栈并升级为双流契约
- 或切换到更适合双通道主画面 + 小窗的媒体方案（如 SRS WHEP/WHIP 或 LiveKit）

不要在下一轮直接扩散到：

- 前端双画面联调
- 真正的视频推流库接入
- 真实模型权重训练或效果优化
- 生产级存储、监控、权限

---

## 五、下一轮推荐执行顺序

### 第一步：先做后端 dual-stream 协调层

原因：

- `rcplus-msdk-agent` 和 `ai-service` 都需要稳定的后端入口
- 不先定后端路径，客户端实现会来回改 DTO

建议先实现：

- `/manage/api/v1/dual-stream/agents/{drone_sn}/heartbeat`
- `/manage/api/v1/dual-stream/agents/{drone_sn}/status`
- `/manage/api/v1/dual-stream/agents/{drone_sn}/capability`
- `/manage/api/v1/dual-stream/groups/{drone_sn}`
- `/manage/api/v1/dual-stream/groups/{drone_sn}/start`
- `/manage/api/v1/dual-stream/groups/{drone_sn}/stop`
- `/manage/api/v1/dual-stream/groups/{drone_sn}/focus`

### 第二步：让 `rcplus-msdk-agent` 真实上报后端

建议顺序：

1. `AgentBackendClient` 增加真正的 `send*`
2. 增加 `AgentReporter`
3. 把 heartbeat/status/capability 串到显式入口

### 第三步：再做真机接入前置能力

建议顺序：

1. `DjiDeviceSession`
2. `DjiSdkGateway` 补设备连接态
3. capability-ready 后上报后端
4. `RealMsdkStreamProvider` 先占位

### 第四步：最后让 `ai-service` 回传 backend event

建议顺序：

1. `BackendClient`
2. `POST /manage/api/v1/dual-stream/tasks/{task_id}/events`
3. backend 保存 event 并挂到 group/task 查询

### 第五步：暂缓项，不进入本轮主线

- 前端双通道直播主画面 + 小窗模式
- 双流媒体技术栈替换（Agora / SRS / LiveKit 重新选型）
- 驾驶舱双画面与 AI 告警联调

---

## 六、直接执行时优先看的文件

### 文档

- `WORK_RECORD.md`
- `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md`
- `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
- `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`
- `docs/superpowers/plans/2026-04-21-m4t-device-backend-integration.md`

### 后端

- `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/LiveStreamController.java`
- `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/LiveStreamServiceImpl.java`
- `backend/uavfire/src/main/java/com/yx/uavfire/control/controller/RcAircraftController.java`
- `backend/uavfire/src/main/java/com/yx/uavfire/control/service/impl/RcAircraftControlServiceImpl.java`

### Android

- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/`
- `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/`

### AI

- `ai-service/app/api/routes.py`
- `ai-service/app/services/task_registry.py`
- `ai-service/app/fusion/service.py`
- `ai-service/app/config/settings.py`

---

## 七、下一轮验证建议

后端新增后，优先验证：

```bash
mvn -pl backend/uavfire -Dtest=DualStreamServiceImplTest,DualStreamControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Android 新增后，优先验证：

```bash
cd rcplus-msdk-agent
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools
./gradlew :app:testDebugUnitTest
```

AI 新增后，优先验证：

```bash
cd ai-service
./.venv/bin/python -m pytest tests -q
./.venv/bin/python -m compileall app
```

---

## 八、现场/真机前置条件

如果下一轮开始摸真机，至少先确认这些条件：

- RC Plus 2 可安装并运行当前 Android app
- M4T 与 RC Plus 2 已正确对频并上线
- DRC 权限和 session 已打开
- 可采集真实设备在线状态、payload 能力信息
- 现场允许做非正式双流/能力探测 PoC

当前仍然没有证据证明这些条件已经全部满足，所以不要把“代码入口写好”误判成“真机链路已通”。

---

## 九、2026-04-22 新增交接：驾驶舱播放地址契约已补，但真实视频仍未切过来

### 9.1 这轮已经完成的事

- `dual-stream group` DTO 已扩展出三项新字段：
  - `playbackStatus`
  - `visiblePlayUrl`
  - `thermalPlayUrl`
- Android agent 现在会显式上报：
  - `playbackStatus = awaiting-media-url`
  - `visiblePlayUrl = null`
  - `thermalPlayUrl = null`
- 驾驶舱 live tab 已能明确展示：
  - 当前是否已有 Web 播放地址
  - visible 地址是否存在
  - thermal 地址是否存在

### 9.2 这轮没有完成的事

- 还没有任何真实 `visiblePlayUrl`
- 还没有任何真实 `thermalPlayUrl`
- 驾驶舱现在展示的是 `rcplus` 运行态与播放地址占位信息，不是真实新视频

### 9.3 不要误判的点

- “驾驶舱不再依赖 Agora 的状态面” 不等于 “驾驶舱视频已经切成新链路”
- 当前真实状态应表述为：
  - live tab 状态面：已切到新链路查询
  - live tab 视频面：尚未切到新链路真实媒体

### 9.4 接手顺序

1. 在 `rcplus-msdk-agent` 或 backend 中打通新链路媒体输出
2. 让 Android agent 状态上报真实 `visiblePlayUrl`
3. 前端检测 `visiblePlayUrl` 非空后，再接真实播放器
4. 真实视频验证通过后，再评估是否下线 Agora 相关代码

### 9.5 新增待办：部署 ZLMediaKit 媒体中枢

- 已在仓库新增部署脚手架：
  - `deployment/zlmediakit/docker-compose.yml`
  - `deployment/zlmediakit/.env.example`
  - `deployment/zlmediakit/README.md`
- 当前这台开发机未安装 `docker`，因此只是“部署材料已准备”，不是“服务已启动”
- 下一步待办：
  1. 准备一台已安装 Docker 的主机或云机
  2. 按 `deployment/zlmediakit/README.md` 启动 ZLMediaKit
  3. 确认至少开放：
     - `1935/tcp`
     - `8080/tcp`
     - `10000/tcp`
     - `10000/udp`
  4. 记录 ZLMediaKit 对外地址
  5. 再把 `visiblePlayUrl` / `thermalPlayUrl` 接回 Android agent 和驾驶舱

### 9.6 新增下一阶段主线：`ai-service` 从占位版升级到真实火情识别

当前必须先统一认知：

- `ai-service` 现在不是“真实火情识别服务”，而是“服务骨架 + 占位识别分数器”
- 真实依据见：
  - `ai-service/app/services/task_runner.py`
  - `ai-service/app/inference/visible/detector.py`
  - `ai-service/app/inference/thermal/analyzer.py`
- 当前逻辑本质上是：
  - `visible_stream_url` 有值就返回固定分数
  - `thermal_stream_url` 有值就返回固定分数
  - 然后 fusion 产出 event

这意味着当前可以验证：

- task 创建 / start / stop / events
- event 回传 backend
- AI 服务链路闭环

当前不能验证：

- 真实火情发现
- 真实火情识别
- 基于真实 visible / thermal 媒体输入的识别效果

#### 下一阶段工作分解

1. **阶段 1：真实视频输入层**
   - 在 `ai-service/app/video/source.py` 落真实拉流与解码实现
   - 至少支持 `RTSP` 或录制文件输入
   - 输出标准化 `FramePacket`
   - 增加断流重连、时间戳、通道标记

2. **阶段 2：visible 真火情检测**
   - 替换 `ai-service/app/inference/visible/detector.py` 的固定分数逻辑
   - 接入真实火焰/烟雾检测模型
   - 输出 bbox、class、confidence、source_ts
   - 增加连续帧稳定性判断

3. **阶段 3：thermal 真分析与双流对齐**
   - 替换 `ai-service/app/inference/thermal/analyzer.py` 的占位逻辑
   - 先做红外伪彩图热点分析，不以温度矩阵为前置
   - 在 `ai-service/app/fusion/service.py` 增加时间窗口对齐与空间近邻匹配

4. **阶段 4：持续运行器与事件增强**
   - 把 `ai-service/app/services/task_runner.py` 从 `run_once()` 升级为持续消费循环
   - 增强 detection event 结构，补证据字段、风险状态与截图/关键帧链路

#### 最短落地主线

如果下一轮目标是“尽快看到真识别”，建议优先顺序是：

1. 真实 `VideoSource`
2. visible 真检测
3. 持续 runner
4. thermal 真分析
5. 双流 fusion
6. 事件与证据增强

#### 待办 checklist

- [ ] 明确对外口径：当前 `ai-service` 仅为“服务骨架 + 占位识别分数器”，不能表述为“真实火情识别已可用”
- [ ] 先完成 `ai-service/app/video/source.py` 的真实视频输入层，至少支持 `RTSP` 或录制文件，并补齐 `FramePacket`、时间戳、通道标记、断流重连
- [ ] 再替换 `ai-service/app/inference/visible/detector.py` 占位逻辑，接入真实 visible 火焰/烟雾检测
- [ ] 然后把 `ai-service/app/services/task_runner.py` 从 `run_once()` 升级为持续消费循环
- [ ] 之后补 `ai-service/app/inference/thermal/analyzer.py` 的真实热区分析
- [ ] 再增强 `ai-service/app/fusion/service.py`，加入时间窗口对齐、空间近邻匹配和单路降级
- [ ] 最后扩展 detection event，补证据字段、风险状态和关键帧/截图链路

#### 资源判断

- 当前占位版 `ai-service` 开发验证不强依赖 GPU
- 一旦进入真实视频推理与真实火情识别，建议单独评估 GPU 资源，不要再按当前占位版的资源占用判断
