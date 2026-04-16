# 前后端工作记录

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
