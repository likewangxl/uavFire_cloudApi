# 项目运行说明

本文档只覆盖本仓库中的两个工程：`frontend/` 和 `backend/`。

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

默认服务端口：

| 服务 | 默认端口 |
| --- | --- |
| 前端开发服务 | `8080` |
| 后端服务 | `6789` |
| BASIC MQTT | `1883` |
| DRC MQTT WebSocket | `8083` |
| Redis | `6379` |

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
      url: jdbc:mysql://192.168.50.83:3306/cloud_sample?useSSL=false&allowPublicKeyRetrieval=true
      username: g_byzt
      password: g_byzt
  redis:
    host: localhost
    port: 6379

mqtt:
  BASIC:
    host: 192.168.50.10
    port: 1883
  DRC:
    protocol: WS
    host: 192.168.50.10
    port: 8083

pilot2:
  web-entry: http://192.168.50.10:8080/pilot-login
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
  --mqtt.BASIC.host=192.168.50.10 \
  --mqtt.BASIC.port=1883 \
  --mqtt.DRC.host=192.168.50.10 \
  --mqtt.DRC.port=8083
```

Windows PowerShell 中可以写成一行：

```powershell
mvn -pl uavfire spring-boot:run --mqtt.BASIC.host=192.168.50.10 --mqtt.BASIC.port=1883 --mqtt.DRC.host=192.168.50.10 --mqtt.DRC.port=8083
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

## 6. RC Plus 2 DRC 测试步骤

1. 启动 MQTT Broker，确保 BASIC MQTT 和 DRC WebSocket MQTT 都可连接。
2. 启动后端，确认日志中没有 MQTT 连接失败。
3. 启动前端，登录并进入设备控制页面。
4. 确认遥控器和飞机在线。
5. 确认 OSD 数据持续刷新，尤其是经纬度、飞行状态、电量等字段。
6. 执行云端操控授权。
7. 点击起飞前，确认当前经纬度可信。
8. 点击起飞后，同时观察后端日志、MQTT `services_reply`、MQTT `events` 和遥控器提示。

## 7. 当前起飞接口说明

前端起飞按钮当前调用官方 `takeoff_to_point` 接口。

注意：

- 该接口必须传目标经纬度。
- 当前实现使用 OSD 中的当前经纬度作为目标点。
- `security_takeoff_height` 按官方限制设置为不低于 20 米。
- 该接口不是 1 米低空本地试飞接口。

如果现场只想做 1 米左右的起落测试，需要确认 DJI 官方是否提供适用于 RC Plus 2 的低空起飞控制方式，不能把 `takeoff_to_point` 强行当作 1 米摇杆起飞。

## 8. 常见排查点

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
