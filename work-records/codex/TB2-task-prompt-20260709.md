# Codex 任务书 TB.2：GPS 空间去重三项优化（并发防护 / 自适应半径 / 状态感知窗口）

## 仓库与范围

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`，分支 `feature/fire-precision-and-realtime-detection`（基线 `75ae77f`，后端测试基数 409）
- **只改 `backend/uavfire/`**。agent、ai-service 零改动。不执行 git commit。

## 背景

空间去重（T-B/TB.1，`FireEventServiceImpl.findNearbyActiveEvent`/`mergeIntoExisting`）有三个已评审确认的缺口：

1. **并发竞态**：两机同时首报同一火点，"查询→新建"无并发防护，可能建出两个事件且此后永久分裂（集群场景高危）；
2. **半径固定**：40m 死值——事件被激光升级到 5m 误差后，40m 会把 ~30m 外第二个真实火点错误吞并；两边都是 30m 误差的 RayDem 报文时 40m 又可能不足；
3. **窗口不看状态**：MISSION_CREATED（已建任务执行中）的事件超 30min 无复报后，复报会新建重复事件。

## 现状锚点

- `fc100/event/service/impl/FireEventServiceImpl.java`：`create()` 入口（eventId 去重 → `findNearbyActiveEvent(param, now)` → `mergeIntoExisting`）；候选查询含 bbox 预筛 + `.ne("status", IGNORED)` + `.ge("last_seen_time", activeSince)` + Haversine 取最近 ≤ `fireEventDedupRadiusM`
- 配置：`application.yml` `fc100.fire-event` 块（dedup-enabled/dedup-radius-m=40/dedup-active-window-ms）
- `FireEventMapper`（MyBatis-Plus BaseMapper）；测试 `FireEventServiceImplMergeTest`（纯 Mockito）
- Clock 注入已有（`fc100/common/Clock`）

## 实现要求

### 1. 并发防护：MySQL 命名锁包住"去重查询→合并/新建"临界区

- `FireEventMapper` 新增两个注解 SQL：
  ```java
  @Select("SELECT GET_LOCK(#{name}, #{timeoutSeconds})")
  Integer acquireNamedLock(@Param("name") String name, @Param("timeoutSeconds") int timeoutSeconds);
  @Select("SELECT RELEASE_LOCK(#{name})")
  Integer releaseNamedLock(@Param("name") String name);
  ```
- `create()` 中：进入去重逻辑前 `acquireNamedLock("fire_event_dedup:" + workspaceId, 3)`；`finally` 中 release。
- **可用性优先**：取锁返回非 1（超时/异常）→ `log.warn` 后**继续无锁执行**（现行为），绝不因锁失败拒绝上报。
- 锁范围覆盖：空间查询 + 合并/新建落库；eventId 去重可留在锁外（幂等）。
- 注释说明：锁在 MySQL 侧，天然覆盖未来多实例部署；单连接内 GET_LOCK 语义与连接池的注意点（同一事务/连接内 release）——**确认 acquire 与 release 走同一连接**（MyBatis 默认每次调用可能取不同连接！须在同一事务内执行，方法加 `@Transactional` 或用现有事务边界，写明你的验证依据）。

### 2. 自适应合并半径

- 判据从固定 `distance <= dedupRadiusM` 改为 `distance <= adaptiveThreshold(param, candidate)`：
  ```
  errOf(x) = x.geoErrorRadiusM ?: null
  两者都有误差半径 → threshold = clamp(err(param) + err(candidate) + marginM, minM, maxM)
  任一为 null → threshold = dedupRadiusM（现行为兜底）
  ```
- 新配置（`fc100.fire-event` 块，环境变量可覆盖）：`dedup-radius-margin-m: 10`、`dedup-radius-min-m: 15`、`dedup-radius-max-m: 60`
- **bbox 预筛窗口改用 `dedup-radius-max-m`**（否则精查半径可能大于预筛半径漏候选）。
- 语义示例（写进测试）：激光事件(5m)+激光新报(5m) → 门限 clamp(20,15,60)=20 → 25m 外第二火点**不合并**；RayDem(30m)+RayDem(30m) → clamp(70,15,60)=60 → 50m 同火**合并**。

### 3. MISSION_CREATED 窗口豁免

- 候选查询的窗口条件改为：`last_seen_time >= activeSince OR status = 'MISSION_CREATED'`（MyBatis-Plus `.and(w -> w.ge(...).or().eq("status", ...))`，注意与既有 `.ne("status", IGNORED)` 的组合正确性）。
- 内存精筛处同步放行 MISSION_CREATED 的过期 lastSeenTime。

## 测试要求（扩展 `FireEventServiceImplMergeTest`，纯 Mockito）

1. `adaptive_radius_precise_pair_rejects_second_fire`：事件 err=5、新报 err=5、距离 25m → 新建（不合并）。
2. `adaptive_radius_coarse_pair_merges_wide`：err=30+30、距离 50m → 合并。
3. `adaptive_radius_null_error_falls_back_to_base`：任一 err 缺失、距离 35m → 按 40m 基础半径合并。
4. `adaptive_radius_clamped_to_max`：err=50+50、距离 70m → 门限被 clamp 到 60 → 不合并。
5. `mission_created_event_merges_beyond_window`：MISSION_CREATED + lastSeenTime 超窗 2h、距离 20m → 仍合并。
6. `stale_new_event_outside_window_not_merged`：NEW + 超窗 → 新建（现行为不变）。
7. `dedup_lock_acquired_and_released`：verify acquire/release 各一次（含新建与合并两条路径）。
8. `lock_timeout_proceeds_without_blocking`：acquire 返回 0 → 流程照常、有 warn、release 不调用（或按你的实现语义断言，说明理由）。
9. 既有 409 基数全绿；`SpringContextSmokeTest` 通过（新配置有默认值）。

## 验证方式（必须实际执行并粘贴 Tests run 数字）

```bash
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\backend"
mvn -pl uavfire test
```

## 进度可见性要求

每完成一个里程碑打印：`[TB2-PROGRESS] <一句话状态>`（建议节点：命名锁实现、自适应半径实现、窗口豁免实现、测试全绿含数字）。

## 验收标准

- [ ] 三项全落实；上述用例全绿；全量无回归。
- [ ] GET_LOCK/RELEASE_LOCK 同连接问题有明确处理与说明。
- [ ] 默认配置下除三项目标行为外零差异。
- [ ] 改动严格限于 backend/uavfire/；不执行 git commit；完成报告含改动文件、测试数字、偏差说明。
