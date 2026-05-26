# CLAUDE.md

本文档用于记录当前仓库的工程范围、运行方式和后续维护注意事项，供 Claude Code、Codex 或其他开发者接手时参考。

## 项目范围

当前 GitHub 仓库 `uavFire_cloudApi` 只维护两个工程：

- `frontend/`：DJI Cloud API Web 前端，基于 Vue 3、Vite、Ant Design Vue。
- `backend/`：DJI Cloud API 后端示例服务，基于 Java 11、Spring Boot 2.7.12、Maven 多模块。

当前工作目录中的其他工程，例如 `cloud_server/`、`AI/uav-fire-sim/`、`AI/uav-fire-sim-frontend/`、`AI/android-gateway-msdkv5/`、Python 原型后端等，不属于本次 GitHub 仓库维护范围，不在本记录中展开。

## 工程结构

```text
uavFire_cloudApi/
  frontend/      DJI Cloud API Web 前端
  backend/       DJI Cloud API 后端服务
  RUNBOOK.md     正式运行说明
  WORK_RECORD.md 前后端工作记录
  work-records/  AI 工具工作记录
```

## 前端工程

路径：`frontend/`

技术栈：

- Vue 3
- Vite 2
- TypeScript
- Ant Design Vue 2
- MQTT.js
- Agora RTC SDK
- AMap JSAPI

常用命令：

```bash
npm install
npm run serve
npm run build
npm run build:test
npm run lint
```

默认开发端口：`8080`。

前端主要配置：

- `env/.env`：后端 API 网关地址。
- `src/api/http/config.ts`：地图 Key、运行环境配置。
- `src/pages/page-web/projects/tsa.vue`：当前无人机控制页面，包含 DRC 控制、起飞按钮和 OSD 数据展示逻辑。

## 后端工程

路径：`backend/`

技术栈：

- Java 11
- Spring Boot 2.7.12
- Maven 多模块
- Spring Integration MQTT
- MySQL
- Redis

Maven 模块：

- `cloud-sdk/`：DJI Cloud API MQTT、服务调用、事件路由等 SDK 封装。
- `sample/`：后端示例应用，提供 REST API、设备管理、控制服务等能力。

常用命令：

```bash
mvn package
mvn -pl uavfire -DskipTests compile
mvn -pl uavfire spring-boot:run
```

默认后端端口：`6789`。

主要配置文件：

- `sample/src/main/resources/application.yml`

关键依赖：

- MySQL：默认配置为 `192.168.0.30:3306/cloud_sample`
- Redis：默认配置为 `localhost:6379`
- BASIC MQTT：默认配置为 `192.168.0.30:1883`
- DRC MQTT WebSocket：默认配置为 `192.168.0.30:8083`

## DJI Cloud API 注意事项

### MQTT 通道

后端同时使用两类 MQTT 连接：

- `BASIC`：普通 MQTT，主要处理设备状态、OSD、服务调用回复等。
- `DRC`：WebSocket MQTT，主要用于云端控制、摇杆控制等实时链路。

常见 Topic：

| 用途 | Topic |
| --- | --- |
| 设备上线/离线 | `sys/product/{sn}/status` |
| OSD 遥测 | `thing/product/{sn}/osd` |
| 服务调用 | `thing/product/{sn}/services` |
| 服务回复 | `thing/product/{sn}/services_reply` |
| 事件上报 | `thing/product/{sn}/events` |
| 设备请求 | `thing/product/{sn}/requests` |

### RC Plus 2

本项目对 RC Plus 2 做过专项适配。维护相关逻辑时要注意：

- RC Plus 2 不能简单等同于普通 RC。
- 如果代码中判断网关类型，需要确认是否同时覆盖 RC Plus 2。
- 起飞前需要确认设备在线、飞控权授权成功、OSD 数据有效。

### 官方一键起飞接口

当前前端起飞按钮已按官方 `takeoff_to_point` 接口调整。

重要限制：

- 必须传入目标经纬度。
- 该接口语义是“起飞到指定坐标并悬停”，不是本地 1 米试飞摇杆模拟。
- `security_takeoff_height` 官方最小值为 20 米。
- 测试前必须确认当前 OSD 中的经纬度可信，避免使用错误坐标。

## 当前已验证事项

- 前端 `npm run build` 可完成构建，存在上游依赖或 Sass 的既有警告。
- 后端 `mvn -pl uavfire -DskipTests compile` 可完成编译，存在 Maven 配置层面的既有警告。
- 后端服务可在 `6789` 端口启动。
- 前后端源码已经整理到 GitHub 仓库，未提交 `node_modules`、`dist`、`target`、运行日志等生成内容。

## 后续建议

1. 拉取仓库后先按 `RUNBOOK.md` 配置 MySQL、Redis、MQTT 和前端环境变量。
2. 做真实飞控测试时，先观察后端 `services`、`services_reply`、`events` 和遥控器提示，不要只看前端按钮结果。
3. 如果继续排查 DRC 断开问题，优先记录点击起飞前后的 MQTT 请求、服务回复和遥控器事件。

## 调试历史索引

- **2026-04-17 `takeoff_to_point` 调试会话**：详见 `WORK_RECORD.md` 第 7 节。错误码从 210003 推进到 336003，包含完整错误分析、代码改动清单、日志诊断位置、下一步建议。对应 commit `f0ef95b`、tag `v0.2.0-takeoff-coord-offset`。
- 已知的修改 cloud-sdk 后生效流程：**必须** 先 `mvn -pl cloud-sdk clean install` 再跑 sample，否则 sample 用的是本地仓库里旧的 cloud-sdk-1.0.3.jar。
