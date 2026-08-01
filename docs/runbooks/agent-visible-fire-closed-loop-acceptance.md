# Agent 可见光火情闭环验收手册

状态日期：2026-08-01（Asia/Shanghai）

## 1. 发布结论

当前结论：`NOT_READY_DEFAULT_OFF`。

RC Plus 当前不可用。NCNN 自动化仅为 provisional 开发证据，不是 visible-960 RC Plus
验收替代品。下列设备、台架和飞行门禁均为 `BLOCKED_PENDING_DEVICE`，检测必须保持
默认关闭并 fail-closed。

## 2. 生产边界

生产仅运行 backend、frontend、ZLMediaKit 和 RC Plus Agent。ZLMediaKit 是 display-only。
Agent 是检测、暂停、悬停、ROI、激光和恢复的唯一实时执行端。backend 只处理可靠上报、
事件、告警、任务和人工控制。

离线模型目录可以用于导出与基准，但不得被启动、健康检查、建立隧道、创建生产检测任务
或作为回滚 fallback。

## 3. 证据目录

每次验收新建不可复用目录：

```bash
export ACCEPTANCE_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD)"
export ACCEPTANCE_DIR="$PWD/artifacts/acceptance/agent-visible-fire/$ACCEPTANCE_ID"
mkdir -p "$ACCEPTANCE_DIR"/{automated,device,soak,network,laser,bench,flight,rollback}
git rev-parse HEAD >"$ACCEPTANCE_DIR/commit.txt"
git status --short >"$ACCEPTANCE_DIR/worktree-status.txt"
```

`artifacts/acceptance/` 不提交 Git；最终报告只记录时间、命令、测试数量、结果哈希和绝对
日志路径。设备截图、APK、SQLite、logcat、backend、浏览器和媒体日志都保存在该目录。

## 4. 自动化门禁

必须从干净 feature worktree 运行。不得因第一项失败而把后续项目写成“通过”；可独立执行
后续命令以收集完整基线，但最终状态仍为失败。

```bash
./scripts/agent-visible-fire-static-check.test.sh \
  2>&1 | tee "$ACCEPTANCE_DIR/automated/static-policy.log"

(cd ai-service && ./.venv/bin/python -m pytest -q) \
  2>&1 | tee "$ACCEPTANCE_DIR/automated/offline-tooling-pytest.log"

(cd backend/uavfire && mvn -q test) \
  2>&1 | tee "$ACCEPTANCE_DIR/automated/backend-test.log"

(cd rcplus-msdk-agent && \
  : "${NCNN_ANDROID_NDK_DIR:?set NCNN_ANDROID_NDK_DIR to the reviewed Android NDK}" && \
  ./gradlew --console=plain --rerun-tasks \
  -PncnnAndroidNdkDir="$NCNN_ANDROID_NDK_DIR" \
  :fire-detector-benchmark:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug) \
  2>&1 | tee "$ACCEPTANCE_DIR/automated/agent-build-with-local-ndk.log"

(cd frontend && node --test src/pages/page-web/projects/__tests__/*.test.mjs scripts/*.test.mjs) \
  2>&1 | tee "$ACCEPTANCE_DIR/automated/frontend-test.log"

(cd frontend && npm run build) \
  2>&1 | tee "$ACCEPTANCE_DIR/automated/frontend-build.log"

while IFS= read -r file; do
  shasum -a 256 "$file"
done < <(rg --files --no-ignore "$ACCEPTANCE_DIR/automated" \
  | rg -v '/SHA256SUMS$' | LC_ALL=C sort) \
  >"$ACCEPTANCE_DIR/automated/SHA256SUMS"
```

### 2026-08-01 当前自动化记录

代码验收提交：`05829aac47cc89ba5ee15b0e8b0be69b36c633a8`；开始时间
`2026-08-01T10:17:23Z`。执行前 `git status --short` 为空。日志目录：
`/Users/likewang/uavfire/.worktrees/agent-fire-detection/artifacts/acceptance/agent-visible-fire/20260801T101718Z-05829aa/automated/`。

| 门禁 | 结果 |
| --- | --- |
| 生产静态策略 fixture + 当前 checkout | 通过 |
| 离线工具 pytest | 199 通过、1 跳过、0 失败 |
| backend Maven | 514 通过、0 失败 |
| Agent JVM | 491 通过、0 失败 |
| detector benchmark JVM | 76 通过、0 失败 |
| Agent debug APK | 构建成功，93 个 task 全部强制执行 |
| frontend Node tests | 303 通过、0 失败 |
| frontend production build | 成功，3009 modules transformed |

首次使用 `--rerun-tasks` 的 Agent 打包因为没有提供 Android NDK 路径而真实失败；日志保留在
`agent-build.log`。随后显式传入已安装的 NDK，并在 `agent-build-with-local-ndk.log` 中完成
全量强制构建。此项归类为构建环境前置条件，不掩盖为缓存通过。自动化门禁结论为
`AUTOMATED_PASS`，日志和产物 SHA-256 见同目录校验清单。

之前 frontend 的 19 项失败均为断言仍指向已退役的 UI/控制入口；提交 `775ced7` 将断言对齐
当前生产控制，不作为“既有失败”忽略。设备门禁仍全部阻塞，因此总体发布结论不变，仍为
`NOT_READY_DEFAULT_OFF`。

最终 clean-HEAD 自动化复跑的精确计数、时间和 SHA-256 固化在
[`docs/evidence/2026-08-01-agent-visible-fire-automated-acceptance.md`](../evidence/2026-08-01-agent-visible-fire-automated-acceptance.md)。

## 5. 设备门禁（严格顺序）

后一个阶段只有在前一个阶段通过后才允许开始。

| 顺序 | 门禁 | 当前状态 |
| --- | --- | --- |
| 1 | 设备安装 APK、版本、回拉 APK SHA-256、manifest/runtime/model/hash | `BLOCKED_PENDING_DEVICE` |
| 2 | visible-960 PyTorch/TFLite/NCNN 固定集逐样本对比 | `BLOCKED_PENDING_DEVICE` |
| 3 | fire 和 smoke 静态目标确认、ROI 与类别 | `BLOCKED_PENDING_DEVICE` |
| 4 | UXSDK + RTMP + inference 正式 APK 30 分钟 soak | `BLOCKED_PENDING_DEVICE` |
| 5 | 初报 `<1s`、驾驶舱提醒 `<1.5s` | `BLOCKED_PENDING_DEVICE` |
| 6 | 实激光三样本 PRECISE、同事件更新、安全恢复 | `BLOCKED_PENDING_DEVICE` |
| 7 | 激光失败 DEGRADED_OSD、无火点坐标、安全恢复 | `BLOCKED_PENDING_DEVICE` |
| 8 | 断网、重复/迟到、backend/Agent 重启、人工接管、恢复失败 | `BLOCKED_PENDING_DEVICE` |
| 9 | 无桨台架安全验证 | `BLOCKED_PENDING_DEVICE` |
| 10 | fire 和 smoke 受控飞行 | `BLOCKED_PENDING_DEVICE` |

每个步骤记录：UTC/本地时间、操作者、设备 SN、任务 ID、event/session ID、APK/model/runtime
哈希、原始日志路径、截图路径、数据库/Outbox 查询结果和判定人。

## 6. 30 分钟 soak 判定

- UXSDK、可见光解码、RTMP 和 NCNN 同时运行；
- 推理 P95 `≤200ms`，有效推理率 `≥5 FPS`，帧龄 P95 `≤300ms`；
- 无崩溃、ANR、持续内存增长或 UI/MSDK/飞控卡顿；
- 最后 5 分钟 P95 相对前 5 分钟退化 `≤20%`；
- 保存 `logcat`、内存、推理统计、ZLM 媒体列表和视频证据。

## 7. 上报与异常判定

- 首报只含视觉确认，不等待图片、悬停或激光；
- ACK 必须对应相同 `eventId/sequence` 且事务已经提交；
- 网络恢复后按 sequence 重放，不丢事件、不重复通知；
- PRECISE 保留三个原始激光样本；
- DEGRADED_OSD 只保留 aircraft 坐标，不生成 fire 坐标、精确图标或自动航线；
- task/drone identity 变化、人工接管或恢复证据不足必须进入人工保持；
- fire 与 smoke 均需验证完整定位和恢复，smoke 页面必须提示可能不是实际起火源。

## 8. 无桨台架与受控飞行

无桨台架必须证明暂停/恢复命令身份、优先级、幂等、超时、人工接管和 fail-closed。台架
通过并完成书面复核后，才可申请受控飞行。受控飞行须有安全员、隔离区域、终止条件和
人工接管方案。任一门禁失败立即停止，状态记为 release-blocking。

## 9. 回滚步骤和证据

触发条件：误检不可控、推理/UX 卡顿、存储/Outbox 故障、任务身份变化、悬停或恢复不确定、
激光无法安全关闭、人工接管或任一门禁失败。

1. 停止监测并确认 Agent detector heartbeat 非运行；无法确认时按失败关闭处理；
2. 保留 DJI 人工飞行控制，禁止再次自动暂停或恢复；
3. 保存当前 APK、manifest、Agent SQLite、logcat、backend/通知日志、浏览器日志和 ZLM 日志；
4. 飞机安全落地后才允许回退 APK；
5. 回退版本仍保持 detector 默认关闭；
6. backend、frontend、ZLMediaKit 可继续提供业务和显示，但不得启用后端自动定位编排；
7. 生成 `rollback/decision.md`，写明时间、触发项、操作者、哈希、证据路径和恢复条件。

回滚绝不通过启动另一正式 detector 或恢复旧任务生命周期来完成。

## 10. 最终放行条件

只有自动化、设备、异常、实激光、无桨台架和 fire/smoke 受控飞行全部通过，且证据哈希
可复核，才能评审将 detector 改为默认启用。在此之前结论固定为
`NOT_READY_DEFAULT_OFF`。
