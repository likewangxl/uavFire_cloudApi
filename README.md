# UAVFire

UAVFire 是基于 DJI Cloud API、RC Plus 2 和 M4T 的森林消防巡检系统。

## 生产架构

生产运行只包含四个组件：

1. `backend/`：事件持久化、告警、任务管理和人工控制；
2. `frontend/`：驾驶舱、事件和任务页面；
3. `deployment/zlmediakit/`：向操作员提供 RTMP/WebRTC 直播；
4. `rcplus-msdk-agent/`：唯一实时火情检测与飞行定位执行端。

Agent 从 MSDK 可见光 RGBA 帧运行单一 NCNN YOLO 模型，在本地完成
`fire/smoke` 确认、暂停、悬停、ROI 重捕获、激光定位、可靠 Outbox 和安全恢复。
ZLMediaKit 只用于画面显示；直播中断不应改变本地检测与飞行闭环。

`ai-service/` 仅保留为离线导出、基准和验证工具，不属于生产启动、健康检查、
网络隧道或降级检测路径。

## 目录

- `backend/`：Java 11、Spring Boot、Maven；
- `frontend/`：Vue 3、Vite；
- `rcplus-msdk-agent/`：Java 17、Android SDK 34、DJI MSDK v5；
- `deployment/zlmediakit/`：显示链路媒体服务；
- `ai-service/`：离线工具，不进入生产运行；
- `docs/runbooks/agent-visible-fire-closed-loop-acceptance.md`：上线验收、证据和回滚；
- `RUNBOOK.md`：生产启动与排障。

## 当前发布边界

- 正式 APK 仅封装 NCNN 和 `visible-fire-wechat-best2-20260728` 的 960 输入产物；
- NCNN 自动化结果只是临时开发证据，不替代 RC Plus visible-960 三引擎验收；
- RC Plus 当前不可用，实机性能、30 分钟 soak、实激光、无桨台架和受控飞行仍阻塞；
- `VISIBLE_FIRE_DETECTION_ENABLED=false`，安全门失败时保持关闭；
- 未通过 fire 和 smoke 受控飞行前不得默认启用检测。

启动步骤见 [RUNBOOK.md](RUNBOOK.md)，验收状态见
[Agent 可见光闭环验收手册](docs/runbooks/agent-visible-fire-closed-loop-acceptance.md)。
