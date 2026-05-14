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

## 双流 PoC 子工程

- `rcplus-msdk-agent/`：RC Plus 2 Android / DJI MSDK v5 执行层工程骨架。
- `ai-service/`：双流火情识别 AI 服务工程骨架。

当前阶段仅固化工程边界、依赖声明、后续接口与本地验证入口，不代表已完成真机双流联调、正式推流链路或商用级火情识别能力。
