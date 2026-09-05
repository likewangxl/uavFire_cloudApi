# uavFire Cloud API

本仓库最初来自 DJI Cloud API 前后端工程，现在已经扩展为巡检火情识别与 FC100 重载灭火协同系统，包含 M4T 路径及 M300/M350 适配代码。

源码主线是 `main`，跟踪 `origin/main`；2026-09-05 已合入原 M300 分支及小程序基础成果。当前开发目录仍为 `.worktrees/m300-model-adaptation`，检出分支已经是 `main`。现场运行版本以实际部署产物为准，不得把源码合并或未提交工作区改动宣称为已上线能力。

同日分支整理已完成：本地和 GitHub 均只保留 `main` 分支，旧火情基线与 NCNN 替代方案已保存为远端归档标签。根目录保留在 `b47042b` 的 detached HEAD 状态，原文件未清理；新开发使用上述 main 工作目录。

当前代码仍包含北京时间 `2026-10-01 00:00` 到期的试用限制，前端还有 24 项已记录的测试失败。M4T 的新火情监测命令需要配套新版 Agent；详见 [主线整合、分支清理与 M4T 兼容性评估](docs/main-integration-and-m4t-compatibility-2026-09-05.md)。

当前项目状态与运行入口：

- `docs/main-integration-and-m4t-compatibility-2026-09-05.md`（当前源码与兼容性边界）
- `docs/CURRENT_PROJECT_STATUS_2026-08-05.md`
- `RUNBOOK.md`
- `HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md`
- `docs/MSDK_MIGRATION_PLAN.md`
- `docs/CURRENT_PROJECT_STATUS_2026-05-21.md`（历史状态）

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

- 生产火情识别主链路是可见光 YOLO，不再依赖红外测温确认。
- 可见光候选火情可触发悬停、ROI 对准和 DJI 激光测距，成功后更新同一事件为 `PRECISE`。
- FC100 任务以 20 态状态机为唯一事实源，派发前经过 15 项合规与安全预检。
- 释放默认为 `MANUAL_CONFIRM + OFFICIAL_HOOK_MANUAL`；Delivery Sync 远程开钩未获书面确认前不可作为生产路径。
- UOM/空域数据目前只作参考层和审批证据留存，不等于已完成正式自动审批接入。
- Pilot 2 PIP 复合推流不进入 Cloud SDK livestream，旧双流方案只作历史研究与降级参考。

当前配置基准是 `172.20.10.7`，如果切换 WiFi 或网卡，需要同步更新 backend、frontend、agent、ZLM 配置。
