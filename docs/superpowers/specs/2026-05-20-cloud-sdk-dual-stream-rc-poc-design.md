# Cloud SDK 双流 (RC Plus + Pilot 2) PoC 设计

Status: Approved (2026-05-20)
Author: lkw + Claude

## 背景

紧接上个 PoC（`docs/poc/pilot2-composite-stream.md`）的发现：

- 方案 B（Pilot 2 PIP 复合）被判死，Pilot 2 这版根本没"自带 RTMP"
- 但 Pilot 2 第三方云平台 → 手动直播能走 Cloud SDK livestream，跟手飞兼容，**单流 visible 已验证**
- `video-demand-aux-manual` 模式手动起只来 1 路，副路（demand）显然要后端 API 触发

后端已有 `POST /manage/api/v1/live/streams/start` endpoint + `LiveTypeDTO` payload。Lens 枚举 `zoom/wide/ir`，调用模板就在 `frontend/src/api/manage.ts` 的 `requestPilotLiveStart`。

## Goal

回答一个**单一未知数**：

> 当 Pilot 2 手动推 visible 那一路在跑（`RC_PLUS_LOCAL-0` 已活）时，**用 backend `/live/streams/start` 把 `video_type` 设为 `ir` 调一次 demand**，ZLM 是否会出现**第二个独立的 thermal 流**？

## Scope

In scope：
- 用 curl 直接 POST `/live/streams/start`，无需改前端
- 观察 ZLM 是否新增第二个流
- 简单视觉确认第二个流的内容是红外（false-color 或灰度热图）

Out of scope：
- 改 ai-service 吃 thermal 流
- 改前端 UI
- 端到端火点检测
- 写新的 backend API（已有 endpoint 直接用）

## Architecture

```
1. [HUMAN] Pilot 2 UI: 手动直播 video-demand-aux-manual + 开始
     └─> Cloud SDK push: visible -> rtmp://192.168.2.34:1935/live/RC_PLUS_LOCAL-0
2. [AUTO] curl POST /manage/api/v1/live/streams/start  with video_type:'ir'
     └─> Backend -> DJI Cloud livestream API -> device pushes thermal -> rtmp://.../RC_PLUS_LOCAL-?
3. [AUTO] curl ZLM getMediaList -> 期望看到 2 个 live/RC_PLUS_LOCAL-* 流
4. [AUTO] ffmpeg 抓两个流各一帧 -> 肉眼判定哪个是 visible / thermal
```

## Procedure

1. **准备**：上个 PoC 已验证 backend、ZLM、ffmpeg。本次额外需要：
   - Backend 在跑（端口 6789）
   - 设备 SN：`RC_PLUS_LOCAL`（agent 默认 SN）
   - 已知 visible 流 stream-id：`RC_PLUS_LOCAL-0`
2. **拉 video_id**：`curl backend GET /live/capacity` 拿到当前 drone 的 cameras_list，确认 thermal lens 对应的 `video_id`（格式 `<deviceSn>/<cameraIndex>/<videoIndex>`）
3. **Pilot 2 启动 manual 那一路**（操作员手动）：跟上个 PoC 一样进 Cloud Api Platform → `video-demand-aux-manual` → 开始
4. **后端 demand thermal**：
   ```bash
   curl -X POST 'http://localhost:6789/manage/api/v1/live/streams/start' \
        -H 'Content-Type: application/json' \
        -H 'x-auth-token: <jwt>' \
        -d '{"url_type":1,"video_id":"<video_id_from_step_2>","video_quality":2,"video_type":"ir"}'
   ```
5. **观察 ZLM**：`getMediaList` 列两个流 → ✅；只 1 个 → ❌（demand 没起来 / 命名冲突）
6. **抓帧验证**：`ffmpeg -frames:v 1` 各抓一帧，肉眼看哪个是 visible / 哪个是 thermal

## Success Criteria

- ZLM 同时列出 ≥ 2 个 `live/RC_PLUS_LOCAL-*` 流
- 两个流各自抓帧后，一帧明显是可见光（自然色彩）、另一帧明显是红外（false-color 或灰度热图）
- 截两张证据图存到 `docs/poc/cloud-sdk-dual-stream-evidence-visible.png` / `*-thermal.png`

## Failure Modes & Responses

| 现象 | 含义 | 下一步 |
|---|---|---|
| API 返回 401 / 鉴权失败 | JWT 没拿到或过期 | 从 /login 拿 token 再调 |
| API 返回 200 但 ZLM 仍只 1 个流 | demand 路径没真触发 / 命名冲突覆盖了主流 | 看 backend 日志 + abstractLivestreamService 行为 |
| API 返回错误（设备不支持 / 镜头不存在） | 这台 M4T + RC Plus + 当前固件不支持 demand 模式的 ir lens | **方案 C' 双流死，方案 D 重新被考虑** |
| 2 个流但内容都是 visible | demand 没真的切到 thermal 镜头 | 检查 video_id 字段是不是真指向 thermal |
| 2 个流 + 内容对（visible + thermal） | ✅ 方案 C' 双流可行 | 写新的 spec：ai-service 接 thermal pipeline |

## Deliverable

- 分支：`poc/cloud-sdk-dual-stream-rc`
- 笔记：`docs/poc/cloud-sdk-dual-stream-rc.md`
- 证据：
  - `docs/poc/cloud-sdk-dual-stream-evidence-visible.png`
  - `docs/poc/cloud-sdk-dual-stream-evidence-thermal.png`（若双流成功）
  - 或单张 `*-no-second-stream.png`（若失败）+ ZLM 列表的 JSON 转储

## Next Step (post-PoC)

- ✅ 通过 → 设计 ai-service 接 thermal 流的 spec（另起一份）；进入"中等 scope"工作
- ❌ 通过不了 → 报告里写清失败模式，决定走方案 D（重投入 agent 主控）还是接受单流可见光
