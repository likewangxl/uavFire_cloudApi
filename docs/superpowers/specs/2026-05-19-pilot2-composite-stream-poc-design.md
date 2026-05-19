# Pilot2 复合流 PoC 设计

Status: Approved (2026-05-19)
Author: lkw + Claude

## 背景

M4T 需要同时拿到可见光 + 红外两路 H.264 流给后端 AI 火点检测使用。
已知方案矩阵：

| 方案 | 形式 | 是否与 Pilot2 手飞兼容 | 状态 |
|---|---|---|---|
| A | Pilot2 自带 RTMP（单镜头） | ✅ | 已知可用，但只有一路 |
| **B** | **Pilot2 自带 RTMP（PIP 复合）** | **✅（如果支持）** | **本 PoC 验证** |
| C | Cloud SDK 走 dock | ❌（仅自主任务） | 后续机场场景 |
| D | rcplus-msdk-agent 主控 + MSDK 双流 | ❌（用户失去 Pilot2 UI） | 已有基础设施，重投入 |

方案 D 在 `docs/MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md` 有研究。
方案 B 是性价比最高的"如果能用就赚到"路径。

## Goal

回答一个**单一未知数**：

> Pilot2 的 RTMP 推流功能，能不能把"画中画 / 双镜头同显"模式下的复合画面（同帧含可见光 + 红外）推到后端 ZLMediaKit？

## Scope

In scope：
- 设备端配置 Pilot2 RTMP → 后端 ZLM
- 后端用 ZLM Web UI / ffplay 视觉确认流内容
- 写一份 markdown 笔记记录结论

Out of scope（PoC 阶段不做）：
- 复合画面裁切 / 拆分
- ai-service 接入
- 前端 cockpit 播放新流
- 自动化截帧 / hook

## Architecture

```
Pilot2 (RC Plus, Android) ──RTMP push──> ZLMediaKit (Mac :1935)
                                              │
                                              ├── HTTP API :58925─┐
                                              │                   ├── 人眼判定
                                              └── ffplay 拉流  ────┘
```

零代码改动。验证完全在"Pilot2 配置 + 后端观测"两端。

## Procedure

1. **准备**
   - `curl http://localhost:58925/` 确认 ZLM 在跑
   - 拿到 Mac LAN IP：`ifconfig | grep "inet 192"`（如果换过 wifi，先跑 `scripts/switch-dev-ip.sh` 同步配置）
   - RC Plus 和 Mac 在同一个 WiFi
2. **Pilot2 配 RTMP**
   - 设置 → 直播平台 → 自定义 → URL：`rtmp://<mac-ip>:1935/live/pilot2-composite`
   - ZLM RTMP push 默认不需要 secret（hook.on_publish 未挂），smoke test 已验证。如 Pilot2 报 auth 错误再加 `?secret=...`
   - 真实 ZLM 配置在 `deployment/zlmediakit/config/config.ini`（HTTP API 端口 58925，secret `psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy`，跟 application.yml 的 `CloudApiSample` 不一致 —— ZLM 实际跑的是 config.ini 里那份）
3. **开 PIP**
   - Pilot2 飞行界面打开双镜头 / 画中画
   - M4T 主镜头通常是可见光（广角/变焦），小窗是红外
4. **起推**
   - 飞机/RC 通电 + Pilot2 看到画面后，在直播设置里点"开始直播"
5. **后端观测**
   - `curl -s "http://localhost:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy"` 看 `pilot2-composite` 在不在
   - `ffplay rtmp://<mac-ip>:1935/live/pilot2-composite` 拉流播放
6. **视觉判定**
   - 单帧里同时能看到可见光 + 红外 → ✅ B 可行
   - 只有一个镜头 → ❌ B 不可行
   - ZLM 收不到 → 走 Troubleshooting

## Success Criteria

- ZLM Web UI 出现 `/live/pilot2-composite` 流
- ffplay 能播放
- 单帧画面同时含**可见光内容 + 红外内容**（小窗 PIP 形式或并排都算）
- 截一张图作为证据

## Failure Modes & Responses

| 现象 | 含义 | 下一步 |
|---|---|---|
| ZLM 收不到 RTMP push | 网络 / URL / auth 问题 | 排查（不否定方案 B） |
| ZLM 收到但画面是单镜头 | Pilot2 RTMP 不推 PIP 复合 | **方案 B 整体不通**，回到 A / D 讨论 |
| Pilot2 在 PIP 模式下禁推 RTMP | UI 互斥 | 同上 |
| 复合画面但红外质量太差 / 太小 | 技术上能用但实用性存疑 | 写报告 + 后续讨论是否接受 |

## Deliverable

- 分支：`poc/pilot2-composite-stream`
- 笔记：`docs/poc/pilot2-composite-stream.md`（执行后写）
  - 实际操作步骤（包括踩坑记录）
  - 一张证据截图（ZLM Web UI 或 ffplay 播放截图）
  - 结论（B 可行 / 不可行）
  - 下一步建议（接 ai-service 切分、回退到方案 A、或转向 D）

## Risk

唯一风险：Pilot2 的 RTMP 推流可能根本不支持 PIP 复合（DJI 没明确文档过）。
真发生这种情况 PoC 也算"成功"——它**回答了未知数**，让我们不会浪费工作在不可行的方案 B 上。

## Next Step (post-PoC)

- B 可行 → 设计后端裁切 + ai-service 双路接入（另起一份 spec）
- B 不可行 → 回到方案 A（单路够用）或方案 D（MSDK 双流，参考已有 research doc）
