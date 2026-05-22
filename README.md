# uavFire Cloud API

本仓库最初来自 DJI Cloud API 前后端工程，现在已经扩展为 M4T + RC Plus 2 的森林消防联动系统。当前最新状态见：

- `docs/CURRENT_PROJECT_STATUS_2026-05-21.md`
- `docs/MSDK_MIGRATION_PLAN.md`
- `docs/poc/pilot2-composite-stream.md`

## 目录

- `frontend/`：DJI Cloud API Web 前端，Vue 3 + Vite。
- `backend/`：DJI Cloud API 后端服务，Java 11 + Spring Boot + Maven。
- `rcplus-msdk-agent/`：RC Plus 2 Android / DJI MSDK v5 执行层。
- `ai-service/`：FastAPI + OpenCV / YOLO 火情识别 PoC 服务。
- `deployment/zlmediakit/`：本地 ZLMediaKit 媒体中枢部署配置。
- `docs/`：当前方案、PoC、航线契约和迁移路线图。
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
- ZLMediaKit
- Python 3.11+（`ai-service`）
- Java 17 + Android SDK（`rcplus-msdk-agent`）

构建产物、依赖目录、IDE 元数据和运行日志不会提交到版本库，例如 `node_modules`、`dist`、`target`、`logs`、`*.log`。

## 当前关键结论

- Pilot 2 PIP 复合推流方案不可行：PIP 小窗不会进入 Cloud SDK livestream 输出。
- Cloud SDK livestream 在 RC 手飞下单路可见光已验证可用，但双路未验证。
- M4T + MSDK v5 当前不暴露 visible + thermal 两路独立 raw stream；热成像需要走降级路线。
- 第一阶段迁移方向是 MSDK Agent 数据面，Cloud SDK livestream 暂保留为 fallback。

当前配置基准是 `192.168.2.34`，如果切换 WiFi 或网卡，需要同步更新 backend、frontend、agent、ZLM 配置。
