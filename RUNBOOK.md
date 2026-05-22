# 项目运行说明

本文档覆盖当前主链路的本地运行方式：`backend/`、`frontend/`、`deployment/zlmediakit/`、`ai-service/` 和 `rcplus-msdk-agent/`。

最新架构和状态先看 `docs/CURRENT_PROJECT_STATUS_2026-05-21.md`。如果本文与该状态文档冲突，以状态文档和当前配置文件为准。

当前工作区的局域网基准 IP 是：

```text
192.168.0.30
```

切换网络后需要同步更新：

- `backend/uavfire/src/main/resources/application.yml`
- `frontend/env/.env`
- `frontend/src/api/http/config.ts`
- `rcplus-msdk-agent/gradle.properties`
- `deployment/zlmediakit/.env`
- `deployment/zlmediakit/config/config.ini`

## 1. 环境要求

前端：

- Node.js
- npm

后端：

- Java 11
- Maven
- MySQL
- Redis
- MQTT Broker

Android agent：

- Java 17
- Android commandline tools / SDK 34
- RC Plus 2 + M4T 真机

AI 服务：

- Python 3.11+
- OpenCV 运行环境
- 可选 YOLO 权重

媒体服务：

- Docker / Docker Compose 或现有 ZLMediaKit 实例

默认服务端口：

| 服务 | 默认端口 |
| --- | --- |
| 前端开发服务 | `8080` |
| 后端服务 | `6789` |
| BASIC MQTT | `1883` |
| DRC MQTT WebSocket | `8083` |
| Redis | `6379` |
| ZLMediaKit RTMP | `1935` |
| ZLMediaKit HTTP/WebRTC API | `58925` |
| ZLMediaKit RTSP | `8554` |
| ai-service | `9000` |

## 2. 后端配置

后端配置文件：

```text
backend/uavfire/src/main/resources/application.yml
```

需要重点确认以下配置：

```yaml
server:
  port: 6789

spring:
  datasource:
    druid:
      url: jdbc:mysql://127.0.0.1:3306/cloud_sample?useSSL=false&allowPublicKeyRetrieval=true
      username: g_byzt
      password: g_byzt
  redis:
    host: localhost
    port: 6379

mqtt:
  BASIC:
    host: 192.168.0.30
    port: 1883
  DRC:
    protocol: WS
    host: 192.168.0.30
    port: 8083

pilot2:
  web-entry: http://192.168.0.30:8080/pilot-login

livestream:
  playback:
    webrtc-host: 192.168.0.30
    webrtc-port: 58925

ai-service:
  base-url: http://127.0.0.1:9000
  zlm-rtsp-host: 192.168.0.30
  zlm-rtsp-port: 8554
```

部署到其他机器时，需要把 MySQL、Redis、MQTT、Pilot Web 地址改成实际地址。

## 3. 启动后端

进入后端目录：

```bash
cd backend
```

编译：

```bash
mvn -pl uavfire -DskipTests compile
```

启动：

```bash
mvn -pl uavfire spring-boot:run
```

如果要临时覆盖 MQTT 地址，可以在启动命令中增加参数：

```bash
mvn -pl uavfire spring-boot:run \
  --mqtt.BASIC.host=192.168.0.30 \
  --mqtt.BASIC.port=1883 \
  --mqtt.DRC.host=192.168.0.30 \
  --mqtt.DRC.port=8083
```

Windows PowerShell 中可以写成一行：

```powershell
mvn -pl uavfire spring-boot:run --mqtt.BASIC.host=192.168.0.30 --mqtt.BASIC.port=1883 --mqtt.DRC.host=192.168.0.30 --mqtt.DRC.port=8083
```

启动成功后，后端监听：

```text
http://localhost:6789
```

## 4. 启动前端

进入前端目录：

```bash
cd frontend
```

安装依赖：

```bash
npm install
```

启动开发服务：

```bash
npm run serve
```

构建生产包：

```bash
npm run build
```

前端默认访问：

```text
http://localhost:8080
```

## 5. 前端连接后端

前端环境配置在：

```text
frontend/env/.env
```

需要确认后端 API 地址指向实际后端服务，例如：

```text
VITE_APP_APIGATEWAY_BACKEND_HOST=http://localhost:6789
```

如果前端给 Pilot 或遥控器访问，地址不能写成只对本机有效的 `localhost`，应改为遥控器可以访问到的局域网 IP。

## 6. 启动 ZLMediaKit

```bash
cd deployment/zlmediakit
docker compose up -d
```

检查：

```bash
curl -s "http://localhost:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy"
```

当前 agent 推流命名为：

```text
rtmp://192.168.2.34:1935/live/{droneSn}-0
```

对应 cockpit WebRTC 播放地址由 backend 拼成：

```text
webrtc://192.168.2.34:58925/live/{droneSn}-0
```

## 7. 启动 ai-service

```bash
cd ai-service
./scripts/run-dev.sh
curl http://127.0.0.1:9000/healthz
```

推荐 `.env`：

```dotenv
AI_SERVICE_USE_CONTINUOUS_RUNNER=true
AI_SERVICE_BACKEND_BASE_URL=http://127.0.0.1:6789
OPENCV_FFMPEG_CAPTURE_OPTIONS="rtsp_transport;tcp"
```

## 8. 构建 / 安装 RC Plus Agent

```bash
cd rcplus-msdk-agent
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/usr/local/share/android-commandlinetools \
ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools \
./gradlew :app:installDebug
```

启动：

```bash
adb shell am start -n com.yinxin.uavfir/.MainActivity
```

当前 agent 配置在 `rcplus-msdk-agent/gradle.properties`，重点确认：

- `agentBackendBaseUrl`
- `agentMediaHost`
- `agentMqttBrokerUrl`
- `agentAircraftSn`
- `agentGatewaySn`

## 9. RC Plus 2 DRC 测试步骤

1. 启动 MQTT Broker，确保 BASIC MQTT 和 DRC WebSocket MQTT 都可连接。
2. 启动后端，确认日志中没有 MQTT 连接失败。
3. 启动前端，登录并进入设备控制页面。
4. 确认遥控器和飞机在线。
5. 确认 OSD 数据持续刷新，尤其是经纬度、飞行状态、电量等字段。
6. 执行云端操控授权。
7. 点击起飞前，确认当前经纬度可信。
8. 点击起飞后，同时观察后端日志、MQTT `services_reply`、MQTT `events` 和遥控器提示。

## 10. 当前起飞接口说明

前端起飞按钮当前调用官方 `takeoff_to_point` 接口。

注意：

- 该接口必须传目标经纬度。
- 当前实现使用 OSD 中的当前经纬度作为目标点。
- `security_takeoff_height` 按官方限制设置为不低于 20 米。
- 该接口不是 1 米低空本地试飞接口。

如果现场只想做 1 米左右的起落测试，需要确认 DJI 官方是否提供适用于 RC Plus 2 的低空起飞控制方式，不能把 `takeoff_to_point` 强行当作 1 米摇杆起飞。

## 11. 常见排查点

后端启动失败：

- 检查 Java 是否为 11。
- 检查 MySQL 地址、账号、库名是否正确。
- 检查 Redis 是否启动。
- 检查 MQTT Broker 是否可访问。

前端无法访问后端：

- 检查 `frontend/env/.env` 中的后端地址。
- 检查浏览器控制台网络请求。
- 检查后端端口 `6789` 是否启动。

云端控制断开：

- 检查 DRC MQTT WebSocket 是否连接正常。
- 检查飞控权是否授权成功。
- 检查起飞服务调用是否收到 `services_reply`。
- 检查遥控器侧是否返回了具体错误码或断开原因。
- 检查起飞前 OSD 经纬度是否为有效值。

驾驶舱直播无画面：

- 先查 ZLM 是否有 `live/{droneSn}-0`：
  `curl -s "http://192.168.2.34:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy"`
- 再查 backend dual-stream group 是否有 `visiblePlayUrl`。
- 浏览器 Network 看 `/index/api/webrtc?app=live&stream=...&type=play` 是否成功。
- 不要再用 `jswebrtc.Player` 排查；当前 cockpit 使用 `ZLMRTCClient.Endpoint`。
