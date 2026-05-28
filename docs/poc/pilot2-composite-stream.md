# Pilot 2 Composite Stream PoC — Findings

Date: 2026-05-19 → 2026-05-20 (one operator session)
Operator: lkw
Drone: M4T
RC: RC Plus
Backend: ZLMediaKit (Docker) on Mac, HTTP API :58925, RTMP :1935

## TL;DR

- **方案 B (Pilot 2 推 PIP 复合到 ZLM) — 不可行**
- 原因和我们最初的假设不一样：**Pilot 2 在这个安装版本下根本没有"自带 RTMP 直播"入口**。能找到的唯一直播入口是 **DJI Cloud SDK livestream UI**（"Cloud Api Platform → 手动直播"），它推的画面**只有当前主镜头**，Pilot 2 UI 上开的 PIP 小窗不进流
- **意外收获**：Cloud SDK livestream 在 RC Plus + Pilot 2 + 手飞模式下走得通，**不抢 MSDK / 跟 Pilot 2 手飞兼容**，单流可见光 H264 1280×720 已验证通到 ZLM —— 这是我们之前矩阵里方案 C 的能力，但当时以为它"只能在 dock 自主任务"。这次证明 **RC 手飞也行**

## Setup used

- Mac LAN IP: `172.20.10.7`
- RTMP URL (后端自动签发，不可改): `rtmp://172.20.10.7:1935/live/RC_PLUS_LOCAL-0`
- ZLM HTTP API: `http://localhost:58925`（secret `psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy`）
- Pilot 2 直播入口：第三方云平台 → 手动直播（即 Cloud Api Platform）
- 三种直播模式选项（dropdown）：`video-on-demand` / `video-by-manual` / `video-demand-aux-manual`

## What happened

1. 翻遍 Pilot 2 设置、飞行界面顶栏、相机面板：**没找到 DJI 自带的"自定义 RTMP 直播"功能**
2. 唯一找到的入口是"第三方云平台 → 手动直播"，进去后看到 Cloud Api Platform UI，URL 已经被后端预填、不可编辑
3. 选 `video-demand-aux-manual` 模式 + 点 "开始"
4. 后端 ZLM 立刻看到 `live/RC_PLUS_LOCAL-0` 单个流，H264 1280×720, origin=rtmp_push
5. 用 `ffmpeg -frames:v 1` 抓单帧 PNG（见 `pilot2-composite-evidence.png`）

## Backend observation

```bash
$ curl -s "http://localhost:58925/index/api/getMediaList?secret=..." | jq
{
  "code": 0,
  "data": [
    {
      "app": "live",
      "stream": "RC_PLUS_LOCAL-0",
      "originTypeStr": "rtmp_push",
      "aliveSecond": 58,
      "tracks": [{ "codec_id_name": "H264", "width": 1280, "height": 720 }]
    }
  ]
}
```

**只有 1 个流**。`video-demand-aux-manual` 名字暗示"主路按需 + 副路手动"，但实际只起了 1 个。怀疑：
- 副路（按需）需要后端调用 Cloud SDK livestream API 主动 demand，不会因为 Pilot 2 UI 点"开始"自动起
- 或者要在 Pilot 2 里额外做副镜头激活动作（这次没探索）

## Visual judgment

抓到的 `pilot2-composite-evidence.png`：

- 可见光内容：✅（飞机在地面对着墙角，看得见地砖、踢脚线、电源插座的可见光画面）
- 红外内容：❌（无任何 false-color / 灰度热图特征）
- 同帧：❌（只有一种内容）

**结论**：Pilot 2 UI 的 PIP 模式不影响 Cloud SDK livestream 推出来的内容。流里始终是"当前主镜头的 raw feed"，UI overlay (PIP 小窗、HUD、HUD 文字) 全都不进流。

## 真正回答的问题

| 问题 | 答案 |
|---|---|
| Pilot 2 自己有 RTMP 直播功能么？ | ❌ 这版没有 / 没启用，找不到入口 |
| Pilot 2 推流的画面会带 PIP 复合么？ | ❌ Cloud SDK livestream 推的是单镜头 raw |
| Cloud SDK livestream 在 RC 手飞场景能用么？ | ✅ 单流通了，跟手飞共存无冲突 |
| Cloud SDK 能推双流（可见光 + 红外）么？ | ⚠ **未确认**。`video-demand-aux-manual` 模式存在，但仅手动起的话只来了 1 路 |

## Troubleshooting that was needed

- 最初找不到 RTMP 设置入口（设置/飞行 UI/相机面板都翻过），后来才发现是在"第三方云平台 → 手动直播"下走 Cloud SDK 通道
- ZLM 端口和 secret 与 spec 写的不一致：实际是 `:58925` + `psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy`（spec/plan 已修正）
- ffmpeg 抓帧时有大量 `missing picture in access unit` 警告 — 因为连流是 mid-stream 进入，缺 I-frame；不影响最终单帧产出

## Conclusion

- **方案 B (PIP 复合单帧) 死亡** — 没有这条路径
- **方案 C (Cloud SDK livestream) 部分确认可用** — RC + Pilot 2 + 手飞模式下单流通了；双流模式存在但未跑通

## Next-step recommendation

我们矩阵里方案 C "Cloud SDK 走 dock" 现在应该改写为 **方案 C': Cloud SDK livestream via Pilot 2（dock 或 RC 都行）**。这条路：

- 跟 Pilot 2 手飞天然兼容（不抢 MSDK / 不抢相机）
- 不需要 agent 主控（D 那个重投入路径可以暂时搁置）
- 关键未确认：双流模式怎么真正跑通

**下一个 PoC（建议另开分支 `poc/cloud-sdk-dual-stream-rc`）**：

1. 选 `video-demand-aux-manual` 模式 + 后端 API 主动 demand 副路 → 看 ZLM 是否同时出现 `RC_PLUS_LOCAL-0` + `RC_PLUS_LOCAL-1`（或类似命名）
2. 如果两个流都来，确认一个是可见光、一个是红外
3. 如果只来 1 个，再试 Pilot 2 里有没有额外的"副镜头"激活动作
4. 端到端：把双流接进 ai-service 的 visible + thermal 检测 pipeline

如果下个 PoC 也通，AI 火点检测就能在 RC 手飞场景下闭环，**不需要 dock**，**不需要 agent 接管 MSDK**。这是我们一开始没看到的优解。
