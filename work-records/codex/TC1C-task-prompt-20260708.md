# Codex 任务书 TC1-C：后端疑似火情自动派发抵近确认作业（默认关闭）

## 仓库与分支

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`
- 分支：`feature/fire-precision-and-realtime-detection`（基线含 T-B `8bf9873`、T-C `c98ad67`；**不要执行 git commit**）
- 只改 `backend/uavfire/` 下本任务书列出的文件。

## 背景

T-C 已实现 agent 端 `fire-confirmation-mission` 指令（urgent 通道，params 带 lat/lng/alt/taskId，见 `DualStreamServiceImpl.URGENT_ACTIONS` 与 agent `DualStreamSessionManager` 的命令分发）。当前只能手动触发。本任务让**后端**在收到热确认的疑似火情事件后自动派发该指令——坐标权威在后端（用解算/合并后的最优坐标），替代 agent 端用机体位置代火点的权宜方案。

## 现状锚点

- fire_event 创建/合并入口：`fc100/event/service/impl/FireEventServiceImpl.java` `create(FireEventCreateParam)`（同 eventId 去重 :302 起、空间合并分支、新建路径）。创建响应含事件 id/reason（CREATED / MERGED_NEARBY / LEVEL_UPGRADED / EXISTING_EVENT_ID）。
- 命令下发链路：`manage/service/impl/DualStreamServiceImpl.java` —— 命令入队/urgent 判定（URGENT_ACTIONS :73-79 已含 `fire-confirmation-mission`）。先读该类找到**向指定设备入队一条命令**的现有方法（服务接口 `IDualStreamService` 上的对应方法），沿用之，不要绕过队列直连。
- `FireEventEntity`：`deviceSn`、`lat/lng/alt`、`status`、`linkedIncidentId`、`geoErrorRadiusM`。
- 配置风格：`application.yml` `fc100.fire-event` 块（T-B 已建：dedup-enabled 等）。
- 测试风格：`FireEventServiceImplMergeTest.java`（纯 Mockito）、`DualStreamServiceImplTest.java`。

## 实现要求

### 配置（application.yml，`fc100.fire-event` 块下追加）

```yaml
    auto-approach-enabled: ${FIRE_EVENT_AUTO_APPROACH_ENABLED:false}
    auto-approach-cooldown-ms: ${FIRE_EVENT_AUTO_APPROACH_COOLDOWN_MS:600000}   # 10min
```

### 逻辑（新增组件 `FireApproachDispatcher` 或紧凑放入 FireEventServiceImpl，选择后说明理由）

触发点：`create(...)` 成功返回**新建（CREATED）或合并（MERGED_NEARBY/LEVEL_UPGRADED）**之后（EXISTING_EVENT_ID 不触发）：

1. 前置过滤（全部满足才派发）：
   - `auto-approach-enabled = true`；
   - 事件有 lat/lng；
   - 事件 `deviceSn` 非空；
   - 事件状态不是 `MISSION_CREATED` / `IGNORED`；
   - 冷却：该事件 id 上次派发距今 ≥ `auto-approach-cooldown-ms`（内存 `ConcurrentHashMap<Long, Long>` 即可，注释注明单实例前提）。
2. 派发：通过 DualStream 现有命令入队方法向 `deviceSn` 投递 action=`fire-confirmation-mission`，params：`lat`、`lng`、`alt`（可空）、`taskId`（用事件关联的 taskId 语义——沿用 agent 上报时的 taskId 字段来源，从 param/事件中取；取不到用 `"fire-" + deviceSn` 兜底，与 agent 端 `taskIdFactory` 一致）。
3. 派发失败（异常）只记 warn 日志，**不影响** create 的返回值与事务。
4. 派发动作与结果打 info 日志（事件 id、设备、坐标、是否冷却拦截）。

### 禁改范围

- 不改 create 的既有返回值/状态机/去重合并逻辑本身（只在其成功路径后追加钩子）。
- 不改 agent 端任何文件。
- 不引入新依赖、不加数据库表；冷却状态用内存即可。

## 测试要求（纯 Mockito，风格对齐现有）

1. `auto_approach_dispatches_on_created_event`：开关开 + 新建事件带坐标 → 命令入队一次，params 含 lat/lng/taskId。
2. `auto_approach_dispatches_on_merged_event`：合并（MERGED_NEARBY）也派发。
3. `auto_approach_disabled_by_default`：默认配置 → 零派发（并断言默认值确为 false）。
4. `auto_approach_respects_cooldown`：同一事件 10min 内第二次 create/merge → 不再派发。
5. `auto_approach_skips_event_without_coordinates`。
6. `auto_approach_skips_mission_created_status`。
7. `dispatch_failure_does_not_break_create`：入队抛异常 → create 正常返回。
8. `SpringContextSmokeTest` 通过（新配置有默认值）。

## 验证方式（必须实际执行并粘贴 Tests run 数字）

```bash
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\backend"
mvn -pl uavfire test
```

全量通过（基数 396 + 新增）。

## 验收标准

- [ ] 上述用例全绿、全量无回归。
- [ ] 开关默认 false，关闭时与基线行为完全一致（有测试证明）。
- [ ] 改动文件限于：FireEventServiceImpl.java（或新增 dispatcher 类）、application.yml、必要的 Service 接口引用、测试文件。
- [ ] 不执行 git commit；完成后输出改动文件列表、测试数字、偏差说明。
