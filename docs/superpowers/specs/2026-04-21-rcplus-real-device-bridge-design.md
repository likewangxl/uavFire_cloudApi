# RCPlus Real Device Bridge Design

**Date:** 2026-04-21

## Goal

让 `rcplus-msdk-agent` 从当前 mock/占位状态升级到“真机前置接入”阶段：能够完成 DJI MSDK 初始化、读取真实连接态与能力信息，并提供可见光/红外双路流的真实绑定入口，但本轮不包含推流、前端播放或媒体分发。

## Scope

本轮只覆盖以下内容：

- `DjiSdkGatewayImpl` 真实化：初始化、连接态、能力读取
- `DjiDeviceSession` 保持为设备状态聚合入口
- `RealMsdkStreamProvider` 真实化为“双路流绑定入口”
- `DualStreamSessionManager` 调用真实 stream provider 时能区分成功/失败
- 增加最小测试，保证无真机环境下仍可验证状态转换逻辑

本轮明确不做：

- WebRTC / Agora / SRS 推流
- 前端双画面播放
- 持续后台轮询 loop
- AI 侧真实视频消费

## Current State

当前 `rcplus-msdk-agent` 的关键缺口如下：

- `DjiSdkGatewayImpl.initialize()` 只是占位返回
- `isAircraftConnected()` 固定返回 `false`
- `loadCapability()` 固定返回不可用 capability
- `RealMsdkStreamProvider.start/stop()` 为空实现
- `DualStreamSessionManager` 虽然已有 start/stop 命令执行路径，但对“真实双流绑定成功/失败”的判断仍依赖 provider 的占位实现

## Approach Options

### Option A: Minimal Real Device Bridge

把 DJI SDK 集成点限定在 `sdk/` 与 `stream/` 两层：

- `DjiSdkGatewayImpl` 负责真实 SDK 初始化、设备连接态和 capability 读取
- `RealMsdkStreamProvider` 负责双路流绑定和解绑
- 上层状态机不感知 DJI 细节，只消费成功/失败与状态枚举

优点：

- 改动边界清晰
- 最适合当前阶段
- 失败时更容易定位是“SDK 未就绪”还是“流绑定失败”

缺点：

- 没有持续上报 loop
- 真机绑定成功后，还不能直接拿去播放

### Option B: Real Device Bridge + Runtime Loop

在 Option A 的基础上，把 heartbeat/status/capability 的持续上报和命令轮询后台循环也一起接入。

优点：

- 更接近真实运行态

缺点：

- 一次性引入太多不确定性
- 难以分离设备问题与调度问题

### Option C: Standalone Diagnostics Mode

先做一个只用于本地诊断的设备接入路径，不接后端命令和现有状态机。

优点：

- 排障效率高

缺点：

- 会产生一条额外过渡路径，后续仍要并回主线

## Recommended Approach

选择 Option A。

这是当前最稳妥的边界：先把“设备是否能初始化、是否已连接、是否支持 visible/thermal、是否能完成双路绑定”这些基础真相拿到，再决定是否继续扩到持续上报或媒体分发。

## Design

### 1. `DjiSdkGatewayImpl`

职责：

- 初始化 DJI MSDK
- 提供飞机连接态判断
- 读取当前机型对 visible / thermal 的支持状态

接口保持不变：

- `initialize(): Boolean`
- `isAircraftConnected(): Boolean`
- `loadCapability(): CameraCapability`

实现策略：

- 若 SDK 初始化失败，返回 `false`
- 若 SDK 初始化成功但未连机，`isAircraftConnected()` 返回 `false`
- 若连机成功，则根据当前 payload / camera source 读取 capability
- 如果热成像 capability 无法明确读取，保守降级为 `false`，但不能抛异常打断会话

### 2. `DjiDeviceSession`

职责不变，继续作为设备状态聚合入口：

- SDK 初始化失败 -> `AgentConnectionState.ERROR`
- SDK 初始化成功但未连机 -> `AgentConnectionState.SDK_READY`
- 飞机已连接且 capability 已读到 -> `AgentConnectionState.CAPABILITY_READY`

这里不引入 DJI 细节，避免状态机与 SDK API 直接耦合。

### 3. `RealMsdkStreamProvider`

职责：

- 建立 visible / thermal 两路真实流绑定入口
- 管理“是否已开始绑定”的本地状态
- 在 `start(droneSn)` 中尝试绑定两路 source
- 在 `stop()` 中释放绑定

本轮不要求输出媒体数据，但必须提供足够状态让上层判断：

- 双路绑定成功 -> `start()` 正常返回
- 任一路关键绑定失败 -> 抛出异常，让 `DualStreamSessionManager` 落入 `FAILED`

设计约束：

- provider 只负责绑定/解绑，不做推流、不做转码、不做播放
- visible/thermal 的真实句柄如果 SDK 当前版本拿不到，就明确失败，不做伪成功

### 4. `DualStreamSessionManager`

保持现有职责：

- `start()` 调用 stream provider
- `stop()` 调用 stream provider
- `executeCommand()` 负责把 `start/stop` 转成标准 ack 语义

新增要求：

- 当 `RealMsdkStreamProvider.start()` 失败时，状态必须落到 `FAILED`
- 不在 manager 内部直接调用 DJI SDK

### 5. Testing Strategy

单测继续以 fake / mock gateway 和 fake stream provider 为主，验证：

- `DjiDeviceSession` 在各种初始化/连接/capability 条件下的状态输出
- `DualStreamSessionManager` 在真实 provider 风格的成功/失败场景下的状态变化
- `RealMsdkStreamProvider` 如果难以在 JVM 单测里直连 SDK，则至少抽出可测试的绑定状态逻辑

本轮不把“真机在线验证”写成自动化测试，而是保留为手工验证步骤。

## Manual Verification

手工验证目标：

1. App 启动后可完成 SDK 初始化
2. 飞机未连接时，设备状态为 `SDK_READY`
3. 飞机连接后，设备状态进入 `CAPABILITY_READY`
4. 下发 `start` 命令后，真实 provider 尝试绑定 visible/thermal
5. 双路绑定成功时，session 进入 `RUNNING`
6. 任一路绑定失败时，session 进入 `FAILED`

## Risks

### Risk 1: 当前仓库尚未真正接入 DJI MSDK 依赖

如果本地 Android 工程还没有可调用的 DJI MSDK API，本轮需要先把 gateway 设计成可挂接真实实现的适配层，必要时先接“编译期可用的占位适配器”。

### Risk 2: MSDK 对双路视频能力的 API 可能因机型或版本受限

M4T 是否能在当前 SDK 版本里同时暴露 visible + thermal 两路，需要以实际 API 为准。若 SDK 只允许单路选择，本轮结论应明确记录，而不是伪装成“双路已可用”。

### Risk 3: 真机流绑定与后续推流链路不是一回事

本轮即使绑定成功，也只能说明“设备侧入口已打通”，不代表前端已经可以双画面播放。

## Success Criteria

满足以下条件即视为本轮完成：

- `DjiSdkGatewayImpl` 不再是固定返回值占位
- `DjiDeviceSession` 能基于真实 gateway 返回真实状态
- `RealMsdkStreamProvider` 不再是空实现，而是具备真实双路绑定入口
- `DualStreamSessionManager` 能正确反映双路绑定成功/失败
- 单测仍通过
- 交接文档明确记录：已完成真机前置接入，未完成媒体推流/前端播放
