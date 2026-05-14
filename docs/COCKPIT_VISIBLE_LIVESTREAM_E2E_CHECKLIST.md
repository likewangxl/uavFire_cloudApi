# 驾驶舱可见光直播 E2E 联调清单（2026-04-25）

对应任务：`HANDOFF_2026-04-24_LOCAL_ZLM_RCPLUS_DUAL_STREAM.md` §七.第一步 + §十"驾驶舱画面"勘误。本清单只覆盖**可见光主通道出画**，热成像第二路另见 `MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md`。

## 0. 事先确认（已在 2026-04-25 验过）

```
backend (java)   : 6789  LISTEN  ✓
frontend (vite)  : 8080  LISTEN  ✓
zlmediakit       : 1935 / 58925  LISTEN（SSH 端口转发，PID 1621） ✓
redis            : 6379  LISTEN  ✓
mysql            : 3306  LISTEN  ✓
```

健康探活：

```bash
curl -fsS -o /dev/null -w '%{http_code}\n' http://192.168.50.254:6789/                          # 期望 302
curl -fsS -o /dev/null -w '%{http_code}\n' http://192.168.50.254:8080/                          # 期望 200
curl -fsS http://192.168.50.254:58925/index/api/getServerConfig                                   # 期望返回 JSON（即便 Please login first 也算 ZLM 在跑）
```

URL 三方对齐（仓库当前实际配置）：
- agent RTMP publish：`rtmp://192.168.50.254:1935/live/{droneSn}-0`
- backend 兜底 play URL：`webrtc://192.168.50.254:58925/live/{droneSn}-0`
- 驾驶舱 ZLM signaling：`http://192.168.50.254:58925/index/api/webrtc?app=live&stream={droneSn}-0&type=play`

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
redis-cli -h 192.168.50.254 GET 'dual-stream:group:RC_PLUS_LOCAL'
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
  "visiblePlayUrl": "webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0"
}
```

验证 ZLM 实际收到了 RTMP：

```bash
curl -fsS 'http://192.168.50.254:58925/index/api/getMediaList?schema=rtmp' | jq '.data[] | {app, stream}'
```

应该看到 `app=live, stream=RC_PLUS_LOCAL-0`。

如果看不到：
- agent 侧 logcat：`adb logcat -s DjiLiveStreamController` 看是否真的调用了 `liveStreamManager.startStream`
- agent 侧 `BuildConfig.AGENT_MEDIA_HOST` 当前是 `192.168.50.254`（来自 `gradle.properties`），`AGENT_MEDIA_RTMP_PORT=1935`，`AGENT_MEDIA_STREAM_APP=live`
- ZLM 是 SSH 端口转发上来的，确认 SSH 进程没断：`lsof -nP -iTCP:1935 -sTCP:LISTEN`

## 3. 驾驶舱出画

浏览器打开 `http://192.168.50.254:8080/`，点导航 "驾驶舱"，点 "直播画面" tab。

期望：
- 主画面区域出现 RC Plus 2 当前可见光镜头实时画面
- 右下小窗保持热成像状态卡（无第二路视频）
- live tab 顶部 status pill 显示 "可见光 running / 热成像 degraded"

如果主画面卡 "播放器加载中"：
- F12 看 console 是否报 `failed-to-load-zlmrtc-client` —— ZLM HTTP 端口被防火墙挡了
- F12 Network 看 `/index/api/webrtc?app=live&stream=...` 的响应 code，401/406/-1 都意味着 ZLM 没拿到 stream

如果主画面卡 "播放失败" 且报 `zlm-offer-answer-exchange-failed`：
- ZLM 内部没有 `live/RC_PLUS_LOCAL-0` 这条 source，回到 §2 检查 RTMP 推流
- 或者 ZLM 的 SDP 候选地址带的是错的 IP（`externIP=192.168.50.254` 要对上当前 wifi）

如果主画面卡 "播放失败" 且报 `zlm-connection-failed`：
- 浏览器和 ZLM 之间 WebRTC 候选地址不通；本机内网应该不会，跨网时要确认 `ZLM_WEBRTC_TCP_PORT=8000` / `ZLM_WEBRTC_UDP_PORT=10000` 已经在防火墙放行

## 4. 关键文件 / 入口（按出问题概率排序）

- agent RTMP 推流：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiLiveStreamController.kt:23-44`
- agent 可见光绑定：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/DjiMsdkStreamBinder.kt:25-32`
- agent 启动双流入口：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/RealMsdkStreamProvider.kt:15-41`
- backend 兜底 URL：`backend/sample/src/main/java/com/dji/sample/manage/service/impl/DualStreamServiceImpl.java`（`buildPlaybackUrl`）
- backend 直播配置：`backend/sample/src/main/resources/application.yml`（`livestream.playback.webrtc-host` / `webrtc-port`）
- 驾驶舱播放器：`frontend/src/pages/page-web/projects/leadership-cockpit.vue`（`mountPlayerInstance` / `syncLivePlayers`，约 509-630 行）
- ZLM 配置：`deployment/zlmediakit/.env`（`ZLM_PUBLIC_HOST=192.168.50.254`）+ `deployment/zlmediakit/config/config.ini`（`externIP=192.168.50.254`）

## 5. 出问题不要做的事

- **不要切 IP**：当前所有配置都已经统一在 192.168.50.254，乱改会把链路再断一遍
- **不要在驾驶舱用 jswebrtc.Player**：已经接的是 `ZLMRTCClient.Endpoint`（WebRTC，不是 RTMP-over-WS），HANDOFF 旧描述是错的
- **不要在 agent 端等 thermal 第二路真出画**：当前实现就是 `degraded`，要做真双路看 `MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md`
