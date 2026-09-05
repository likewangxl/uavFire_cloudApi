# 宣传与演示资料归档

本目录收录 2026 年 6–7 月留存在本地的宣传源稿、可编辑 PPT 和配套生成脚本，2026-09-05 补入 `main`。文件内容保持原样，属于历史资料。涉及服务器端 AI、双光复核、定位和设备能力的旧描述，需要按当前代码和实际验收结果更新后再对外使用。

当前主线与设备兼容性说明见 [Main 整合记录](../main-integration-and-m4t-compatibility-2026-09-05.md)。当前主线的可见光 AI 推理在 Agent 端；历史宣传资料不能作为已部署或已实飞的证明。

## 收录内容

| 资料 | 用途 |
| --- | --- |
| `智能集群大载重无人机灭火系统-宣传视频文案母稿.md` | 宣传视频原始文案 |
| `智能无人机森林防火灭火系统-推广.pptx` | 历史可编辑推广稿，13 页 |
| `智能集群大载重无人机灭火系统-推广.pptx` | 历史图文推广稿，13 页 |
| `history/2026-06-10/*.pptx` | 3 份销售介绍稿，分别为 14、24、18 页 |
| `prep_images.py`、`make_promo_ppt.py` | 图文推广稿的配图处理与生成源码 |
| `make_leader_scene_ppt.py` | 2026-07-11 消防领导场景版生成源码 |
| `assets/shots/` | 第一套脚本需要的 10 张输入原图 |
| `assets/leader_20260711/` | 领导场景版需要的 12 张输入原图 |

另从本地保留的历史 Git 引用 `49f1b89` 提取了两份尚未进入主线的资料：[更新设计](../superpowers/specs/2026-07-10-uav-fire-promotion-deck-refresh-design.md)、[实施计划](../superpowers/plans/2026-07-10-uav-fire-promotion-deck-refresh.md)。其中路径、技术栈和待办均是当时记录，本次仅归档。

## 后续编辑与生成

脚本依赖 Python、Pillow 和 python-pptx。准备好依赖后，从仓库根目录执行：

```bash
python3 docs/promo/prep_images.py
python3 docs/promo/make_promo_ppt.py
python3 docs/promo/make_leader_scene_ppt.py
```

这些命令会重写各脚本指定的 PPT 文件；编辑历史稿前请另存副本或在分支中操作。本次未执行生成命令，没有改动演示文稿内容，也没有重新逐页验收版式。

`assets/final/`、背景图片、`_build/` 为可重建中间产物，继续忽略。其他未使用截图、视频、缓存及 `outputs/` 中的渲染副本保留在本地。历史 PPT 的原始路径与本次归档路径、文件大小和 SHA-256 见 [来源清单](../local-source-archive-2026-09-05.json)。
