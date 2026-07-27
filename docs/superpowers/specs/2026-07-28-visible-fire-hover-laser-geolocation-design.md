# 可见光火情悬停与激光定位设计

## 目标

当可见光 YOLO 连续两帧确认火情后，系统立即暂停当前航线并让无人机悬停，同时创建火情事件和发送告警。飞机稳定后重新锁定火焰目标，使用 DJI MSDK 激光测距模块直接取得目标点经纬度和高度，并将精确坐标回填到同一条火情事件。整个流程不得恢复红外识别或红外测温。

## 已确认的业务行为

1. 两帧可见光确认是唯一的自动火情触发条件。
2. 触发后立即请求暂停航线/悬停，不等待激光定位才告警。
3. 首次事件状态为 `LASER_LOCATING`，界面显示“正在精确定位”，不得把飞机坐标展示成火点坐标。
4. 激光坐标只在飞机稳定悬停、目标重新锁定且多次采样通过校验后采纳。
5. 激光定位成功后更新原事件，不创建第二条火情事件。
6. 激光定位失败时保留火情和证据，状态改为 `LASER_FAILED`，不得用飞机坐标冒充火点坐标。
7. 定位完成或失败后保持悬停，等待人工处置，不自动恢复原航线。
8. 每架飞机同一时间最多执行一个定位任务；重复识别由现有事件去重机制处理，不重复抢占云台。

## 方案选择

### 采用：事件直报与异步激光定位

AI 将最高置信度可见光框的归一化 ROI 随每帧双流事件和离散火情事件一起上报。后端先持久化事件并发送告警，再下发 `visible-fire-hold` 命令。RC Plus Agent 暂停航线并验证稳定悬停，ACK 后由后端选取 AI 在悬停后上报的最新鲜 ROI，再下发 `visible-fire-laser-measure`。Agent 对准云台、激光采样，并通过命令 ACK 回传定位结果。后端根据事件标识原位更新火点坐标。

优点是告警不被飞控或激光失败阻塞，MSDK 直接提供目标坐标，无需维护射线/DEM 算法；代价是精确坐标会比首次告警晚数秒出现。

### 未采用：同步完成定位后才创建事件

该方案数据一次成型，但暂停航线失败、稳定等待超时或激光不可用都会延迟甚至阻断告警，不符合火情优先上报原则。

### 未采用：继续使用飞机坐标或后端射线/DEM

飞机坐标不是火点坐标；射线/DEM 需要准确姿态、相机内参和地形数据，且精度低于 M4T 激光测距能力。两者只保留为遥测或人工诊断信息，不作为自动火点坐标。

## 数据契约

### AI 到后端

可见光火情创建载荷新增：

```json
{
  "visible_roi": {
    "x": 0.72,
    "y": 0.46,
    "width": 0.08,
    "height": 0.05
  },
  "geo_method": "LASER_RANGEFINDER",
  "geo_quality": "LASER_LOCATING"
}
```

`visible_roi` 使用归一化左上角和宽高，范围为 `[0, 1]`。无有效框时不得启动自动激光定位，但仍可创建火情，状态为 `LASER_FAILED`。

### 后端到 Agent

流程拆成两个命令，保证激光使用的是悬停后重新识别的框。

第一条命令 `visible-fire-hold`：

```json
{
  "eventId": "fire-<droneSn>-<sourceTs>",
  "taskId": "fire-<droneSn>",
  "sourceTs": 1785171009455
}
```

Agent 只有在真实稳定条件满足后才 ACK `HOVER_STABLE`。

后端收到 `HOVER_STABLE` 后，从该任务每帧双流事件中选择时间不早于悬停命令、年龄不超过 `1500 ms` 的最新可见光 ROI，再下发 `visible-fire-laser-measure`：

```json
{
  "eventId": "fire-<droneSn>-<sourceTs>",
  "taskId": "fire-<droneSn>",
  "sourceTs": 1785171009455,
  "visibleRoi": {
    "x": 0.72,
    "y": 0.46,
    "width": 0.08,
    "height": 0.05
  }
}
```

两条命令都必须标记为紧急，并按飞机串行执行。Agent 已有运行中的定位任务时返回 `busy`，后端不创建重复任务。

### Agent 到后端

激光测距命令 ACK 扩展为携带定位结果：

```json
{
  "status": "applied",
  "message": "LASER_LOCATED",
  "eventId": "fire-<droneSn>-<sourceTs>",
  "fireLat": 34.960123,
  "fireLng": 109.316456,
  "fireAlt": 386.2,
  "geoMethod": "LASER_RANGEFINDER",
  "geoQuality": "PRECISE",
  "geoErrorRadiusM": 5.0
}
```

定位失败 ACK 使用 `status=failed`、`message=LASER_FAILED:<reason>` 和原事件标识，不携带 `fireLat/fireLng`。

## 后端事件语义

现有表中 `fire_event.lat/lng` 为非空字段，因此首次创建仍由 OSD 填入占位值，同时把同一时刻飞机位置写入 `aircraft_lat/aircraft_lng/aircraft_alt`。只要 `geo_quality=LASER_LOCATING` 或 `LASER_FAILED`：

- API/前端不得将 `lat/lng` 标注为火点坐标；
- 不得参与火点空间去重；
- 不得生成自动航线、抵近任务或投放任务；
- 告警内容显示“火点坐标定位中”或“激光定位失败”。

激光成功后在一个事务内更新 `lat/lng/alt`、`geo_method`、`geo_quality`、`geo_error_radius_m` 和 `geo_source_ts`，并写入一条定位历史记录。更新操作必须按原事件 ID 幂等；重复成功回调不得重复通知或创建事件。

## Agent 定位状态机

### 1. HOLDING

收到 `visible-fire-hold` 后立即调用现有 `MissionHoldControl.holdForConfirmation()` 暂停活动航线。没有活动航线时仍显式执行飞控悬停。暂停失败时继续尝试悬停；悬停指令失败则终止定位并 ACK `LASER_FAILED`。

### 2. WAITING_STABLE

使用飞控速度遥测验证真实稳定状态，而不是只相信命令 ACK：

- 水平速度不超过 `0.3 m/s`；
- 垂直速度绝对值不超过 `0.2 m/s`；
- 条件连续满足 `1000 ms`；
- 最长等待 `8000 ms`。

超时 ACK `LASER_FAILED` 并保持当前安全飞行状态；稳定后 ACK `HOVER_STABLE`。

### 3. REACQUIRING

Agent 稳定 ACK 后继续悬停。AI 推理不中断，并继续把最新可见光 ROI 放入每帧双流事件。后端优先选择与原始 ROI 中心距离最近且达到可见光报告阈值的框；`3000 ms` 内没有时间新鲜且中心连续的 ROI，则把原事件标记为 `LASER_FAILED`。不能直接使用减速前的旧框进行测距，首版不依赖 Agent 端部署第二份 YOLO 模型。

### 4. AIMING

Agent 收到 `visible-fire-laser-measure` 后，将重新锁定框中心移到激光目标位置。优先调用 MSDK `KeyTapZoomAtTarget`；设备不支持时使用现有云台相对角度控制。每次调整后读取激光屏幕位置，最多迭代三次。只有激光屏幕点位于传入的火焰 ROI 内才进入采样。

### 5. MEASURING

复用现有 `DjiLaserRangefinderClient`：

- 设置 `KeyLaserWorkMode=OPEN_ON_DEMAND`；
- 启用 `KeyLaserMeasureEnabled`；
- 读取 `KeyLaserMeasureInformation.location3D`；
- 仅接受 `LaserMeasureState.NORMAL`；
- 默认采集 3 个有效样本，样本间隔 `300 ms`；
- 经纬度样本最大离散半径不超过 `15 m`；
- 使用稳健中心作为最终坐标；
- 对外误差半径暂定 `5 m`。

采样完成后关闭按需激光模块。任何异常都转为明确失败结果，不覆盖已有证据。

### 6. HOLD_COMPLETE

无论成功或失败，都不调用 `resumeAfterConfirmation()`。Agent 保持航线暂停/悬停，直到人工下达恢复航线、返航或其他控制命令。

## 并发、去重和超时

- Agent 使用现有单任务原子锁，`visible-fire-hold` 成功后保持该事件的定位会话；只有匹配事件 ID 的 `visible-fire-laser-measure` 可以继续，其他定位命令返回 `busy`。
- 后端按 `eventId` 记录定位状态，重复命令不重复下发。
- AI 的 10 秒上报去抖继续生效。
- 同一事件的成功回调覆盖 `LASER_LOCATING`；失败回调不得覆盖已经成功的 `PRECISE`。
- 定位总超时建议为 `15 s`，超时只改变定位状态，不删除事件。
- 后端重启后可根据仍处于 `LASER_LOCATING` 且未超时的事件决定重派一次；首版不做无限重试。

## 安全与降级

- 只有飞机在线且控制权可用时下发悬停/定位命令。
- 地面调试时不执行航线暂停和云台定位，事件保留并标记 `LASER_FAILED`。
- 电量过低、飞控速度不可读、激光状态异常、目标丢失或 SDK 不支持时均停止自动定位并保持告警。
- 激光定位流程不得调用红外视频源、热成像测温或 `focus-thermal`。
- 人工飞控、返航和低电量安全动作优先级高于自动定位。

## 前端表现

- `LASER_LOCATING`：坐标列显示“正在精确定位”，不显示占位经纬度。
- `PRECISE + LASER_RANGEFINDER`：显示火点经纬度，并标注“激光定位”。
- `LASER_FAILED`：显示“激光定位失败”，保留重新定位入口；不显示飞机坐标。
- 飞机坐标只在详情的“检测时飞机位置”字段中展示。

## 测试与验收

### AI

- 最高置信度可见光框正确转换为归一化 ROI。
- 两帧确认后载荷包含 `visible_roi` 和 `LASER_LOCATING`。
- 无框检测不伪造 ROI。

### 后端

- `LASER_LOCATING` 事件仍即时创建并通知，但不参与空间去重和自动任务。
- 创建后只下发一次 `visible-fire-hold`，稳定 ACK 后只下发一次匹配事件的 `visible-fire-laser-measure`。
- 成功回调原位更新坐标并标记 `PRECISE`。
- 失败回调保留事件并标记 `LASER_FAILED`。
- 重复、乱序和跨飞机回调被安全拒绝或幂等处理。

### Agent

- 命令先暂停航线/悬停，再等待速度稳定。
- 未稳定、目标丢失、激光异常分别产生明确失败状态。
- 正常采样只接受 `NORMAL`，并执行离群值过滤。
- 完成后不自动恢复航线。
- 全流程不触发任何红外动作。

### 现场验收

1. 飞行航线中放置一个可见火源。
2. 两帧确认后，观察飞机立即停止航线并进入悬停。
3. 前端立即出现火情告警，坐标显示“正在精确定位”。
4. 稳定后云台重新锁定火焰，约数秒后同一事件更新为激光坐标。
5. 将更新后的坐标与 DJI Pilot 2 激光标点对比，偏差应在声明误差范围内。
6. 定位完成后飞机继续悬停，且日志中无 `focus-thermal`、热成像测温或 `channel=thermal`。
