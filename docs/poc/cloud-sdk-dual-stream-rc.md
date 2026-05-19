# Cloud SDK Dual-Stream (RC + Pilot 2) PoC — Findings

Date: 2026-05-20 (continued from previous-PoC session, ~01:00–01:20 GMT+8)
Operator: lkw
Drone: M4T (`1581F7K3D249E00AM3Q3`)
RC: RC Plus 2 (`9N9CMA500100B8`)
Backend: localhost:6789, ZLM 1935/58925, Mac LAN 192.168.2.34

## TL;DR

❌ **Cloud SDK 经 `/live/streams/start` 不能实现 dual-stream**，也不能从可见光切到红外。三个并列发现：

1. `/live/streams/start` 带 `video_type:'ir'` 调用**返回 success**，但 ZLM 上来的还是**单镜头可见光**（`video_type` 在 device 侧被忽略）
2. demand 调用**抢占了** Pilot 2 manual push — `RC_PLUS_LOCAL-0` 立刻消失，被 backend 签发的 `1581F7K3D249E00AM3Q3-89-0-0` 取代（**互斥，不并存**）
3. 尝试 `/live/streams/switch`（liveLensChange）切换 lens → MQTT 超时 (`Error Code: 211001, ... No message reply received.`) — Pilot 2 Cloud Api Platform 模式没订阅 lens-change topic

## Setup used

- Token: 从 frontend DevTools 拷的 JWT（workspace=`e3dea0f5-...`，user=adminPC）
- 起始状态：上个 PoC 的 Pilot 2 manual push 仍然活着（`live/RC_PLUS_LOCAL-0` alive 633s），mode=`video-demand-aux-manual`
- 真实设备已登记：`/manage/api/v1/devices/<ws>/devices` 返回 RC + M4T 都 online
- 但 `/manage/api/v1/live/capacity` 返回空 `data:[]` — capacity 未上报（不影响 demand 调用）

## What the API returned

**`POST /manage/api/v1/live/streams/start` — visible URL signing succeeded**
```
请求：{"url_type":1,"video_id":"1581F7K3D249E00AM3Q3/89-0-0/ir-0","video_quality":2,"video_type":"ir"}
返回：{"code":0,"message":"success","data":{"url":"webrtc://192.168.2.34:58925/live/1581F7K3D249E00AM3Q3-89-0-0"}}
```
注意：video_id 必须是**3 段格式**（`<sn>/<payloadIndex>/<videoType>-0`），用 2 段会被 VideoId parser 拒掉返回 210002。

**`POST /manage/api/v1/live/streams/switch` — MQTT 超时**
```
请求：{"video_id":"1581F7K3D249E00AM3Q3/89-0-0/wide-0","video_type":"ir"}
返回：{"code":-1,"message":"Error Code: 211001, Error Msg: The sending of mqtt message is abnormal.. No message reply received."}
```

## ZLM observation

调用 `/streams/start` 之后立刻：
```
live/1581F7K3D249E00AM3Q3-89-0-0 alive=28s tracks=[H264@1280x720]
```
而 `live/RC_PLUS_LOCAL-0` **不见了** — 不是双流并存，是单流接管。

## Frame analysis

`cloud-sdk-dual-stream-evidence-after-demand.png` (从 demand 后的新流抓的单帧)：
内容 = 飞机在地面对着室内墙角的可见光画面，电源插座、地砖、踢脚线清晰可见。**无任何 false-color / 热图特征**。
跟上个 PoC 的 `pilot2-composite-evidence.png` 是同一个视角，只是分辨率一样 (1280×720)，证明 `video_type=ir` 没被 device 接受 / 没换 lens。

## Conclusion

通过 backend 现有 `/live/streams/start` + `/live/streams/switch` 这两条已有路径，**在 Pilot 2 Cloud Api Platform 模式下既拿不到 dual stream 也拿不到 thermal**。

`video-demand-aux-manual` 这个 mode 名字误导 —— 它的"并存"特性显然需要不同的客户端实现（不是这版 Pilot 2 + 这套后端 API），或者只在 dock 自主任务里才生效。

## Next-step recommendation

**为这个项目**，三条候选路径（按推荐顺序）：

1. **接受 Cloud SDK 单流 + 走 lens-switch 时分**
   后端调 `/live/streams/switch` 在 visible / ir 间切换，AI 那边用单流 task 跑两个时段。要先解决 MQTT 211001（先弄明白 lens-change topic 用什么订阅）。**缺点**：不是真正双流，AI 检测得分时间窗，火点漏检率会上升
2. **回到方案 D（rcplus-msdk-agent 主控双流）**
   重新启用 agent + MSDK 双流采集（visible + ir 各一路），跟 Pilot 2 互斥。优点：真双流；缺点：用户失去 Pilot 2 全部 UI，需要 agent 自己实现飞控/HUD/告警
3. **等 dock**
   M4D 自主任务场景下用 Cloud SDK + dock，这条路 DJI 文档明确写支持双流。**但你现在是 RC Plus 手飞场景**，等于放弃这个使用案例

**当前判断**：方案 1 短期最小投入，但工程上把 211001 修通是个新 PoC（要查 DJI MQTT 文档 + 设备实际 capability）。方案 2 大投入，方案 3 等设备。

**实际产品决策**：建议先回去做"AI 火点检测在 Pilot 2 手飞场景下到底要不要严格 thermal+visible 双路"的产品讨论，因为技术上**RC 手飞 + Cloud SDK + 真双流**这条路要么不存在要么需要 DJI 那边加东西。如果产品上**单流可见光够用**（很多火点检测产品就用可见光），那这次 PoC 已经把 cockpit livestream 通畅证明了，可以直接进入 AI 接入阶段。

## Stale artifact note

PoC 结束前调了一次 `/live/streams/stop`，device 侧 push 已停（ZLM 应回到空闲）。上个 PoC 写的 `pilot2-composite-evidence.png` 在另一个分支（`poc/pilot2-composite-stream`），本分支只有 `cloud-sdk-dual-stream-evidence-after-demand.png` 一张证据图。
