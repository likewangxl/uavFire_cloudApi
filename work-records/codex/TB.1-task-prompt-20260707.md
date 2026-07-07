# Codex 任务书 TB.1：修复 T-B 空间去重合并删除的火级升级通知契约

## 背景（评审发现的回归）

你在 T-B 中重写 `mergeIntoExisting` 时删除了旧合并逻辑的三个行为，其中火级升级通知是**前端依赖的既有契约**，必须恢复：

- 前端指挥舱 `frontend/src/pages/page-web/projects/leadership-cockpit.vue:1848-1909` 依靠 `notificationVersion` 递增在火情升级时重新弹通知；
- 设计文档 `docs/superpowers/specs/2026-05-24-fire-event-latest-history-design.md:11`："通知仍只在等级升级时递增 `notification_version`，避免列表降级导致重复通知"；
- 当前 T-B 后的代码：合并时 `fireLevel/confidence` 保持旧值、`notificationVersion` 永不递增 → 火从 LOW 烧到 HIGH 指挥舱不会再收到任何升级通知。

## 仓库与范围

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`，分支 `feature/fire-precision-and-realtime-detection`
- 只改：`backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java` 和 `backend/uavfire/src/test/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImplMergeTest.java`
- **不要动**你在 T-B 已实现且验收通过的部分：空间查询（边界盒+Haversine）、温度取 max、图片仅补空、geoErrorRadiusM 择优、lastSeenTime/reportCount/lastSourceEventId 更新、开关与配置。
- 不执行 git commit。

## 要求（恢复 git 基线 7815e04 中被删的旧语义，与新逻辑共存）

在 `mergeIntoExisting` 中恢复：

1. 恢复 `fireLevelRank` 私有方法（HIGH=3/MEDIUM=2/LOW=1/其他=0，原样）。
2. `boolean levelUpgraded = fireLevelRank(param.getFireLevel()) > fireLevelRank(existing.getFireLevel())`（在覆盖 fireLevel 之前计算）。
3. `param.getConfidence() != null` → 覆盖 `confidence`（旧行为）。
4. `param.getFireLevel()` 非空非 blank → 覆盖 `fireLevel`（旧行为，含降级覆盖——通知只对升级触发，见设计文档）。
5. notificationVersion（旧行为原样）：`levelUpgraded` → `existing.setNotificationVersion(旧 == null ? 2 : 旧 + 1)`；否则若旧为 null → 置 1。
6. 合并分支的响应：`notificationRequired = levelUpgraded`，`notificationReason = levelUpgraded ? "LEVEL_UPGRADED" : "MERGED_NEARBY"`（MERGED_NEARBY 是 T-B 新语义，保留作为未升级时的 reason）。`mergeIntoExisting` 需要把 `levelUpgraded` 返回给调用方（返回 boolean 或小结果对象均可）。

## 测试要求（加进 FireEventServiceImplMergeTest）

1. `merge_upgrades_level_bumps_notification_version`：existing LOW(version=1) + 新报 HIGH → fireLevel=HIGH、notificationVersion=2、notificationRequired=true、reason=LEVEL_UPGRADED。
2. `merge_same_level_keeps_notification_version`：同级复报 → version 不变、notificationRequired=false、reason=MERGED_NEARBY。
3. `merge_downgrade_overwrites_level_without_notification`：existing HIGH + 新报 LOW → fireLevel=LOW、version 不变、notificationRequired=false。
4. `merge_updates_confidence_from_new_report`。
5. 既有用例如断言 reason/字段与新语义冲突，按上述语义修正断言（说明每处修改原因）。

## 验证（必须实际执行并粘贴 Tests run 数字）

```bash
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\backend"
mvn -pl uavfire test
```

全量通过（当前基数 394 + 新增），`SpringContextSmokeTest` 通过。

## 完成后输出

改动 diff 摘要、测试运行数字、与本任务书的偏差说明（如有）。
