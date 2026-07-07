# Codex 任务书 T-B：后端 fire_event GPS 空间去重合并

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线 commit 7815e04；**你自己不要执行 git commit**）
- 只允许改动 `backend/uavfire/` 下本任务书列出的文件。

## 背景

森林火情巡逻中，同一火点会被反复检测：无人机航线折返再次经过、多架无人机先后飞过、抵近确认作业二次上报——目前后端只有"同 eventId 去重"（`FireEventServiceImpl.java:302-316`），每次都会新建 fire_event，重复报警。本任务增加**空间去重合并**：同 workspace、活跃时间窗内、距离 ≤ 去重半径的已有事件，合并更新而非新建。语义是"合并"不是"丢弃"——复报要刷新事件的活跃度、计数、温度与定位质量。

## 现状锚点（已核实）

- 创建入口：`backend/uavfire/src/main/java/com/yx/uavfire/fc100/event/service/impl/FireEventServiceImpl.java`
  - :302-316 同 eventId 去重分支（返回 reason=`"EXISTING_EVENT_ID"` 的 `FireEventCreateResponse`）
  - :318 附近 `long now = clock.now();` 后进入新建路径
- 实体 `fc100/event/model/entity/FireEventEntity.java`：已有 `workspaceId`、`lat`、`lng`、`alt`、`geoMethod`、`geoErrorRadiusM`、`geoQuality`、`geoSourceTs`、`thermalTemperature`、`thermalImageUrl`、`visibleImageUrl`、`lastSeenTime`、`reportCount`、`lastSourceEventId`、`status`、`deleted`、`updateTime` 字段（MyBatis-Plus，表 `fire_event`）
- 状态枚举 `fc100/event/model/enums/FireEventStatus.java`：NEW / CANDIDATE / LOW_CONFIDENCE / MISSION_CREATED / IGNORED
- Mapper：`fc100/event/dao/FireEventMapper.java`（MyBatis-Plus BaseMapper，QueryWrapper 内联查询即可，无需 XML）
- 配置风格参考 `uavfire/src/main/resources/application.yml:228-230`（`${ENV_VAR:default}` 形式）
- 测试风格参考：`uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java`；`SpringContextSmokeTest.java` 是完整 ApplicationContext 冒烟测试，新配置项必须有默认值否则装配挂

## 实现要求

### 配置（application.yml，`fc100` 相关块下，若无合适父块则新建）

```yaml
fc100:
  fire-event:
    dedup-enabled: ${FIRE_EVENT_DEDUP_ENABLED:true}
    dedup-radius-m: ${FIRE_EVENT_DEDUP_RADIUS_M:40}
    dedup-active-window-ms: ${FIRE_EVENT_DEDUP_WINDOW_MS:1800000}   # 30min
```

注入方式对齐该 Service 现有配置注入风格（@Value 或 @ConfigurationProperties，看现状选一致的）。

### 逻辑（FireEventServiceImpl 创建路径）

插入位置：eventId 去重分支之后、新建事件落库之前。

1. 前置：`dedup-enabled=true` 且 param 的 lat/lng 均非 null，否则跳过（走原路径，逐字不动）。
2. 候选查询（新增私有方法 `findNearbyActiveEvent`）：
   - 条件：`workspace_id` 相同、`deleted=0`、`status != 'IGNORED'`、`last_seen_time >= now - window`、lat/lng 非空；
   - 边界盒预筛：`lat BETWEEN param.lat ± radius/111320.0`，`lng BETWEEN param.lng ± radius/(111320.0*cos(toRadians(param.lat)))`（QueryWrapper 完成，避免全表拉回）；
   - 内存中对候选算 Haversine 距离，取最近且 ≤ `dedup-radius-m` 者；无则返回 null。
3. 命中合并（新增私有方法 `mergeIntoExisting`）：
   - `lastSeenTime = now`；`reportCount = (旧值 null ? 1 : 旧值) + 1`；`lastSourceEventId = param.getEventId()`；`updateTime = now`；
   - `thermalTemperature = max(旧, 新)`（null 安全，一方为 null 取另一方）；
   - 定位择优：新报 `geoErrorRadiusM` 严格更小（null 视为无穷大）时，覆盖 `lat/lng/alt/altitudeReference/geoMethod/geoErrorRadiusM/geoQuality/geoSourceTs`；否则坐标不动；
   - `thermalImageUrl` / `visibleImageUrl`：仅旧值为空且新值非空时补上；
   - `status`、`confidence`、`fireLevel` 不动；
   - `eventMapper.updateById(...)` 落库；
   - 响应：复用 `FireEventCreateResponse` 结构，reason = `"MERGED_NEARBY"`，事件 id 用已有事件，活跃任务号复用 :306-315 分支同样的 `findActiveMissionNo` 逻辑。
4. 未命中：原创建路径逐字不动。
5. 并发：不引入分布式锁；方法注释注明"单实例部署前提，多实例需加锁"（YAGNI）。

### 禁改范围

- 不改 eventId 去重分支的行为与优先级（空间去重必须在它之后）。
- 不改 `DualStreamServiceImpl`、状态机、mission 创建逻辑、任何 agent 端文件。
- 不加数据库索引/DDL（数据量小；在 `findNearbyActiveEvent` 注释标注后续可加 `idx_fire_event_workspace_lastseen`）。
- 不引入新依赖。

## 测试要求

跟随现有 fire event 相关测试的组织方式（先找 `FireEventServiceImpl` 的现有测试文件扩展之；确无则新建，风格对齐 `DualStreamServiceImplTest`，纯 Mockito 单测优先，不起 Spring 上下文）。用例：

1. `create_merges_into_nearby_active_event`：30m 内活跃事件 → 合并，reportCount+1，reason=MERGED_NEARBY，无 insert 调用。
2. `create_skips_merge_beyond_radius`：60m 外 → 新建。
3. `create_skips_merge_when_window_expired`：lastSeenTime 超 30min → 新建。
4. `create_skips_merge_for_ignored_event`：附近事件 status=IGNORED → 新建。
5. `merge_keeps_better_geo`：旧 geoErrorRadiusM=5、新=20 → 坐标不覆盖；旧=20、新=5 → 覆盖。
6. `merge_takes_max_temperature`（含一方 null 的分支）。
7. `dedup_disabled_preserves_current_behavior`：开关关 → 直接新建。
8. `create_without_coordinates_skips_spatial_dedup`：param 无坐标 → 直接新建。
9. `event_id_dedup_takes_precedence`:同 eventId 且同坐标 → 走 EXISTING_EVENT_ID 分支,不触发空间查询。

## 验证方式（必须实际执行并粘贴结果）

```bash
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\backend"
mvn -pl uavfire test
```

385 个基线测试 + 新增用例全部通过，`SpringContextSmokeTest` 通过。

## 验收标准

- [ ] 上述用例全绿，全量 `mvn -pl uavfire test` 无回归、无跳过。
- [ ] 新配置项均有默认值。
- [ ] 改动文件严格限于：`FireEventServiceImpl.java`、`application.yml`、测试文件（如需 DTO 加 reason 常量可改 `FireEventCreateResponse` 所在文件并说明）。
- [ ] 不执行 git commit。
- [ ] 完成后输出：改动文件列表、测试运行摘要（Tests run 数字）、与任务书的偏差说明（如有）。
