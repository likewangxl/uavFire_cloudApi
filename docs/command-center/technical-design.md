# 统一指挥台落地技术方案

日期：2026-09-06。基线：6c5e319，codex/video-bandwidth-4hd-16sd。实施目录为现有 m300-model-adaptation 工作树；根目录旧现场不变。设计依据：本任务确认的 cockpit 扩展原型。

## 1. 目标与验收原则

将原型的信息架构和核心布局落入正式 Vue 应用，接回已有 Java 服务。保留完整航线规划、KMZ、面状规划、机型/载荷参数、重复执行、投送审批与控制门禁。示例数组、模拟状态推进、固定在线数和历史图片不得成为正式页面的数据来源。

用户路径：登录 → 运行总览 → 火情列表/现场证据/时间轴 → 复核/事件处置；任务中心 → 航线库/规划 → 创建任务 → 执行/历史；视频监控 → 选飞机 → 飞机操作；灭火任务 → 审批/执行；管理 → 设备/固件/媒体/成员/地图空域。

## 2. 架构与复用边界

采用统一顶栏与二级功能导航，保留原 route name、workspace map host、Vuex 注入和 WebSocket 事件链。原 home/workspace 外壳接统一导航，而不是在新壳内重复实例化路由和地图。独立专业作业栏目和旧综合驾驶舱入口已移除；运行总览为唯一综合态势首页，/flight-control 旧地址转到视频监控。视频采用独立播放窗口，复用现有媒体端点获取、播放器和带宽租约策略。

正式火情页使用新三栏组件。现有 FireEventList 作为高级表格入口保留（含历史、测试火情、审批）；列表筛选与选择在新页中实现，事件 ID 可深链。左栏约 250px，中栏自适应，右栏约 360px；宽屏各栏独立滚动，窄屏顺序排列。图片 contain，可见光/红外只显示真实可用证据，无证据/失败展示明确状态。

航线库与规划复用 wayline.vue、PlannerWorkspace、GMap 和 WaylineMissionMonitor；新导航提供直达规划/导入/库/任务计划/执行历史入口，通过白名单 query 驱动现有流程，不能绕过保存校验或预检。任务计划与一次执行保持不同实体，完成后的再次执行仍走原 prepare/execute 生成新实例。

## 3. 数据契约

| 展示 | 来源 | 规则 |
| --- | --- | --- |
| 火情列表/详情 | eventApi.list/get | workspaceId 查询保持 Spring 参数格式；身份认证沿用现有 client；空列表与请求失败分开 |
| 识别证据记录 | eventApi.history | 保留后端 action、source、时间，不伪造通知对象 |
| 处置时间轴 | operationIncidentApi.timeline | linkedIncidentId 存在才请求；与识别历史合并按时间稳定排序 |
| 复核 | eventApi.confirm/reject | 登录 operatorId、必填说明、处理中防重复；成功后重读服务端状态 |
| 处置/投送/审批 | operation-workbench、FireMissionList/Detail | 复用现有幂等、状态、权限与预检 |
| 飞机/遥测 | MSDK/现有设备状态接口 | 无数据不显示默认在线或虚构航高 |
| 视频 | 现有播放 URL 与 video-bandwidth | 窗口开关只控制观看，不能停止源端持续上传；高清由后端租约裁决 |
| 航线与任务 | wayline.ts/现有计划组件 | 复用机型、载荷与执行条件，不复制原型 mock |

联调补充：原后端在未关联处置事件时没有留存人工复核说明。新增 fire_event_history.decision_reason（nullable varchar(1000)），确认/排除事务中写入并由历史 API 返回；原记录保持 NULL。迁移脚本 backend/sql/migrations/2026-09-06-fire-event-decision-reason.sql 可重复执行，必须先迁移再更新后端。旧后端忽略新增字段，代码回退无需删列。列表使用 workspaceId/status/limit，兼容旧调用 size，禁止蛇形参数导致跨工作区汇总。

## 4. 状态、异常与生命周期

- 加载状态：初次 loading / 成功空态 / 成功有数据 / 失败重试。后台刷新失败保留已显示数据并标记未更新。
- 切换事件采用请求序号防止旧请求覆盖新事件；时间轴失败独立显示，不把失败当作无记录。
- 复核仅允许待复核且有登录身份的事件；收到业务失败不改本地状态；成功后刷新列表和详情。已有 client 自动生成 X-Idempotency-Key。
- 页内筛选不发控制指令。URL 查询只控制视图；不得用查询参数设置权限、批准状态或媒体租约。
- 页面退出清理定时器、ResizeObserver、pointer listeners 和播放器；浏览器隐藏暂停非必要刷新。视频关闭释放观看租约，不调用 stopLivestream。
- 页面级样式限定 command-center 根节点，避免影响 Pilot 登录和旧专业控件。导航 active 从 router 实时计算，支持返回与深链。

## 5. 测试与部署方式

先保存全量前端测试基线，再增加数据适配、请求竞态、导航匹配和视频布局/资源释放的行为用例。构建正式应用后，在本机正式接口服务验证登录、只读 API 与页面；可控写入只使用独立测试数据，真实飞行/投放操作不作为 UI 验证动作。

浏览器必须检查 1440/1792 桌面、1280 中屏和390窄屏；截图并对照已确认原型，记录滚动、溢出、图片比例、状态提示、键盘与错误日志。有问题修复后重测受影响用例。

## 6. 风险与回退

历史交接记录存在前端已知失败，必须逐项比较本次基线。部署缺失后端或设备时报告准确层级，不以 fixture 测试代替现场验收。原规划/审批组件保留，旧综合驾驶舱不再挂载。通过航线库、灭火任务和视频内飞机操作进入对应功能。代码回退采用本次 diff，不执行数据库回滚或覆盖根目录未提交文件。

当前工作量按实际完成记录更新 implementation-plan.md；所有未执行现场项列入 verification-report.md，不写为通过。

## 7. 代码模块与职责

| 模块 | 实现文件 | 责任 |
| --- | --- | --- |
| 信息架构 | components/command-center/navigation.mjs | 主栏目、二级入口、嵌套任务与 query view 的高亮规则 |
| 统一外壳 | CommandHeader.vue / SectionNav.vue；home.vue / workspace.vue | 登录身份、退出、导航；保留原地图覆盖层与 WebSocket |
| 总览 | pages/page-web/command-center/Overview.vue | 三个独立数据源并发读取，分别标记失败，15 秒刷新 |
| 火情 | FireEventCenter.vue / event-model.mjs | 最近记录、搜索筛选、深链、图片、复核和合并时间轴 |
| 视频 | VideoMonitor.vue / LiveVideo.vue / video-layout.mjs | 飞机选择、窗口几何、播放器、观看租约、关闭清理 |
| 执行记录 | ExecutionHistory.vue | 航线最近执行与巡检计划实例两种数据口径，服务端分页 |
| 原有规划 | projects/wayline.vue / wayline-planner/* | 航点/巡逻/面状、参数、KMZ、保存、执行，保持原业务实现 |
| 原有处置 | operation-workbench.vue / operation/* | 关联处置深链，排除已复核误报候选，接入视频入口 |
| 复核后端 | FireEventServiceImpl / FireEventHistoryEntity/DTO | 事务内记录说明；历史查询返回说明与原审核人字段 |

所有文件路径以 frontend/src 为前缀（Java 文件除外）。公共业务组件仍由原模块维护，避免出现两份规划、执行或审批状态机。

## 8. 关键接口与字段

### 8.1 火情读取

2026-09-08：事件缺失/占位工作区已改为按设备持久化绑定解析，已修复历史归属；默认隐藏 COMMAND_CENTER_QA 验收记录、可勾选展示。详见 [数据修复与展示口径](workspace-event-fix-2026-09-08.md)。

`GET /api/fire/events?workspaceId={id}&limit=200`：Spring 的 query 名称为 camelCase，JSON 仍为 snake_case。前端只为这个接口显式序列化 query，不改变全局 JSON 转换策略。兼容旧调用传 size，但不声称支持服务端 page 分页。工作区筛选是查询条件，不替代后端身份与权限管理。

`GET /api/fire/events/{eventId}` 支持业务编号或内部 ID。所有路径段 encodeURIComponent。深链事件不在最近 200 条时单独补读；读取失败给出明确错误，不把其他记录冒充指定事件。

列表状态优先级：REJECTED/IGNORED → 已排除；已结束任务状态 → 已结束；CONFIRMED/linkedIncidentId/MISSION_CREATED → 处理中；NEW/CANDIDATE/LOW_CONFIDENCE/PENDING → 待复核；其余 → 状态待核实。UNLOCATED/LASER_LOCATING/LASER_FAILED 不展示为已确认坐标；缺失、越界及 (0,0) 同样显示定位待确认。

### 8.2 复核与审计

`POST /api/fire/events/{id}/confirm|reject`，请求为 `{ operator_id, reason }`，沿用 X-Idempotency-Key。页面锁定弹窗打开时的事件，说明 1–1000 字；请求中禁用提交及关闭，业务错误不推进状态。后端兼容旧调用 reason 为空，但限制最大 1000 字；新页面要求必填。

状态写入与 history 写入在同一事务中；history.source_event_id 在 CONFIRMED/REJECTED/RECHECK_RESULT 时是审核人 ID，不能显示成飞机编号。新增 decision_reason 原样保存，返回后由 Vue 文本绑定显示。确认时仍沿用定位精度判断：非 PRECISE 不创建投送任务；没有派遣、起飞或释放载荷的新增调用。

历史合并使用 `history:{id}` 与 `operation:{index}:{time}` 两种 key；按真实时间稳定升序排列。两源 Promise.allSettled，单源失败显示单源错误。没有后端记录的短信提醒、接收人、处置耗时不生成虚构节点。

### 8.3 视频观看与带宽

设备来自 `/manage/api/v1/msdk/devices`；媒体信息来自 `/manage/api/v1/dual-stream/groups/{sn}`；高清实际结果来自 `/manage/api/v1/video-bandwidth/status`。观看申请调用 `/manage/api/v1/video-bandwidth/viewers/{viewerId}`，页面不自行裁决四路高清限额。

每个窗口独立 viewer ID，几何位置按工作区保存到 localStorage。只恢复设备列表中存在、ID 唯一、坐标有限的窗口；限制到舞台范围。关闭所有窗口后保留空布局。关闭/离页/隐藏清理本地播放器和观看租约；不调用源端 stopLivestream。媒体地址读取失败、无地址、连接超时、解码错误、画面停滞分别提示。真实播放必须等 video playing 事件后才标记实时画面。

本次没有实现服务端统一存储个人窗口布局，也没有把窗口布局跨账户同步。浏览器存储不可用时仍能观看，只是不持久化布局。

### 8.4 航线与历史口径

规划弹窗允许在无在线飞机时选择机型后保存；只有“打开弹窗”允许机型暂缺，正式保存仍校验机型、航点、名称与空域，执行仍要求在线飞行器及原预检条件。

执行与历史拆为“航线最近执行”和“巡检计划记录”：前者读取 planned-waylines 的当前状态、flightId、taskStatusReason；后者读取 jobs 的独立执行实例。前者接口只提供每条航线最近执行，不能当成完整逐次审计流水；未新增历史回填或伪造记录。完整跨航次日志的统一检索属于后续后端接口扩展。

## 9. 异常与并发矩阵

| 场景 | 展示/处理 | 防止的问题 |
| --- | --- | --- |
| 列表刷新失败 | 保留旧记录并标注可能过期 | 假空态、数据突然消失 |
| 初次成功返回 [] | 暂无事件/设备/记录 | 把无数据当系统异常 |
| A 请求慢于 B | 独立 generation，仅最新提交 | 图片/时间轴串到另一事件 |
| 相同事件状态变化 | 轮询后重新读取详情 | 处置时间轴长时间停在旧状态 |
| 页面卸载 | invalidation + timer/listener/player 清理 | 卸载后写状态、重复连接 |
| 未确认播放质量 | 档位待确认，不以申请成功表示高清 | 假高清、超额名额 |
| 页面已有错误面板的轮询 | silent 参数抑制重复 toast，401仍走原登录恢复 | 持续弹出错误遮挡导航 |
| 已排除事件进入处置列表 | 共用 eventState 过滤候选 | 误报重新成为待处理候选 |

## 10. 部署、回退与现场验收

1. 对目标数据库确认 schema 名；执行新增列迁移，再重复执行一次确认幂等。禁止直接对未知数据库运行 init.sql（包含建表/重建逻辑）。
2. Java 17 运行 Maven `-pl uavfire -am package`，先通过后端测试再启动。当前 jar 不是带 main manifest 的可执行包，本次用既有 spring-boot:run 启动，不能用“java -jar 成功”作证据。
3. 前端构建时配置实际 API/WS 地址。本次仅对命令注入 127.0.0.1:6789，未覆盖用户 env；正式环境必须替换为部署地址。
4. 静态站点需支持 Vue history 路由回退至 index.html，快照路径继续按原 nginx/Vite 配置代理。验证 `/fire-events`、`/wayline` 等深链刷新不是 404。
5. 本次本机运行正式构建和真实 Java API，不等于 Windows 生产部署或遥控器安装；不改设备固件/APK。
6. 回退前端导航与新页路由、后端 reason 写入代码即可；保留 nullable 新列，避免删掉已留存的审核说明。未经专项备份/批准不删除审计列。
7. 现场需另测：M300/H20T 与实际遥控器型号、4HD/16SD 同时上传、媒体恢复、航线再次执行的新 flightId、空域门禁、审批/投送反馈、实际飞行与载荷释放。没有本次现场实测证据的项目不得勾选通过。

## 11. 本地运行命令

以下在现有目标工作树执行，不改根目录旧现场。环境已有项目依赖。数据库仅运行本次增量迁移；使用既有安全凭据，不在文档记录口令。

后端（backend目录，Java17）：

```sh
mvn -B -pl uavfire -am package
mvn -B -pl uavfire spring-boot:run -Dspring-boot.run.arguments='--server.address=127.0.0.1 --mqtt.BASIC.host=127.0.0.1 --mqtt.DRC.host=127.0.0.1'
```

前端（frontend目录）：

```sh
VITE_APP_APIGATEWAY_BACKEND_HOST=http://127.0.0.1:6789 VITE_APP_APIGATEWAY_WEBSOCKET_HOST=ws://127.0.0.1:6789 ./node_modules/.bin/vite build
./node_modules/.bin/vite preview --host 127.0.0.1 --port 8080 --strictPort
```

全量本地前端测试（frontend目录，含组件及页面的mjs测试）：

```sh
python3 - <<'PYTEST'
from pathlib import Path
import subprocess
files = sorted(str(p) for p in Path('src').rglob('*.test.mjs'))
raise SystemExit(subprocess.call(['node', '--test', *files]))
PYTEST
```

验证截图中的QA事件使用本地演示登录生成的实际账号。生产必须继续使用部署环境的身份服务，不把本地演示入口当作权限验收。

## 12. 根据实用反馈合并重复入口

主导航改为五项：运行总览、视频监控、火情事件、任务中心、设备管理。任务中心二级仅航线库、巡检计划、灭火任务与审批、执行记录。“新建航线”是航线库操作；旧规划深链仍能打开创建对话框。“事件处置”只归火情事件。

原监测列表和FC100投放列表共用plannedWaylinesData，造成同一航线出现两次且后者统一标成FC100。现在只渲染一份航线列表，显示实际aircraftModelKey；卡片“用于投放”打开原FC100选设备/建任务/执行/终端控制弹窗，保留原校验。旧view=delivery链接进入统一航线库，不再出现第二套目录。FC100状态轮询生命周期移到wayline父组件。

旧驾驶舱源码保留用于追溯，但不再作为页面路由加载。视频窗口增加“操作”，按所选飞机挂载DeviceOperations，复用CockpitFlightControlPanel；识别启停继续调用现有API，M300闭环能力门禁继续拦截。控制只使用15秒内有效高度遥测，缺失/过期时禁用，不用默认电量和高度伪造在线数据；查看/关闭操作区不会启停源端推流。
