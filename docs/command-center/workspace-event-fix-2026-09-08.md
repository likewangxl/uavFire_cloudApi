# 火情事件工作区归属修复（2026-09-08）

## 问题与展示口径

本机 8080/fire-events 修复前仅显示 3 条 COMMAND_CENTER_QA 验收事件，110 条现场事件不可见。原因是 Agent/旧识别接口创建参数没有工作区，FireEventServiceImpl 直接使用 DEFAULT；新页面按登录工作区筛选。manage_device 中两架现场飞机都已绑定 e3dea0f5-37f2-4d79-ae58-490af3228069，DEFAULT 也不是 manage_workspace 中的有效业务工作区。

展示当前工作区内最近 200 条记录，后端按 last_seen_time、create_time 倒序，前台可见时每 15 秒刷新。默认仅展示现场业务记录；3 条指挥台验收数据保留在库中，通过“显示验收记录”查看并标为“验收测试”。运行总览排除同一来源的验收记录。日期展示完整年月日和时间；不把刷新时间当发现时间。该页是近期列表，不等同于全量历史分页。

## 修复实现

- 创建新事件前，根据 device_sn 查询持久化 manage_device 已绑定设备，只在工作区缺失、空白或 DEFAULT 时回填唯一设备工作区。
- 在空间去重锁、候选匹配、主表及历史写入前解析，保证整个写入链路的工作区一致。
- 明确指定的工作区不被改写；未知、未绑定或归属冲突设备不猜测归属。列表继续按工作区过滤。
- 一次性 SQL：backend/sql/migrations/2026-09-08-fire-event-device-workspace.sql。仅对有唯一有效绑定的非删除占位工作区事件修复；同步关联历史及必要的任务工作区，不改时间、证据、坐标、状态和审核说明。

## 本机数据结果

目标数据库 127.0.0.1:3306/cloud_sample，数据目录 /usr/local/var/mysql/。

| 表 | 总行数 | 修复工作区行数 |
| --- | ---: | ---: |
| fire_event | 113 | 110 |
| fire_event_history | 144 | 138 |
| fc100_fire_mission | 487 | 0 |

逐行、逐字段对比确认只有 workspace_id 变化。SQL 第二次执行变化数均为 0。原始行备份到 fire_event_bak_20260908_workspace、fire_event_history_bak_20260908_workspace、fire_mission_bak_20260908_workspace；原有备份表未变。

最新现场事件是内部 ID 855，2026-09-03 18:10:31.541（北京时间），飞机 1ZNBJ9600C00C7，来源 DJI_AGENT。9 月 6 日的三条为验收数据，不是近期实机上报。

## 验证

- 后端 FireEventWorkspaceTest + 原事件去重/决策/OSD 回填测试：73/73 通过。
- 前端指挥台行为测试：21/21 通过；包括默认排除验收记录、显式展示验收及保持已有倒序。
- 修改页面和事件模型 ESLint 通过；正式 Vite 构建通过，保留既有 chunk 体积提示。
- 已更新本机后端和 8080 正式前端构建；新后端保留原进程参数、环境变量和工作目录，运行 PID 34424。
- 登录工作区 API 返回 113 条，其中 110 条现场记录；不相关工作区返回空列表。
- 事件 855 详情/历史可读；其证据图经 6789、8080 两个地址均返回 HTTP 200、image/jpeg、145041 字节。
- 浏览器默认列表首条为 855，显示 2026/9/3 18:10:31、现场识别图片、82.5% 置信度及真实时间轴；待复核计数 110。
- 首轮修复构建已完成浏览器截图和 DOM 验证：110 张事件卡片、首条 855、证据图加载成功、无横向溢出。随后补充验收记录开关清理深链的小修复并再次构建成功；最终冷加载及开关交互复测受浏览器工具 CDP 超时阻断，未冒充通过。两端 HTTP 服务仍正常。
- 未创建新的现场事件、未操作人工复核或飞行指令。没有在线设备，本次不包含新的实机上报验收。

日志与数据验证摘要位于 evidence/workspace-fix-*；运行日志和构建产物不应提交。

## 回退

代码以本次增量为单位回退，不覆盖既有指挥台未提交改动。数据库备份保留，若需要回退归属，先核对修复后是否发生设备转移，再事务内按 id 连接对应备份，仅恢复 workspace_id，不能整行替换覆盖后续业务变化。例如：

```sql
START TRANSACTION;
UPDATE fire_event e JOIN fire_event_bak_20260908_workspace b ON b.id=e.id
SET e.workspace_id=b.workspace_id
WHERE e.workspace_id='e3dea0f5-37f2-4d79-ae58-490af3228069';
UPDATE fire_event_history h JOIN fire_event_history_bak_20260908_workspace b ON b.id=h.id
SET h.workspace_id=b.workspace_id
WHERE h.workspace_id='e3dea0f5-37f2-4d79-ae58-490af3228069';
COMMIT;
```

本次任务工作区更新为 0，任务表无需回退。以后重新执行若确实修复了任务，必须同步按对应备份恢复任务工作区。
