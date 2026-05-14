# RCPlus Visible-First Degradation Design

**Date:** 2026-04-22

## Goal

把 `rcplus-msdk-agent` 的双流启动行为从“thermal 失败即整体 failed”调整为“visible 优先启动、thermal 显式降级”，以匹配 RC Plus 2 + M4T 真机验证结果。

## Verified Device Facts

- `SDKManager` 初始化、注册和连机识别已在 RC Plus 2 上恢复正常
- 真机 capability 已读到：
  - `可见光: true`
  - `红外: true`
- 双流启动时的真实限制是：
  - `msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding`

## Design

### 1. StreamProvider contract

`StreamProvider.start()` 不再只靠抛异常表达结果，而是返回标准化启动结果：

- visible 是否已绑定
- thermal 是否已绑定
- thermal 是否发生了可降级失败
- 降级原因是什么

visible 绑定失败仍然视为整体失败并抛异常。

### 2. RealMsdkStreamProvider behavior

启动顺序固定为：

1. 先绑定 visible
2. visible 成功后再尝试 thermal
3. thermal 失败时不撤销 visible，直接返回“visible 已运行、thermal 已降级”的结果

### 3. DualStreamSessionManager behavior

- visible 已运行时，session 进入 `RUNNING`
- `executeCommand("start")` 返回 `applied`
- thermal 降级原因保留并向上透传
- 只有 visible 启动失败时，session 才进入 `FAILED`

### 4. UI behavior

启动结果需要同时体现整体状态和分路状态：

- `双流启动结果: applied`
- `可见光: running`
- `红外: degraded`
- `原因: ...`

## Success Criteria

- 真机上点击 “启动双流” 后不再显示整体 `failed`
- 页面能明确显示 visible 已运行、thermal 已降级
- 降级原因仍然是当前真实限制字符串
