# RCPlus Visible-First Degradation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `rcplus-msdk-agent` 在 visible 成功、thermal 受 MSDK v5 限制时返回 `applied`，并把 thermal 降级状态与原因显式展示出来。

**Architecture:** 扩展 `StreamProvider.start()` 的返回结果，使 provider 自身对 visible/thermal 分路状态负责；`DualStreamSessionManager` 只根据标准化结果决定 `RUNNING/FAILED` 与命令返回；`ValidationConsoleController` 负责把分路状态渲染成真机可读文案。

**Tech Stack:** Kotlin, Android, JUnit4, kotlinx-coroutines-test.

---

### Task 1: 用失败测试锁定 visible 优先降级行为

**Files:**
- Modify: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/stream/RealMsdkStreamProviderTest.kt`
- Modify: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/session/DualStreamSessionManagerTest.kt`
- Modify: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/ui/ValidationConsoleControllerTest.kt`

- [ ] 写 `RealMsdkStreamProviderTest` 红灯：thermal 失败时 `start()` 不抛错，返回 visible=BOUND、thermal=IDLE 和原因字符串。
- [ ] 写 `DualStreamSessionManagerTest` 红灯：visible 已运行且 thermal 降级时，`executeCommand("start")` 返回 `applied`。
- [ ] 写 `ValidationConsoleControllerTest` 红灯：UI 文案显示 `双流启动结果: applied / 可见光: running / 红外: degraded / 原因: ...`。

### Task 2: 最小实现标准化启动结果

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/StreamProvider.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/MockStreamProvider.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/stream/RealMsdkStreamProvider.kt`

- [ ] 为 `StreamProvider.start()` 增加返回结果类型。
- [ ] 让 `MockStreamProvider` 返回双路都成功的默认结果。
- [ ] 让 `RealMsdkStreamProvider` 在 visible 成功、thermal 失败时返回降级结果而不是抛异常。

### Task 3: 调整 manager 和 UI 透传分路状态

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/session/DualStreamSessionManager.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/ui/ValidationConsoleController.kt`

- [ ] `DualStreamSessionManager` 根据 provider 返回结果决定 `RUNNING/FAILED`。
- [ ] `CommandExecutionResult` 扩展出分路状态字段。
- [ ] UI 将分路状态渲染为真机可读文案。

### Task 4: 回归验证

**Files:**
- None

- [ ] 运行 focused unit tests。
- [ ] 运行 `:app:assembleDebug`。
- [ ] 重装 RC Plus 并确认页面显示为 visible running、thermal degraded。
