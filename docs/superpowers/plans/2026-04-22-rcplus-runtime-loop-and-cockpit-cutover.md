# RCPlus Runtime Loop And Cockpit Cutover Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `rcplus-msdk-agent` 具备稳定的应用生命周期 loop，并为驾驶舱从旧 Agora 直播链切换到新 rcplus 真机链路创造可执行前提。

**Architecture:** 先完成 Android agent 的持续心跳、状态、capability、命令轮询闭环，再定义前端/后端消费新链路的播放入口。驾驶舱切流必须在新链路稳定输出后进行，避免先删 Agora 导致画面中断。

**Tech Stack:** Kotlin, Android, Retrofit, coroutines, Vue 3, existing backend sample module.

---

### Task 1: 把 agent 生命周期 loop 接进 Android App

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/App.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/CommandPollingCoordinator.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/session/DualStreamSessionManager.kt`
- Add/Modify tests under `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/`

- [x] 定义一个前台存活期间的 loop owner，负责 heartbeat、status、capability 和 command poll。
- [x] 明确 loop 的启动条件、停止条件和异常恢复策略。
- [ ] 让 backend 能持续看到 agent 在线状态和 visible/thermal 当前运行态。
- [x] 让 backend 至少可稳定接收 agent heartbeat/status/capability/command poll，不再被统一鉴权拦截。
- [x] 为 loop 的启动/停止和错误恢复补 focused tests。

执行进度备注：

- 已新增 `AgentRuntimeLoop`、`AppServices`、`AgentBackendApiFactory`
- 已通过 `ProcessLifecycleOwner` 把 loop 接进 App 前后台生命周期
- 已完成本机 unit test + assemble 验证
- 已完成 RC Plus 真机回归，确认设备可真实连到 backend
- 已修复 backend 对 `/dual-stream/agents/**` 的误鉴权，agent 接口现返回 `200`
- 当前仍缺“前端可查询的运行态表达”，所以 Task 1 只算 agent->backend 联通完成，未完成驾驶舱消费面

### Task 2: 把当前真机运行态纳入 backend 可观测面

**Files:**
- Modify backend dual-stream DTO / persistence files as needed
- Modify corresponding tests under `backend/uavfire/src/test/java/...`

- [x] 明确 backend 当前是否足够承载 `visible running / thermal degraded / reason` 这组状态。
- [x] 如果不够，最小扩展 DTO 和存储结构，不要引入无关重构。
- [x] 保证前端可查询到当前 agent 的真实运行态。

### Task 3: 定义驾驶舱切流前置条件

**Files:**
- Modify/add docs as needed
- Inspect `frontend/src/components/WorkspaceLivestreamPanel.vue`
- Inspect `frontend/src/pages/page-web/projects/leadership-cockpit.vue`

- [ ] 明确驾驶舱要消费的新链路输入形式：
  - 是直接视频播放地址
  - 还是 backend 聚合后的播放配置
- [x] 先补最小播放契约：`playbackStatus` / `visiblePlayUrl` / `thermalPlayUrl`
- [ ] 部署 ZLMediaKit 媒体中枢，并给出可被 RC Plus 与浏览器同时访问的地址
- [ ] 明确 visible 主画面与 thermal 降级状态在驾驶舱的呈现方式。
- [ ] 在新链路未稳定输出前，不删除 Agora 老代码。

执行进度备注：

- 已把 `playbackStatus` / `visiblePlayUrl` / `thermalPlayUrl` 补进 backend DTO、Android status 上报和前端查询类型
- 当前 Android agent 显式上报 `awaiting-media-url`
- 这一步完成的是“播放地址契约”，不是“真实媒体输出”
- 已新增 `deployment/zlmediakit/` 部署脚手架，但当前机器未安装 Docker，尚未实际起服

### Task 4: 驾驶舱切流实施

**Files:**
- Modify: `frontend/src/components/WorkspaceLivestreamPanel.vue`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Modify: `frontend/src/api/manage.ts`
- Modify/remove old Agora wiring in backend only after new path is live

- [x] 用新链路替换当前驾驶舱 live tab 对旧 Agora 状态面的依赖。
- [x] 驾驶舱 live tab 已明确展示“是否有 Web 播放地址”
- [ ] 驾驶舱第一版至少要展示：
  - visible 主画面
  - thermal degraded 状态
  - 当前设备/质量/模式摘要
- [ ] 切流完成并验证后，再清理 Agora 旧入口。

执行进度备注：

- `leadership-cockpit.vue` 当前 live tab 已改为读取 `dual-stream/groups/{droneSn}`
- 这次切掉的是“状态面”，不是“真实视频面”
- 原因：当前仓库仍没有 rcplus 新链路提供给 Web 的直接播放地址
- 当前已补好 `visiblePlayUrl` / `thermalPlayUrl` 契约，下一步不需要再改 DTO，可以直接接真实媒体输出
- 当前真实阻塞项是：ZLMediaKit 还未在可用主机上部署完成
- 因此 Agora 旧代码尚未删除，只是不再作为驾驶舱 live tab 的当前实现

### Task 5: 验证与交接

**Files:**
- Update `WORK_RECORD.md`
- Update handoff doc

- [x] 跑 Android 定向 unit tests。
- [ ] 做一次 RC Plus 真机回归。
- [x] 验证驾驶舱状态面已不再依赖 Agora。
- [ ] 验证驾驶舱真实视频已切到新链路。
- [ ] 更新文档，明确 Agora 是否已完全下线。

### Task 6: `ai-service` 占位版升级为真实火情识别

**Files:**
- Modify: `ai-service/app/video/source.py`
- Modify: `ai-service/app/services/task_runner.py`
- Modify: `ai-service/app/inference/visible/detector.py`
- Modify: `ai-service/app/inference/thermal/analyzer.py`
- Modify: `ai-service/app/fusion/service.py`
- Modify tests under `ai-service/tests/`

- [ ] 在 `video/source.py` 落真实视频输入层，至少支持 `RTSP` 或录制文件。
- [ ] 把 `task_runner.py` 从 `run_once()` 升级为持续消费循环。
- [ ] 替换 visible 固定分数逻辑，接入真实火焰/烟雾检测。
- [ ] 替换 thermal 固定分数逻辑，先落红外伪彩图热点分析。
- [ ] 在 `fusion/service.py` 增加时间对齐、近邻匹配与降级策略。
- [ ] 扩展 detection event，补证据与风险状态字段。

执行进度备注：

- 当前 `ai-service` 仍是“服务骨架 + 占位识别分数器”
- 当前能验证的是任务/event 闭环，不能把结果表述成“真实火情识别已可用”
- 下一阶段应优先做：真实视频输入 -> visible 真检测 -> 持续 runner

补充待办：

- [ ] 对外统一说明：当前 `ai-service` 不是实时火情识别服务，只能验证任务/event/回传闭环
- [ ] 从 `ai-service/app/video/source.py` 开始落真实视频输入层，至少支持 `RTSP` 或录制文件
- [ ] 完成 visible 真检测后，再把 `task_runner.py` 升级成持续消费循环
- [ ] 后续按 `thermal -> fusion -> 事件与证据增强` 顺序推进，不反向宣称 AI 已可用
