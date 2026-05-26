# rcplus-msdk-agent

`rcplus-msdk-agent/` 是面向 RC Plus 2 Android 端的 DJI MSDK v5 执行层子工程。它已经从早期骨架推进到真实 MSDK 初始化、runtime loop、后端状态上报、RTMP 推流、航线执行和 OSD 上报的 phase-1 实现。

## 当前定位

- RC Plus 2 上的 MSDK Agent，负责替代 Pilot 2 的部分数据面能力。
- 向 backend 上报 DualStream runtime 状态、设备能力、心跳和命令执行结果。
- 通过 MSDK `LiveStreamManager` 向 ZLMediaKit 推 RTMP：`live/{effectiveSn}-0`。
- 通过 MQTT 模拟 Cloud SDK OSD/events topic，为 backend 提供遥测数据。
- 接收后端 wayline-agent 命令，下载 KMZ 并通过 MSDK WaypointMission API 执行。

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
agentBackendBaseUrl=http://192.168.0.30:6789/
agentMediaHost=192.168.0.30
agentMediaRtmpPort=1935
agentMediaStreamApp=live
agentMqttBrokerUrl=tcp://192.168.0.30:1883
agentAircraftSn=1581F7K3D249E00AM3Q3
agentGatewaySn=9N9CMA500100B8
```

`agentAircraftSn` 为空时会禁用 OSD/HMS reporters；非空时 `DjiLiveStreamController` 也会优先用该 SN 生成 ZLM stream id。

## 已实现模块

- `api/`：backend Retrofit client、runtime loop、command poll/ack、status/capability 上报。
- `sdk/`：MSDK runtime adapter、设备会话、能力读取、OSD/HMS reporter。
- `stream/`：visible-first 绑定、RTMP 推流、focus-visible/focus-thermal 命令。
- `session/`：DualStream session state machine。
- `wayline/`：wayline command router、KMZ downloader、MSDK waypoint executor、MQTT event publisher、probe。
- `ui/`：RC Plus 本地验证控制台。

## 当前不承诺项

- 不承诺 M4T 能提供 visible + thermal 两路独立 raw stream；当前真机结论是不暴露两路独立 ComponentIndex。
- 不承诺 thermal 第二路已经出画；当前应按降级或 composite slicing 方案继续。
- 不承诺 OSD/HMS payload 字段已完成生产级验证；`mode_code` 和 `height` 语义仍需真机校准。
- 不承诺生产级任务队列、鉴权、重连和监控。
