# 驾驶舱可见光直播 E2E 联调清单（2026-05-21 更新）

对应任务：`HANDOFF_2026-04-24_LOCAL_ZLM_RCPLUS_DUAL_STREAM.md` §七.第一步 + §十"驾驶舱画面"勘误。本清单只覆盖**可见光主通道出画**，热成像第二路另见 `MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md`。

当前工作区基准 IP 是 `192.168.2.34`。旧版清单中的 `192.168.50.254` 是历史 WiFi 环境。

## 0. 事先确认

```
backend (java)   : 6789
frontend (vite)  : 8080
zlmediakit       : 1935 / 58925 / 8554
redis            : 6379
mysql            : 3306
ai-service       : 9000
```

健康探活：

```bash
curl -fsS -o /dev/null -w '%{http_code}\n' http://192.168.2.34:6789/                          # 期望 302 或登录重定向
curl -fsS -o /dev/null -w '%{http_code}\n' http://192.168.2.34:8080/                          # 期望 200
curl -fsS "http://192.168.2.34:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy"
```

URL 三方对齐（仓库当前实际配置）：
- agent RTMP publish：`rtmp://192.168.2.34:1935/live/{effectiveSn}-0`
- backend 兜底 play URL：`webrtc://192.168.2.34:58925/live/{droneSn}-0`
- 航线触发 AI URL：`rtsp://192.168.2.34:8554/live/{droneSn}-0`
- 驾驶舱 ZLM signaling：`http://192.168.2.34:58925/index/api/webrtc?app=live&stream={streamId}&type=play`

注意：agent 的 `effectiveSn` 优先取 `AGENT_AIRCRAFT_SN`，而 cockpit 当前默认查 `RC_PLUS_LOCAL` 的 DualStream group。若两者不同，要确认 backend group 生成的 `visiblePlayUrl`、ZLM 实际 stream id、航线 AI 的 `droneSn` 都指向同一条 `live/{sn}-0` source。cockpit 已不再自动启动 Pilot 2 / Cloud SDK livestream 来覆盖 `visiblePlayUrl`。

USB 联调注意：若 RC Plus 的 Wi-Fi 不在 `192.168.2.0/24`，可以用 ADB reverse 联通 agent 到 Mac：

```bash
adb reverse tcp:6789 tcp:6789
adb reverse tcp:1883 tcp:1883
adb reverse tcp:1935 tcp:1935
```

然后安装 agent 时把 app 内部连接目标改成 localhost：

```bash
./gradlew :app:installDebug \
  -PagentBackendBaseUrl=http://127.0.0.1:6789/ \
  -PagentMediaHost=127.0.0.1 \
  -PagentMqttBrokerUrl=tcp://127.0.0.1:1883
```

浏览器、backend 返回的播放 URL、ZLM `externIP` 仍保持 `192.168.2.34`。

## 1. RC Plus 2 准备

```bash
adb devices -l                                     # 期望见 DJI_RC_PLUS_2
cd /Users/likewang/uavfire/rcplus-msdk-agent
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/usr/local/share/android-commandlinetools \
ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools \
./gradlew :app:installDebug
adb shell am start -n com.yinxin.uavfir/.MainActivity
```

UI 上等出现：

- `连接状态: CAPABILITY_READY`
- `可见光: true` / `红外: true`

## 2. 启动双流

UI 点 "启动双流" 或 ADB：

```bash
adb shell input tap <按钮坐标>     # 或直接走 UI
```

期望 backend 侧（用 redis-cli 看）：

```bash
redis-cli -h 192.168.2.34 GET 'dual-stream:group:RC_PLUS_LOCAL'
```

应包含：

```json
{
  "session_state": "RUNNING",
  "live_status": "RUNNING",
  "current_mode": "DUAL",
  "visible_state": "running",
  "thermal_state": "degraded",
  "playback_status": "visible-playback-ready",
  "visiblePlayUrl": "webrtc://192.168.2.34:58925/live/RC_PLUS_LOCAL-0"
}
```

验证 ZLM 实际收到了 RTMP：

```bash
curl -fsS 'http://192.168.2.34:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy&schema=rtmp' | jq '.data[] | {app, stream}'
```

应该看到 `app=live, stream=<effectiveSn>-0`。

如果看不到：
- agent 侧 logcat：`adb logcat -s DjiLiveStreamController` 看是否真的调用了 `liveStreamManager.startStream`
- agent 侧 `BuildConfig.AGENT_MEDIA_HOST` 当前应是 `192.168.2.34`（来自 `gradle.properties`），`AGENT_MEDIA_RTMP_PORT=1935`，`AGENT_MEDIA_STREAM_APP=live`
- 确认 ZLM 在监听：`lsof -nP -iTCP:1935 -sTCP:LISTEN`

## 3. 驾驶舱出画

浏览器打开 `http://192.168.2.34:8080/`，点导航 "驾驶舱"，点 "直播画面" tab。

期望：
- 主画面区域出现 RC Plus 2 当前可见光镜头实时画面
- 右下小窗保持热成像状态卡（无第二路视频）
- live tab 顶部 status pill 显示 "可见光 running / 热成像 degraded"

如果主画面卡 "播放器加载中"：
- F12 看 console 是否报 `failed-to-load-zlmrtc-client` —— ZLM HTTP 端口被防火墙挡了
- F12 Network 看 `/index/api/webrtc?app=live&stream=...` 的响应 code，401/406/-1 都意味着 ZLM 没拿到 stream

如果主画面卡 "播放失败" 且报 `zlm-offer-answer-exchange-failed`：
- ZLM 内部没有 `live/RC_PLUS_LOCAL-0` 这条 source，回到 §2 检查 RTMP 推流
- 或者 ZLM 的 SDP 候选地址带的是错的 IP（`externIP=192.168.2.34` 要对上当前 wifi）

如果主画面卡 "播放失败" 且报 `zlm-connection-failed`：
- 浏览器和 ZLM 之间 WebRTC 候选地址不通；本机内网应该不会，跨网时要确认 `ZLM_WEBRTC_TCP_PORT=8000` / `ZLM_WEBRTC_UDP_PORT=10000` 已经在防火墙放行

## 4. 关键文件 / 入口（按出问题概率排序）

- agent RTMP 推流：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiLiveStreamController.kt:23-44`
- agent 可见光绑定：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt:25-32`
- agent 启动双流入口：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/RealMsdkStreamProvider.kt:15-41`
- backend 兜底 URL：`backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`（`buildPlaybackUrl`）
- backend 直播配置：`backend/uavfire/src/main/resources/application.yml`（`livestream.playback.webrtc-host` / `webrtc-port`）
- 驾驶舱播放器：`frontend/src/pages/page-web/projects/leadership-cockpit.vue`（`mountPlayerInstance` / `syncLivePlayers`，约 509-630 行）
- ZLM 配置：`deployment/zlmediakit/.env`（`ZLM_PUBLIC_HOST=192.168.2.34`）+ `deployment/zlmediakit/config/config.ini`（`externIP=192.168.2.34`）

## 5. 出问题不要做的事

- **不要切 IP**：当前所有配置都应统一在 `192.168.2.34`，除非实际网络已变更
- **不要指望 cockpit 自动拉起 Pilot 2 直播**：这个 Cloud SDK hack 已移除，驾驶舱只消费 DualStream group 里的 URL
- **不要在驾驶舱用 jswebrtc.Player**：已经接的是 `ZLMRTCClient.Endpoint`（WebRTC，不是 RTMP-over-WS），HANDOFF 旧描述是错的
- **不要在 agent 端等 thermal 第二路真出画**：当前实现就是 `degraded`，要做真双路看 `MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md`
