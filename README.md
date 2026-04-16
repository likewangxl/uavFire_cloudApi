# uavFire Cloud API

本仓库只包含智能集群大载重无人机灭火系统中的 DJI Cloud API 前后端工程。

## 目录

- `frontend/`：DJI Cloud API Web 前端，Vue 3 + Vite。
- `backend/`：DJI Cloud API 后端服务，Java 11 + Spring Boot + Maven。
- `RUNBOOK.md`：正式运行说明。
- `WORK_RECORD.md`：前后端工作记录。
- `work-records/`：AI 工具工作记录。

## 快速开始

详细启动步骤见 `RUNBOOK.md`。

核心依赖：

- Node.js 和 npm
- Java 11
- Maven
- MySQL
- Redis
- MQTT Broker，包含 BASIC MQTT 和 DRC WebSocket MQTT

构建产物、依赖目录、IDE 元数据和运行日志不会提交到版本库，例如 `node_modules`、`dist`、`target`、`logs`、`*.log`。
