# M300 工作树整合与清理记录

日期：2026-09-05。范围：本机 Git 分支、工作树、未提交成果及回归验证。

## 结论与统一入口

- 后续 M300/Windows 现场交付开发统一使用 `feature/m300-model-adaptation`。
- 当前工作目录：`/Users/likewang/uavfire/.worktrees/m300-model-adaptation`。
- 已合入小程序基座与兼容的 M350 补充代码；小程序、微信登录和未验收飞控能力仍默认关闭。
- 旧 NCNN 检测闭环与 PM430 备份属于不同技术方案，已完整归档，没有无差别覆盖当前 ONNX/媒体方案。
- 本次完成的是源码整合与本地清理，不代表所有历史功能均已集成，也不代表 Windows、微信、真机或实飞验收完成。

## 已保存的提交

| 提交 | 内容 |
| --- | --- |
| `1ff9ba6` | 保存 M300 当前工作：Windows 安装与修复脚本、地图部署点、运行时 URL、面状航线/轨迹/转弯修复、三端试用期限、Agent 地址与模型更新 |
| `ce1e522` | 保存小程序工作树的 63 个改动文件：产品/技术/API 文档、原生小程序工程、BFF/鉴权/能力策略、M350 负载校验及测试 |
| `334f04b` | 合并上述成果；保留较新的 6 份小程序文档，增加 M300/M350 合并行为测试，修正过时的地图和 Nginx 测试断言 |

安装包、运行数据、模型、私有配置及所有忽略文件均保留在文件归档中；新增 `/releases/` 忽略规则，避免把生成的巨型交付包误提交到 Git。

## 备份与恢复证据

私有归档目录：

`/Users/likewang/uavfire-archive/consolidation-20260905.atP4XJ`

- `repository-before/`：操作前整个仓库目录的 APFS 克隆，包含 Git 历史、所有工作树、依赖、安装包、模型及本地配置。
- `*-changed-manifest.json` / `*-tracked-before.patch`：每个工作树的改动清单、SHA-256 和可还原补丁。
- 共核对 902 个改动/未跟踪路径：根目录 25、旧 Claude 644、旧 Agent 0、小程序 63、M300 170。
- 三个待清理工作树另经 `rsync -rclni --delete` 只读内容校验，均无差异。
- `history-before.bundle` 与 `history-consolidated.bundle`：操作前及整合后的完整 Git 历史包，均通过 `git bundle verify`。
- `recovery-check.git/`：从整合历史包独立恢复的裸仓库，通过 `git fsck --full --no-dangling`，六个旧分支 HEAD 与归档标签逐项一致。
- 根目录原有 25 个改动文件在操作后重新核对，内容未变。

归档在同一台电脑上，不是异地备份；含私有配置，不应公开上传或分发。

## 已清理的工作树

采取可恢复移动，不直接删除文件。以下目录已移至私有归档的 `retired-worktrees/` 并解除 Git 工作树登记：

| 原工作树 | 处理 |
| --- | --- |
| `.worktrees/m300-miniapp-foundation` | 未提交源码已保存并合入 M300，全部文件保留 |
| `.worktrees/agent-fire-detection` | 替代检测架构保留于标签及文件归档，不修改当前推理架构 |
| `.claude/worktrees/recursing-thompson-afa5f7` | 私有配置、虚拟环境等原样保留，不将凭据提交到源码仓库 |
| `/private/tmp/uavfire-m300-v019.OUlcMh` | 原目录早已不存在，仅清理失效登记；HEAD 留有归档标签 |

仍保留两个登记入口：根目录 `/Users/likewang/uavfire` 和当前 M300 工作树。根目录不是本次交付开发入口，但其 `.git`、真实 DJI UXSDK、模型/依赖及原始未提交文件仍被使用，因此没有移除或强行切换分支。

## 已归档的本地分支

下列本地分支名已清理，原提交由 `archive/20260905/` 前缀的同名标签保留：

| 原分支 | 保存的提交 |
| --- | --- |
| `claude/recursing-thompson-afa5f7` | `887812e` |
| `codex/backup-m300-before-b112aef-20260901` | `1e93f3b` |
| `codex/backup-m300-local-before-remote-20260901-0025` | `deb165e` |
| `codex/m300-miniapp-foundation` | `ce1e522` |
| `feature/agent-fire-detection-runtime` | `9f8ed13` |
| `feature/agent-visible-fire-closed-loop` | `228b167` |

另保留 `archive/20260905/m300-consolidated` → `334f04b`，以及临时工作树 HEAD 标签 `archive/20260905/m300-v019-detached` → `6c48db2`。

未修改远端分支，未执行 push、远端删除、现场服务重启、APK 安装或飞行指令。与本次 M300 整合无关的历史分支保持不动。

## 验证结果

| 项目 | 结果 |
| --- | --- |
| Java 后端 `mvn test` | 483 项通过，0 失败/错误/跳过，包含 Spring 上下文测试 |
| Agent `testDebugUnitTest` | 256 项通过，0 失败/错误/跳过 |
| Agent `assembleDebug` | 使用真实 DJI UXSDK 构建成功；没有安装至设备 |
| 前端 `npm run build` | 生产构建成功，仍有大体积 chunk 提示 |
| 小程序 `npm test` | 4 项通过 |
| 合并/地图/区域航线/轨迹/试用期专项 | 20 项通过，其中新增 M300/M350 行为用例 4 项 |
| Windows 通用脚本测试 | 通过，包含 Nginx 停止保护、数据库参数、重复实例等检查 |
| Windows 地图脚本测试 | 通过，覆盖部署点持久化、校验及热修复安装流程 |
| 前端全量 | 346 项中 322 通过、24 失败，不能写成全量通过 |

前端操作前备份复跑结果为 342 项中 317 通过、25 失败。与第一次合并结果逐项比对，没有新增失败；更新了一项已被服务器部署点策略替代的浏览器自动定位断言，最终剩余 24 项均属于既有失败。涉及驾驶舱布局/播放策略、FC100 控件、起飞策略和航线源码断言，需另行逐项确认，不能只改断言来掩盖真实问题。

完整结果和失败清单见私有归档中的 `frontend-baseline-tests.log`、`frontend-final-tests.log`、`frontend-known-failures.txt`、`backend-tests.log`、`agent-tests-build.log`、`windows-tests.log`、`windows-map-tests.log` 和 `consolidation-focused-tests.log`。

Windows 脚本是在 macOS PowerShell 上进行逻辑/配置/受控进程测试；没有模拟整台 Windows 机器或现场媒体链路，不能代替真实部署验证。

## 恢复方式与后续约束

1. 历史源码可以从上述归档标签重新创建一个 `codex/` 分支；已实际演练 Git 历史包恢复。
2. 未提交私有文件和忽略的模型/环境从 `repository-before/` 或 `retired-worktrees/` 恢复；先在独立目录操作，避免覆盖当前工作。
3. 文件归档中的 `.git` 指针保留原始路径，解除登记后不要直接将归档目录当作活跃工作树使用；先从 bundle/tag 建立新 checkout，再选择性恢复所需文件，排除旧 `.git`。
4. Agent 本机构建需要 `JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`、`ANDROID_HOME=/Users/likewang/Library/Android/sdk` 和 `DJI_UXSDK_DIR=/Users/likewang/uavfire/Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk`。
5. 下一次交付前应处理已记录的 24 项前端既有失败，并对最终版本做现场视频、Windows 服务、遥控器及飞行验收。本次没有重做完整 Windows 安装包，原交付包仍在 `releases/` 和私有备份中。
