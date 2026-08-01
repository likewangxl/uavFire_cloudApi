# UAVFire 生产运行手册

本文只描述当前 Agent 可见光生产链路。生产启动清单固定为 backend、frontend、
ZLMediaKit 和 RC Plus Agent。离线模型工具不在此清单中，也不是生产 fallback。

当前局域网示例地址为 `172.20.10.7`。换网后同步检查 backend、frontend、Agent
和 ZLMediaKit 的实际可达地址。

## 1. 发布前必须满足

```bash
git status --short
./scripts/agent-visible-fire-static-check.test.sh
```

然后按
[`docs/runbooks/agent-visible-fire-closed-loop-acceptance.md`](docs/runbooks/agent-visible-fire-closed-loop-acceptance.md)
执行完整自动门禁。以下项目在 RC Plus 不可用时必须记为
`BLOCKED_PENDING_DEVICE`，不能写成通过：

- visible-960 PyTorch/TFLite/NCNN 三引擎逐样本对比；
- 正式 APK 与 UXSDK、RTMP、推理并行 30 分钟 soak；
- 实激光成功与 OSD 降级；
- 无桨台架和受控飞行。

生产 detector 必须保持 `VISIBLE_FIRE_DETECTION_ENABLED=false`，直到 fire 与 smoke
全部完成受控飞行验收。

## 2. 环境

| 组件 | 要求 | 默认端口 |
| --- | --- | --- |
| frontend | Node.js、npm | `8080` |
| backend | Java 11、Maven、MySQL、Redis、MQTT | `6789` |
| BASIC MQTT | Broker | `1883` |
| DRC MQTT WebSocket | Broker | `8083` |
| ZLMediaKit RTMP | Docker/Compose | `1935` |
| ZLMediaKit HTTP/WebRTC API | Docker/Compose | `58925` |
| RC Plus Agent | Java 17、Android SDK 34、ADB | 设备侧 |

端口 `9000` 可能被 MinIO 或 ZLMediaKit RTP proxy 使用，不能仅凭端口号判断服务职责。

## 3. 精确 preflight

在生产启动前记录时间、提交和配置摘要：

```bash
date -u +%Y-%m-%dT%H:%M:%SZ
git rev-parse HEAD
git status --short
sha256sum rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json
sha256sum rcplus-msdk-agent/app/src/main/assets/fire-detection/visible-fire-960.ncnn.param
sha256sum rcplus-msdk-agent/app/src/main/assets/fire-detection/visible-fire-960.ncnn.bin
rg 'VISIBLE_FIRE_DETECTION_ENABLED' rcplus-msdk-agent/app/build.gradle.kts
./scripts/agent-visible-fire-static-check.test.sh
```

人工确认：

- MySQL、Redis、BASIC MQTT、DRC MQTT 地址正确且可达；
- Agent backend、MQTT、媒体地址指向同一环境；
- 模型 manifest 为 `PROVISIONAL_NCNN_SELECTED`、`enabledByDefault=false`、
  `visible960GatePassed=false`；
- APK 只包含一个 NCNN runtime 和一套 param/bin；
- 没有第二个本地火情控制会话、人工接管或未解决的恢复状态；
- ZLMediaKit 不健康只影响显示，不得触发检测降级或后端定位编排。

## 4. 启动 backend

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire -DskipTests compile
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire spring-boot:run
```

确认 `http://localhost:6789` 可访问，并检查数据库、Redis 和 MQTT 日志。不要依赖
devtools 热重启完成生产发布；代码或配置变化后执行完整进程重启。

## 5. 启动 frontend

```bash
cd frontend
npm ci
npm run build
npm run serve
```

确认 `frontend/env/.env` 的 backend 地址能被浏览器或遥控器访问。页面状态必须通过
共享中文映射显示，未知值显示“未知状态”。

## 6. 启动 ZLMediaKit（仅显示）

```bash
cd deployment/zlmediakit
cp -n .env.example .env
docker compose up -d
docker compose ps
curl -s 'http://localhost:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy'
```

Agent 发布 `rtmp://172.20.10.7:1935/live/{droneSn}-0`，驾驶舱通过 WebRTC 播放。
ZLMediaKit/RTMP/WebRTC 不提供推理帧、不参与暂停、ROI、激光或恢复。

## 7. 构建与安装 Agent

```bash
cd rcplus-msdk-agent
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/usr/local/share/android-commandlinetools \
ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools \
./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

只有设备可用且 acceptance runbook 的前置门禁允许时才安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.yinxin.uavfir | rg 'versionCode|versionName'
adb shell am start -n com.yinxin.uavfir/.MainActivity
```

安装后必须从设备拉回 APK 并重新计算哈希，核对 runtime、模型、版本和 manifest。

## 8. 正常运行边界

- 后端只负责监测 arm/disarm 意图、可靠接收、事件、告警、任务和人工控制；
- Agent 是唯一检测、暂停、悬停、ROI、激光和自动恢复执行端；
- 第一条视觉确认报告不等待图片、悬停或激光；
- 激光失败只保存飞机 OSD 观测坐标，不能生成火点坐标；
- Agent/后端断网时 Outbox 保序重放；
- 人工接管、任务 ID 变化、安全门异常或恢复证据不完整时 fail-closed；
- backend 不自动下发旧的逐步定位动作。

## 9. 排障

### 后端或告警不可用

检查 MySQL、Redis、MQTT、Agent JWT、`agent-report` HTTP 状态和通知 Outbox。HTTP
ACK 只有在事件、历史和通知事务提交后才有效。不要启动另一套正式 detector 绕过故障。

### 驾驶舱无画面

检查 ZLMediaKit 媒体列表、Agent RTMP publish 和浏览器 WebRTC Network。即使画面
不可见，本地检测状态必须通过 Agent heartbeat 独立判断。

### Agent 未运行检测

检查 detector intent/state/health/reason、manifest 哈希、帧新鲜度、SQLite/Outbox、
当前 aircraft SN、active stream SN 和 active mission ID。缺失或不一致必须保持关闭。

### 无法恢复航线

保留人工飞行控制，检查原 mission/断点、稳定悬停、安全信号、激光关闭状态和恢复
回调。不得循环恢复或猜测任务身份。

## 10. 回滚

回滚的第一动作是关闭 Agent detector arming，而不是切换另一检测服务：

1. 从驾驶舱停止监测，确认 Agent heartbeat 为非运行状态；
2. 若无法确认，停止 Agent 自动检测入口并保留 DJI 人工飞行控制；
3. 保留 backend、frontend 和 ZLMediaKit 供事件查看、人工操作与画面显示；
4. 导出 Agent SQLite、logcat、backend 日志、通知 Outbox 和浏览器日志；
5. 只有在飞机安全落地后才能回退 APK；
6. 回退版本仍必须默认关闭检测，且不得恢复后端自动定位编排。

完整回滚证据和发布判定见 acceptance runbook。
