# rcplus-msdk-agent

`rcplus-msdk-agent/` 是面向第一代 RC Plus Android 端的 DJI MSDK v5 执行层子工程。它已经从早期骨架推进到真实 MSDK 初始化、runtime loop、后端状态上报、RTMP 推流、航线执行和 OSD 上报的 phase-1 实现。

## 当前定位

- 第一代 RC Plus 上的 MSDK Agent，负责替代 Pilot 2 的部分数据面能力。
- 向 backend 上报 DualStream runtime 状态、设备能力、心跳和命令执行结果。
- 通过 MSDK `LiveStreamManager` 向 ZLMediaKit 推 RTMP：`live/{effectiveSn}-0`。
- 通过 MQTT 模拟 Cloud SDK OSD/events topic，为 backend 提供遥测数据。
- 接收后端 wayline-agent 命令，下载 KMZ 并通过 MSDK WaypointMission API 执行。
- 直接消费 MSDK RGBA 可见光帧，在 RC Plus 端通过 ONNX Runtime 执行 `fire/smoke` 识别。

最新迁移范围见 `../docs/MSDK_MIGRATION_PLAN.md`。

## 目录结构

```text
rcplus-msdk-agent/
├── README.md
├── app/
├── build.gradle.kts
├── gradle/
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

## 本地启动与检查

当前构建需要 Java 17 和 Android SDK：

```bash
cd rcplus-msdk-agent
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/usr/local/share/android-commandlinetools \
ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools \
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

安装到 RC Plus：

```bash
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/usr/local/share/android-commandlinetools \
ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools \
./gradlew :app:installDebug
adb shell am start -n com.yinxin.uavfir/.MainActivity
```

关键配置在 `gradle.properties`：

```text
agentBackendBaseUrl=http://127.0.0.1:6789/
agentMediaHost=127.0.0.1
agentMediaRtmpPort=1935
agentMediaStreamApp=live
agentMqttBrokerUrl=tcp://127.0.0.1:1883
agentWaylineSharedSecret=
agentAircraftSn=
agentGatewaySn=
agentFireOnnxEnabled=false
agentFireModelProfile=legacy416
```

`agentWaylineSharedSecret` 必须与后端环境变量 `WAYLINE_AGENT_SHARED_SECRET` 一致；两端默认均为空，
未显式配置时 Agent 火情事件和航线控制鉴权保持不可用。

`agentAircraftSn` 为空时会禁用 OSD/HMS reporters；非空时 `DjiLiveStreamController` 也会优先用该 SN 生成 ZLM stream id。

M300 RTK 负载选择与消防闭环默认采用 fail-closed 配置：

```properties
# -1 表示自动选择；检测到多个兼容负载时必须改为 0/1/2 明确指定安装位
agentPayloadPositionIndex=-1
# 仅在对应飞机、RC Plus、负载和 AI 验收完成后启用
m300FireClosedLoopEnabled=false
```

端侧同时保留两套 `fire/smoke` 模型，通过 `agentFireModelProfile` 在构建时选择：

- `legacy416`（默认）：`best.onnx`，固定输入 `1×3×416×416`，SHA-256
  `68db8102b3ae591d2f1bca3e585d8bc1850933d608ae132a86f61eee89d42271`。
- `visible960`（观察版）：`best-fire-smoke-960.onnx`，固定输入 `1×3×960×960`，SHA-256
  `24563198eb66e797ac3f32123dfe78410aeeb6ef766b936e825c3146686137a6`。该文件是把训练元数据
  `imgsz=640` 的 checkpoint 导出为 960 推理尺寸，并不是用 960 重新训练，效果和耗时仍需真机验证。

960 观察版构建参数为
`-PagentFireOnnxEnabled=true -PagentFireModelProfile=visible960 -Pm300FireClosedLoopEnabled=false`；
回退旧模型只需将 profile 改回 `legacy416` 后重新构建。
Agent 最多同时执行一次推理，只保留最新待处理帧；同类目标需在 6 秒内连续命中两帧才上报，
同一任务 10 秒内去重。后端将结果保存为 `UNLOCATED`、`MANUAL_CONFIRM` 的待确认候选，
不会据此自动抵近、测距或投放。RC Plus 真机完成 RGBA、RTMP、ONNX 推理和 UXSDK 页面共存验收前，
保持 `agentFireOnnxEnabled=false`；通过验收后再显式改为 `true` 并重新构建 APK。

识别结果不会直接从推理协程发 HTTP：Agent 先生成不超过 64 字符的稳定 `event_id`，写入
本地 SQLite `fire_event_outbox`，再由单独工作线程调用
`POST /manage/api/v1/dual-stream/tasks/{taskId}/agent-fire-events`。网络失败按
`1/2/5/10/30/60` 秒退避并在 60 秒封顶；应用或遥控器重启后继续发送。后端只有在
`fire_event` 事务完成后才返回 `accepted`，相同 `event_id` 返回 `duplicate`，两者都会让
Agent 将本地记录标记为 `DELIVERED`。Outbox 待发送量、最老记录时间和最近失败原因随心跳上报。

模型元数据声明了 Ultralytics AGPL-3.0。对外分发或商用部署前应确认训练模型及
Ultralytics 运行链的许可证是否满足项目交付要求。

## 已实现模块

- `api/`：backend Retrofit client、runtime loop、command poll/ack、status/capability 上报。
- `sdk/`：MSDK runtime adapter、设备会话、能力读取、OSD/HMS reporter。
- `stream/`：visible-first 绑定、RTMP 推流、focus-visible/focus-thermal 命令。
- `firedetection/`：RGBA letterbox、ONNX Runtime 推理、YOLO 解码/NMS、连续帧确认、SQLite Outbox 和可靠上报。
- `session/`：DualStream session state machine。
- `wayline/`：wayline command router、KMZ downloader、MSDK waypoint executor、MQTT event publisher、probe。
- `ui/`：RC Plus 本地验证控制台。

## 当前不承诺项

- 不承诺 M4T 能提供 visible + thermal 两路独立 raw stream；当前真机结论是不暴露两路独立 ComponentIndex。
- 不承诺 thermal 第二路已经出画；当前应按降级或 composite slicing 方案继续。
- 不承诺 OSD/HMS payload 字段已完成生产级验证；`mode_code` 和 `height` 语义仍需真机校准。
- 火情事件已具备本地持久化、幂等和重试；wayline 命令等其他链路仍不承诺生产级队列、鉴权和完整监控。
