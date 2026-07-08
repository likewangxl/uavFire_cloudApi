# 实机验证清单——悬停确认 / 抵近作业 / 激光定位（Round 1–3 改动）

**适用版本**：`feature/fire-precision-and-realtime-detection` @ `834116c`
**前置**：rcplus-msdk-agent 需重新构建 APK 并安装到 RC Plus 2；后端部署本分支并确认配置默认值。

## 0. 部署即生效的行为变化（先读这个）

| 功能 | 默认状态 | 说明 |
| --- | --- | --- |
| 悬停趋势确认（dwell） | **开（部署即生效）** | 构造默认 `enabled=true`：热点超 45°C 后会暂停航线悬停 ~8s 采样 4 次、3 次命中才上报。若要回退现行为需改 `AppServices` 注入 `enabled=false` 重打包 |
| GPS 空间去重合并 | 开（40m/30min，可配） | `FIRE_EVENT_DEDUP_ENABLED=false` 可关 |
| 自动派发抵近作业 | **关** | `FIRE_EVENT_AUTO_APPROACH_ENABLED`，验证通过前保持 false |
| 抵近作业 agent 自动触发 | **关** | processor `enabled=false`，只能手动指令触发 |
| 激光坐标落库 | 被动 | 只有抵近作业产生 HIGH 置信激光值才携带，平时零影响 |

## 1. 台架测试（飞机上电、不起飞）

### B1 悬停确认全链路（热源道具）

- **目的**：验证 dwell 采样、K/N 判定、上报门控在真机数据下工作。
- **步骤**：设备联通、开启火情监测（thermal-monitor-on），用热源（点燃的酒精块/打火机，注意安全）在红外镜头视场内保持 ≥10s；随后移开热源再短暂晃入 1–2 次（模拟瞬时热点）。
- **观察**：agent logcat TAG `ThermalDwellConfirmer` / `ThermalHotspotMonitor`：`dwell result ... confirmed=true samples=[...]`（持续热源）与 `dwell rejected`（瞬时热源）。
- **通过判据**：持续热源 → 一次上报；瞬时热源 → 拒绝日志且后端无新事件；地面无航线时 `WaypointMissionHoldControl` 打出 `dwell hold skipped: no active waypoint mission` 且流程不中断。

### B2 空间去重合并

- **目的**：验证同一位置复报合并而非新建。
- **步骤**：B1 持续热源场景下让其触发 2–3 次上报（间隔 >5s 冷却）。
- **观察**：后端 fire_event 表 / 指挥舱。
- **通过判据**：只有一条事件，`report_count` 递增、`last_seen_time` 刷新；reason 日志为 `MERGED_NEARBY`。

### B3 抵近作业失败路径与 RESET 必达（地面特性利用）

- **目的**：地面 FlyTo 必然失败/超时，正好验证失败转 RESET 的完整性——这是安全性最关键的一条。
- **步骤**：后端手动向该机下发 `fire-confirmation-mission`（params 带附近任意坐标）。
- **观察**：TAG `FireConfirmProcessor`：进入 FLY_TO → `fly-to-timeout`（≤90s）或立即失败 → RESET 各步日志。
- **通过判据**：①无论哪步失败，切回红外源、云台复位、zoom=1.0、热监测恢复全部执行（`reset step failed` 出现时有重试且不跳步）；②作业结束后 B1 流程仍正常（监测没有被作业遗留状态打瞎）；③指令响应 status=failed 且 message 说明阶段。

### B4 激光测距硬件层（DJI Pilot 2 旁证）

- **目的**：确认 M4T 激光在变焦镜头光轴出数据（MSDK 层读取在 F2 验证）。
- **步骤**：用遥控器 DJI Pilot 2 开 RNG 测距，对 ≥3m 外墙面/地物。
- **通过判据**：Pilot 2 显示距离与目标坐标；若 Pilot 2 都无读数，先排查硬件/固件再进行 F2。

### B5 自动派发链路（开关临时打开）

- **目的**：验证 dispatcher 过滤、冷却与指令到达。
- **步骤**：临时设 `FIRE_EVENT_AUTO_APPROACH_ENABLED=true`，重复 B1 持续热源触发上报。
- **观察**：后端日志 `fire auto approach command dispatched ...` / `skipped by cooldown`；agent 收到指令进入 B3 的失败-复位路径。
- **通过判据**：首次上报后派发一次；10min 内复报只见 cooldown 日志；**测完立即关回 false**。

## 2. 首飞测试（开阔地、人工监控、遥控可随时接管）

### F1 FlyTo 高度基准（最高风险项，先做）

- **目的**：排除 `KeyAircraftLocation3D` 高度与 `FlyToPointInfo` 高度基准（椭球高/海拔/相对高）不一致导致的错误爬升/俯冲。
- **步骤**：手动起飞悬停 ~30m；下发 `fire-confirmation-mission`，目标坐标取水平 80m 外空旷点。
- **通过判据**：飞行全程高度变化 ≤±5m（standoff 逻辑取 `max(当前高度, fireAlt)`，正常应基本平飞）；任何非预期爬升/俯冲立即接管，记录 OSD 高度与目标参数供修正。

### F2 完整抵近作业 + 激光 MSDK 读取

- **目的**：端到端验证 MEASURE_CLOSE：对中、激光多次采样、坐标落库。
- **步骤**：地面放置热源目标（火盆/加热板，做好防火），F1 通过后对其坐标下发作业。
- **观察**：`FireConfirmProcessor` 日志中激光样本与 `RobustLaserFix`；后端 fire_event 的 `geo_method=LASER_RANGEFINDER`、`geo_error_radius_m=5`、`geo_quality=PRECISE`。
- **通过判据**：①对中日志显示 ROI 偏移逐轮缩小（不收敛但不阻塞也算过，记录 FOV 待校准）；②≥3 个 NORMAL 激光样本，落库坐标与目标实测 GPS 差 ≤10m（无 RTK）；③作业完成 RESET 后航线/悬停状态正常。
- **失败分支**：激光全 NO_SIGNAL → 检查镜头绑定（调研文档 `TC-laser-rf-findings` 的 ZOOM/LEFT_OR_MAIN 假设可能需改 lens 参数）；事件应降级为 standoff 坐标且流程不中断。

### F3 云台相对角旋转行为

- **目的**：验证 RELATIVE_ANGLE 在 M4 上真的按增量转（有 RECENTER"返回成功但不动"的前科）。
- **步骤**：F2 过程中观察对中阶段云台动作，或单独用偏置热源迫使大偏移修正。
- **通过判据**：云台朝正确方向转动且量级合理（偏移 20% 画面 ≈ 转 9° 左右）；"日志显示修正但云台纹丝不动"= 不通过，记录后改用绝对角方案（读当前角+增量）。

### F4 航线场景端到端

- **目的**：真实巡逻语义下全链路。
- **步骤**：跑一条含热源目标的航线，开 `auto-approach-enabled`，全自动走完 发现→悬停确认→疑似上报→自动派发→抵近→激光落库→RESET→航线恢复。
- **通过判据**：全程无人工干预；首报延迟 ≤25s；事件坐标最终为激光值；航线恢复后继续巡逻；指挥舱两级状态与坐标更新正确。

## 3. 参数标定（伴随 F 阶段收集数据）

| 参数 | 现值 | 标定方法 |
| --- | --- | --- |
| 红外 FOV（对中换算） | 45°/37° 标称 | F3 记录"偏移比例→实际需转角度"，偏差 >15% 则修正常量 |
| dwell 4 样本/3 命中 | 拍的保守值 | 巡逻晒热地物区域，统计 `dwell rejected` 与漏报，调 confirmMinHits/阈值 |
| 去重半径 40m | 保守值 | 按定位实测误差收放（上 RTK 后可收到 20m） |
| 激光散布门限 15m / 合理性 3×斜距 | 经验值 | F2 多次作业统计样本散布分布 |

## 4. 开关启用门（全部满足才转常开）

- [ ] B1–B5 全过；F1 高度行为确认；F2 激光落库成功 ≥3 次且无一次错误坐标入库；F4 端到端 ≥2 次成功。
- [ ] `auto-approach-enabled` 转 true 上线；dwell 参数按标定值固化。
- [ ] 任何一条不过 → 相关开关保持关闭，问题记录回 `work-records/`，回到代码侧修正。

## 附：日志速查

| 组件 | TAG / 位置 |
| --- | --- |
| 悬停确认 | agent logcat `ThermalDwellConfirmer`、`ThermalHotspotMonitor`（dwell result / rejected / degraded） |
| 航线暂停恢复 | `WaypointMissionHoldControl` |
| 抵近作业 | `FireConfirmProcessor`（阶段流转、激光样本、RESET 各步） |
| 自动派发 | 后端 `FireApproachDispatcher`（dispatched / skipped by cooldown） |
| 事件合并 | 后端 fire_event 表 report_count/last_seen_time/geo_method |
