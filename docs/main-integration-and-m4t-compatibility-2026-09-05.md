# Main 整合、分支清理与 M4T 兼容性评估

日期：2026-09-05。依据用户要求，将当前 M300 成果合入 `main`，清理明确可退役的分支，并评估既有 M4T 设备兼容性。

## 已完成的整合

- 将本地 `main` 从 `9c83f5e` 快进到 M300 最新提交 `2ee80d7`，随后将 GitHub `main` 从 `c5310fc` 同步至 `2ee80d7`。保留完整祖先关系，没有 squash、重写历史或冲突取舍。
- 原来未推送的 4 个提交现已进入 GitHub `main`：`1ff9ba6`、`ce1e522`、`334f04b`、`2ee80d7`，包含 Windows 交付、航线修复、试用控制、小程序和 M350 补充。
- 同步并核对远端 M300 后，将已完成合入的 M300 分支转为归档标签并删除分支名。
- 当前开发分支为 `main`，工作目录仍是 `/Users/likewang/uavfire/.worktrees/m300-model-adaptation`。目录名为历史命名，不代表仍在 M300 分支。
- 项目根目录 `/Users/likewang/uavfire` 保留原提交 `b47042b` 及未提交现场；最终清理时仅在同一提交上转为 detached HEAD，没有强制切换到新版本或清理文件。
- 本次保留原有试用授权规则与已知前端失败，没有将主线合并描述为现场交付验收通过。

本文件及交接入口更新作为后续纯文档提交进入 `main`，不会改变上述已经测试的业务代码。

## 最终分支状态

用户在源码和设计资料补齐后，同意将剩余两条分支转为归档。**本地与 GitHub 均只保留 `main` 分支**，远端默认分支仍为 `main`。

| 已退役分支或引用 | 保留的提交 | 归档标签 |
| --- | --- | --- |
| `feature/fire-precision-and-realtime-detection` | `b47042b` | `archive/20260905/feature/fire-precision-and-realtime-detection` |
| `feature/agent-visible-fire-closed-loop` | `228b167` | `archive/20260905/feature/agent-visible-fire-closed-loop` |
| 本地遗留引用 `deploy/current` | `49f1b89` | `archive/20260905/deploy/current` |

三个 annotated tag 均已推送并核对远端标签对象与解引用提交后，才删除分支或引用。旧火情基线是 main 的祖先，没有独有提交；NCNN 方案的 69 个独有提交继续由标签保留，未合入当前 ONNX 主线。`deploy/current` 不属于当前 GitHub 分支，其两份独有文档已在 `38abf2e` 补入 main。

根工作区仍保留原有 25 个改动/未跟踪文件；在同一提交解除分支绑定后逐文件核对 SHA-256，内容均未变化。标识源文件另已收录在 main 的独立设计方案中。根工作区用于保留旧现场，新开发继续使用 `/Users/likewang/uavfire/.worktrees/m300-model-adaptation`。

需要恢复 NCNN 方案时，可在独立目录从标签创建新分支：

```bash
git fetch origin tag archive/20260905/feature/agent-visible-fire-closed-loop
git worktree add -b recover/agent-ncnn ../uavfire-agent-ncnn archive/20260905/feature/agent-visible-fire-closed-loop
```

最终清理证据保存在本机 `/Users/likewang/uavfire-branch-audit-20260905/final-branch-retirement/`。本阶段只整理 Git 引用并更新说明，没有改动业务代码、打包或部署。

## 第一阶段分支清理（历史记录）

GitHub 分支从 6 个减至 3 个，删除：

- `claude/debug-code-issues-TvDw6`
- `feature/cockpit-real-data-layout`
- `feature/m300-model-adaptation`

保留：

- `main`：统一主线。
- `feature/fire-precision-and-realtime-detection`：根目录仍在该分支，保留 20 个已跟踪修改和 5 个未跟踪文件；暂不处置该工作现场。
- `feature/agent-visible-fire-closed-loop`：保留 69 个独有提交的 NCNN 替代方案，本轮没有合入。

本地分支从 9 个减至 2 个，保留 `main` 和根目录的旧火情分支；删除上述已合入的 cockpit、M300，以及 `feat/msdk-sample-tools`、`feat/wayline-l1-l2`、`fix/flight-page-disconnect` 和两个 PoC 分支。

两个 PoC 在删除前重新核验：Pilot 2 PoC 全部 4 个提交有等价补丁；Cloud SDK PoC 的 3 个业务提交已有等价补丁，剩余 2 个提交只含 `docs/` 材料。原始 HEAD 均已由远端标签保留。

## 可恢复记录

第一阶段的 10 个 annotated tag 已推送到原私有 GitHub 仓库，并逐项核对标签解引用后的 commit；最终清理另推送了上文的 3 个归档标签：

| 标签（共同前缀 `archive/20260905/`） | 提交 |
| --- | --- |
| `main-before-m300-integration` | `c5310fc` |
| `local-main-before-m300-integration` | `9c83f5e` |
| `merged-m300` | `2ee80d7` |
| `poc/pilot2-composite-stream` | `a725aa2` |
| `poc/cloud-sdk-dual-stream-rc` | `7c4a05c` |
| `merged/feature/cockpit-real-data-layout` | `b5d63ce` |
| `merged/claude/debug-code-issues-TvDw6` | `97ecc8c` |
| `merged/feat/msdk-sample-tools` | `2910700` |
| `merged/feat/wayline-l1-l2` | `a8db540` |
| `merged/fix/flight-page-disconnect` | `5ec3689` |

远端删除采用原子操作并校验预期 SHA，避免删除已被他人更新的分支。上述标签保存源码历史；根目录未提交文件另有清单、SHA-256 和 binary patch，不由标签保存。

## M4T 兼容性判断

**M4T 的源码支持仍在，适合作为同一主线支持的机型；需要使用与新后端匹配的 Agent 和部署配置。现有证据不足以宣称旧 M4T 现场可以无条件原样升级。**

| 路径 | 当前代码与验证 | 判断 |
| --- | --- | --- |
| SDK 与安装目标 | MSDK 仍为 `5.18.0`，与旧 `b47042b` 基线相同；applicationId、minSdk 26、arm64-v8a 未因 M300 改变 | 未发现本次整合把原 M4T 安装目标排除 |
| 飞机与控制器识别 | `RealMsdkKeyValueClient` 保留 Matrice 4 系列到 M4T 的归一化，识别 `RC_PLUS_2` | M4T/原控制器识别路径保留；实际 SDK 返回值需设备确认 |
| 相机与载荷 | 非 M300 且没有 H20/H30 外挂载荷时走内置相机能力路径，选中位置为 0 / LEFT_OR_MAIN | M4T 不要求安装 M300 的外挂相机 |
| M300 专用门禁 | 控制器要求、M300 自动闭环开关仅在 M300 路径应用；后端和前端的 Zenmuse 选择只针对 M300/M350 | 不会仅因未开启 M300 自动闭环而屏蔽 M4T |
| 航线规划和下发 | 前端仍提供 M4T 选项；后端保留 M4T 相机、KMZ 构建和 Agent 运行时适配 | 本轮 M4T 生成、导入、下发相关回归通过 |
| 航线在线条件 | 所有 Agent 下发均要求近期命令轮询；单有遥测心跳不再代表执行服务可用 | 旧配置或未运行的航线服务可能被正确拦截，需要配套联调 |
| 视频和飞行页面 | 可见光/红外切换路径保留；`AgentFlightActivity` 继承真实 DJI `DefaultLayoutActivity`，增加被动观察叠层 | 源码支持保留；UXSDK、RTMP、推理并发性能需 M4T 实机验证 |
| 双路独立直播 | `bindThermal` 仍保留 M4T 单组件限制，可见光可降级运行 | 本次整合没有将原有单组件约束变成双路同时可用 |
| 火情检测 | 新后端使用 `visible-ai-on/off`，新 Agent 对 MSDK 帧做 ONNX 推理并通过 Outbox 上报 | 需要新后端与新 Agent 配套；不能依赖旧服务端 RTSP 检测配置直接接替 |
| 小程序 | M4T 属于支持识别的 Matrice 4 机型族，但小程序与飞行控制仍有独立开关和白名单 | 不影响既有 PC/Agent 路径，也不代表小程序飞控已放行 |

### 升级时必须考虑的差异

1. **旧 APK 的火情命令不兼容。** 对比旧火情基线 `b47042b`，其 `DualStreamSessionManager` 没有 `visible-ai-on/off`，未知命令返回 `unsupported-action`。如果 M4T 仍运行该旧系列 APK，只升级后端会导致监测无法正常启动。应使用与当前 main 匹配的 Agent；本轮未读取任何已安装 APK，因此不推定现场具体版本。
2. **部署地址与认证必须匹配。** 当前 Agent 配置指向 `192.168.0.100`。旧 M4T 若连接其他服务器，应按真实部署地址构建或配置。证据和 Agent 火情事件接口默认要求 HTTPS、认证及防重放信息；不要将网络/认证失败误判为机型不支持。
3. **新启动权限需要完整授权。** 当前版本增加运行前权限检查，包括 `READ_PHONE_STATE`；应在遥控器首次启动时检查授权结果，避免把权限缺失导致的未启动误判为断连。
4. **试用时间对 M4T 同样生效。** 当前前端、后端、Agent 均在北京时间 `2026-10-01 00:00` 到期。后端会停止，Agent 会停止服务。用户本轮明确要求整合现有代码，本次没有更改此前试用策略；长期交付仍需单独确定版本授权配置。
5. **推理性能没有 M4T 新证据。** 当前配置使用 `visible1088` ONNX profile。模型、帧龄、内存、实时画面与推理并发，应在实际 M4T/遥控器组合上复核；不能以 M300 代码合并和 JVM 测试代替。

### 现场验证建议

部署新主线前后，保留 APK 与后端/前端版本、配置和数据库迁移记录。在地面先核验 M4T 身份、遥测、载荷、权限、Agent token/命令轮询、直播及 KMZ 导入。再验证静态火焰/烟雾样本、证据上传和事件回执，以及 UXSDK + RTMP + ONNX 连续运行。暂停/恢复、激光定位和受控飞行另行验证。

这是后续验收建议，不表示本轮已经进行这些设备操作。当前 `adb devices -l` 没有连接设备；没有安装、重启遥控器应用、发送飞行命令或改变现场服务。

## 本轮验证

被测试的业务源码为 `2ee80d7`。快进合并未改变其文件内容，随后仅补充文档。

| 项目 | 结果 |
| --- | --- |
| 后端 `mvn -B -pl uavfire -am test` | 483 项通过，0 失败/错误/跳过 |
| Agent `:app:testDebugUnitTest --rerun-tasks` | 256 项通过，0 失败/错误/跳过；真实 DJI UXSDK，45 个 task 全部执行 |
| 前端 63 个 Node 测试文件 | 346 项，322 通过，24 失败；失败位置与整合前清单一致 |
| 小程序 `npm test` | 4 项通过 |
| ADB 设备 | 无设备连接，不能开展真机验证 |

本轮没有重新生成 APK 安装包、Windows 安装包或现场部署。测试产物和日志不作为已部署版本。

关键测试包括 `m4tKmzHasPilot2EnumValues`、`executeAgentWaylineShouldNormalizePilotM4tKmzBeforeDispatch`、`createPublishedWaylineShouldImportM4tKmzWithPilotPayload89` 和 `bindThermal_reportsM4tSingleComponentLimitationInsteadOfMsdkWideLimitation`。

本机原始证据：`/Users/likewang/uavfire-branch-audit-20260905/main-integration/`，包含前后引用、归档计划、推送与删除日志、根工作区 SHA-256/补丁、测试日志和结果清单。前端 24 项既有失败仍需后续逐项处理，不列为本轮兼容性验收通过。
