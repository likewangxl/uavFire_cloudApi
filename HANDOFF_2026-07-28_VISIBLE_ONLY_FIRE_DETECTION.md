# 交接：Agent 纯可见光火情闭环

更新日期：2026-08-01

原 2026-07-28 交接描述的是已经退出生产的远端检测和后端逐步定位链路。本文件现为
当前生产交接入口；历史分析保留在 Git 历史及 `docs/superpowers`，不得作为启动手册。

## 当前决策

```text
MSDK 可见光 RGBA
  → Agent 单一 NCNN visible-960 fire/smoke
  → 两帧本地确认
  → 本地事务写会话 + sequence 1 Outbox
  → 并行立即上报与暂停任务
  → 稳定悬停、ROI 重捕获、tap zoom、激光三样本
  → PRECISE 或 DEGRADED_OSD 可靠保存
  → 安全复核后恢复原任务
```

- Agent 是唯一实时检测与飞行定位编排者；
- backend 保留事件、历史、通知、空间合并、任务和人工控制；
- frontend 对全部状态进行中文转换，未知值显示“未知状态”；
- ZLMediaKit 只提供操作员直播，不是检测依赖；
- 离线模型工具不进入生产运行，也不是 fallback detector。

## 已完成的代码边界

- 正式 APK 只有 NCNN runtime 和一套 960 param/bin，manifest 固定版本与哈希；
- detector 默认关闭，模型、帧、存储、后端持久化和飞行安全门 fail-closed；
- fire 与 smoke 均进入悬停、ROI、激光和恢复流程；
- 初报不等待图片或定位，Outbox 按 `(eventId, sequence)` 保序重试；
- backend 使用 Agent JWT，并要求 token 与 payload `droneSn` 一致；
- `droneSn` 与真实 active mission/task ID 独立持久化；任务切换时暂停提交失败关闭；
- 激光失败只报告 aircraft OSD，不填充火点坐标；
- 后端不参与正常 ROI、悬停、激光逐步调度；
- 通知在业务事务提交后唤醒发送，重复/迟到报告不重复通知。

## 生产启动

只启动：

1. backend；
2. frontend；
3. ZLMediaKit；
4. RC Plus Agent。

命令、preflight 和故障处理见 [RUNBOOK.md](RUNBOOK.md)。不要从旧提交复制远端检测、
健康探测、隧道或任务重建命令。

## 当前验收状态

自动化结果必须以
[`docs/runbooks/agent-visible-fire-closed-loop-acceptance.md`](docs/runbooks/agent-visible-fire-closed-loop-acceptance.md)
中的最新时间戳和日志为准。

RC Plus 当前不可用，因此以下均为 `BLOCKED_PENDING_DEVICE`：

- visible-960 PyTorch/TFLite/NCNN 三引擎设备验证；
- 正式 APK、UXSDK、RTMP、推理 30 分钟 soak；
- 首报 1 秒、驾驶舱提醒 1.5 秒现场时延；
- fire/smoke 实目标、实激光成功与 OSD 降级；
- 断网/重启/人工接管/恢复失败的设备链路；
- 无桨台架；
- 受控飞行。

NCNN 单元/基准结果只是临时开发证据，不能替代上述门禁。检测保持默认关闭。

## 下一位执行者

1. 从 acceptance runbook 的 preflight 开始，不跳过顺序；
2. 记录设备 APK、模型、runtime、版本和哈希；
3. fire 和 smoke 静态目标都通过后才执行 30 分钟 soak；
4. 所有设备和网络异常门禁通过后才能做无桨台架；
5. 所有此前门禁通过后才能申请受控飞行；
6. 任一失败均为 release-blocking，不得开启默认 detector。

## 回滚原则

停止并确认 Agent detector disarm，保留人工飞行控制和业务查看能力，采集证据后再决定
是否回退 APK。回滚不得启用另一正式 detector，也不得恢复 backend 自动定位编排。
