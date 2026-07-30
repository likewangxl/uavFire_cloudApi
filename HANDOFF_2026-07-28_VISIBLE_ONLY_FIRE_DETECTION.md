# 交接文档：纯可见光火情识别链路（2026-07-28）

> 交接对象：Codex。本文档覆盖 2026-07-27 晚 ~ 07-28 凌晨的全部改动、已验证结论、未解决问题与运行手册。
> 所有改动**均未提交**（见文末"未提交改动清单"），先 review 再决定提交粒度。

## 一、背景与决策

用户拍板：**去掉红外画面识别与测温，火情识别只用可见光**。原因是红外串行链（红外 YOLO→测温仲裁→80°C 线确认→可见光证据照）识别慢（10~30 秒/次，一次飞越只有 1~2 次测温机会）。

新链路（当前生产形态）：

```
可见光 RTSP（ZLM，~1s/帧）
  → ai-service 可见光 YOLO（MPS，~53ms 推理）
  → fire 框内火色像素否决（防夜间暗噪声误报）
  → 分数 ≥0.25 且连续两帧、框中心相近（6s 窗、归一化距离 ≤0.3）
  → POST /api/fire/events（10s 防刷屏去抖）
  → 后端 FireEventService.create：OSD 回填定位 + 空间合并去重 + 建任务(WAITING_REVIEW) + 驾驶舱通知
  → 人工确认/驳回（红外自动确认已移除）
```

**时延账**：火入画 → 事件生成 ≈ 2~3 秒（首帧 ~1s + 第二帧确认 ~1s + POST）。
**红外代码全部保留未删**，只是不再被触发；恢复串行链 = revert 本次改动。

## 二、改动清单（按模块）

### backend（Java, `backend/uavfire`）

| 文件 | 改动 |
|---|---|
| `firedetection/FireDetectionService.java` | 启动监测不再发 `thermal-monitor-on`+`focus-thermal`；新增 `isThermalFocusActive()`（查 dual-stream group 的 currentMode），**仅镜头真在红外时**才发 `focus-visible`（start/stop 两处）。原因：agent 收到镜头命令会无条件 `restartLiveStream`，无操作切换也会断流卡顿（`RealMsdkStreamProvider.kt:42-44`） |
| `manage/service/impl/DualStreamServiceImpl.java` | `applySingleStreamReview()` 可见光分支：不再自动下发 `focus-thermal` 切红外复核（状态仍标 `VISIBLE_SKIPPED_THERMAL_FIRST`，`rememberVisibleTrigger` 保留）。thermal 分支代码原样保留（无红外事件进来即死路） |
| `DualStreamServiceImplTest.java` | 69 测试全绿：6 个旧"可见光→自动切红外"断言反转；7 个串行链测试改为显式 `issueCommand("focus-thermal")` 搭桥进红外分支 |

### backend 补充（07-28 凌晨）

- `wayline/service/impl/PlannedWaylineServiceImpl.java` + `PlannedWaypointDTO.java`：WPML 生成时未显式设置俯仰角的航点，`waypointGimbalPitchAngle` 默认 **0° → -30°**（航线飞行相机前下视，供可见光识别取景）。规划页显式设置的角度仍优先。`PlannedWaylineServiceTest` 43 绿。

### frontend（`frontend/src/pages/page-web/projects/leadership-cockpit.vue`）

- 驾驶舱"开始火情监测"按钮：原来启动成功后主动 `switchFireMonitorFocus('focus-thermal')`（这是之前"点监测就切红外"的真正来源，与后端无关）。现在：新增 `isThermalFocusActive()`（读 agent 上报的 `dualStreamState.group.currentMode`），**仅红外时才切回可见光**，否则完全不发镜头命令、不碰视频流（直播不再卡顿）。停止监测同理。

### ai-service（Python）

| 文件 | 改动 |
|---|---|
| `app/services/task_registry.py` | **接线 `fire_event_reporter`**（生产装配原来传的是 `None`，从未生效——红外时代事件由后端建）；`ContinuousTaskRunner` 轮询 0.5s→0.2s；visible detector 缓存 key/构造加 `device` |
| `app/services/fire_event_reporter.py` | ①删"仅 thermal 通道上报"闸门；②可见光上报线 `_VISIBLE_REPORT_SCORE_FLOOR=0.25`（对齐画框线 box_display_floor，保证"有事件必有框"；红外弱分线 0.01 保留）；③**连续两帧确认**（`_VISIBLE_CONFIRM_WINDOW_S=6`、中心距 ≤0.3，防单帧噪声误报）；④可见光跌破线只清连续帧记录**不清去抖窗**（火苗闪烁曾把 60s 窗打穿导致 7~15s 一条 POST）；⑤可见光去抖 60s→**10s**（`_VISIBLE_REPORT_DEBOUNCE_S`，空间去重交给后端合并——多火点巡飞每个火点独立出事件） |
| `app/inference/visible/detector.py` | ①`device="auto"`→Apple Silicon 自动用 MPS（推理 273ms→53ms 实测），`PYTORCH_ENABLE_MPS_FALLBACK=1` 在模块顶 setdefault（torchvision::nms 无 MPS 实现需 CPU 回退）；②**火色像素否决** `_box_passes_fire_color_check`：fire 框内 <5 个橙红像素（R≥180,G≥80,B≤120）直接毙掉（实测黑暗树丛噪声可打 fire 0.78），smoke 类不校验；③score 改从否决后的候选框取 max（原 `_peak_target_confidence` 及其 helper 已删）|
| `app/video/source.py` | ①模块顶注入 `OPENCV_FFMPEG_CAPTURE_OPTIONS=rtsp_transport;tcp|timeout;5000000`（`.env` 里的同名行从未生效——非 AI_SERVICE_ 前缀不会导出到进程环境）。②**`LatestFrameVideoSource`（关键）**：socket 超时防不住"ZLM 推流暂停但持续发 RTCP 保活"的卡法（实测 453s 静默盲区、无任何日志），改为独立抓帧线程满速消费流 + 帧龄 10s 看门狗，检测循环永不阻塞网络；重连时直接抛弃卡死线程/连接新建一路。盲区上限降到 ~15s 且有 WARNING 日志。附带：不再需要"每 8 读重开连接"（内层 max_reads_before_reopen=0）；流未就绪时任务不再失败，流出现自动开始识别。`continuous_supervisor.opencv_source_factory_from_task` 已切到该包装。新增 `tests/test_latest_frame_video_source.py` 5 例 |
| `app/config/settings.py` | 新增 `visible_yolo_device: str = "auto"`（env `AI_SERVICE_VISIBLE_YOLO_DEVICE`）|
| `.env` | 模型换成 `weights/visible-fire-best-inference-20260727.pt`（fire/smoke 两类、imgsz 960，微信收的 best.pt 系列；**imgsz 严禁降 640——小火从 0.691 直接掉到 0.000 实测漏检**）|
| tests | 159 全绿（reporter 两帧确认/10s 去抖/floor、registry 接线、detector fake 加 xyxy）|

### 数据库

- `fire_event` / `fire_event_history` 已清空（测试数据）；全量归档在同库备份表 `fire_event_bak_20260727`（698 条）/ `fire_event_history_bak_20260727`（1258 条），另有磁盘 dump `backups/fire_event_backup_20260727_2147.sql`。
- 恢复：`insert into fire_event select * from fire_event_bak_20260727;`（履历同理）。
- 测试期常用清理（归档后清空）：
```sql
insert ignore into fire_event_bak_20260727 select * from fire_event;
insert ignore into fire_event_history_bak_20260727 select * from fire_event_history;
delete from fire_event_history; delete from fire_event;
```

## 三、已实测验证

1. **端到端链路通**：识别→两帧确认→事件（带框标注图 + OSD 坐标回填 + 空间合并 report_count 累加 + 驾驶舱通知），实火（夜间盆火）多轮验证。
2. **画框管线一致性**：真火帧离线重跑分数/框位置与运行时吻合（0.696 vs 0.691）；"框位置不对"的案例（fire 0.78 画在黑树丛）是**模型误报**不是画框 bug（JPEG 存盘抹平暗噪声后离线复现不出）。
3. **"重启监测后 23 秒才出事件"解释**：识别 2 秒就绪，21 秒在等上一轮的 60s 去抖窗（现已改 10s，且按空间去抖）。
4. **性能**：MPS 推理 53ms；检测节奏 ~1s/帧（瓶颈在 RTSP 取帧不在推理）。

**未实飞验证**（下次飞行要盯）：
- 5s 拉流超时是否消除分钟级盲区（看重连 WARNING 是否秒级恢复）；
- 10s 空间去抖多火点是否各自出独立事件；
- 白天场景的误报率（0.25 线 + 火色否决 + 两帧确认组合是首次白天实战）。

## 四、未解决问题（按优先级）

1. **模型质量（最重要，需训练侧出下一版）**
   - 夜间暗部噪声可打 fire 0.78 高分误报（已用火色否决兜底，但治标）；
   - 小余烬 + 夜间稀薄烟零检出（conf 0.03 都没有输出）——早期火情/阴燃识别缺口；
   - 真火低谷帧闪烁（0.7↔0.15 跳动）。
   - 硬负样本已留：`ai-service/data/fire-snapshots/fire-1581F7K3D249C00AEK3P-1785156989334-raw.jpg`（黑暗树丛 0.78 误报帧）；"小余烬+烟"零检出帧在 `1785159030845` 附近。
2. **agent 冷启动身份时序 bug**：飞机比 agent 晚就绪时，OSD 上报器以占位 SN（`UNKNOWN-AIRCRAFT-<rcSn>`）启动，真机身份解析后**某些时序下不切换**（07-27 实测两路并存：真机 SN 一路正常、占位一路一直空报）。现象无害（后端丢弃占位路），但曾与"拓扑 SN 与流名不一致导致直播取流失败"同源。彻底修法在 `AppServices.activateDynamicIdentity` / `AgentRuntimeLoop.handleIdentityChange` 时序，需要改 APK。临时办法：飞机上电后重启 agent App。
3. **ai-service 重启/热加载丢检测任务**：需手动重建（见运行手册）。可做成后端定时对账自动拉起（`FireDetectionActivityTracker` 标记 active 但 ai-service 无任务时自动 re-start）。
4. **抵近确认任务的红外依赖未拆**：`FireConfirmationProcessor`（agent 端）内部仍有 `focus-thermal`+测温+激光定位动作；`auto-approach-enabled` 目前 false。若要在纯可见光模式下重开自动抵近，需先拆掉其中的红外步骤。已知坑：后端下发 `fire-confirmation-mission` 命令不检查 processor 的 `enabled` 开关（`DualStreamSessionManager.kt:368` 直接 run）。
5. **取帧节奏瓶颈**：RTSP 每 8 读重开一次连接（防缓冲积压的粗暴方案）+ 逐帧阻塞读，实际 ~1s/帧（推理只占 53ms）。优化方向：独立拉流线程持续 read 丢弃旧帧、检测线程永远拿最新帧，可到 0.3s/帧以内。
6. **通知横跳风险（观察项）**：后端合并只在火情等级**升级**时 bump notificationVersion；分数在 MEDIUM↔HIGH 边界来回横跳时每次"升级"都会再通知，10s 去抖下可能比 60s 时代频繁。刷屏的话在 `mergeIntoExisting` 加等级升级通知冷却。
7. **红外死代码**：`ThermalDwellConfirmer.kt`（agent，全无引用）等。留着无害，恢复串行链时还有用。
8. 旧遗留：fc100 ActionButtons 用 `open` 属性（antd-vue 2.x 要 `v-model:visible`）导致审批弹窗不弹，与本次无关，未修。
9. **运行中改后端代码的坑（02:07 实测踩过）**：`mvn spring-boot:run` + devtools 热重启会出现新旧类混杂，火情事件 POST 被 `Method [getVisibleRoi] cannot be resolved` 类错误整批拒收——识别正常但事件全丢。开发期改完后端代码必须完整重启（Ctrl-C 再 run），实飞前确认后端是干净启动的。

## 五、运行手册

```bash
# 三件套都在 tmux：uavfire-backend / uavfire-frontend / uavfire-ai
# backend（JDK11，:6789）
JAVA_HOME=/usr/local/opt/openjdk@11 mvn spring-boot:run -pl uavfire   # 在 backend/ 下
# ai-service（:9000，--host 不能省）
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload   # 在 ai-service/ 下
# frontend（vite :8080）
npm run serve

# ai-service 重启/reload 后重建检测任务（驾驶舱点"开始火情监测"亦可）
TOKEN=$(curl -s -X POST http://127.0.0.1:6789/manage/api/v1/demo-login | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['access_token'])")
curl -s -X POST "http://127.0.0.1:6789/manage/api/v1/fire-detection/start" \
  -H "x-auth-token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"drone_sn":"1581F7K3D249C00AEK3P"}'

# ZLM（colima docker，容器 uavfire-zlmediakit；RTSP :8554、HTTP API :58925）
curl -s "http://127.0.0.1:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy"

# agent（RC Plus，adb 192.168.50.141:5555，包名 com.yinxin.uavfir）
adb -s 192.168.50.141:5555 shell am force-stop com.yinxin.uavfir
adb -s 192.168.50.141:5555 shell monkey -p com.yinxin.uavfir -c android.intent.category.LAUNCHER 1
```

关键参数速查（ai-service，改后热加载即生效）：
- 上报/画框线 0.25：`fire_event_reporter._VISIBLE_REPORT_SCORE_FLOOR` / detector `box_display_floor`（两者要一起动）
- 两帧确认：`_VISIBLE_CONFIRM_WINDOW_S=6` / `_VISIBLE_CONFIRM_MAX_CENTER_SHIFT=0.3`
- 去抖：`_VISIBLE_REPORT_DEBOUNCE_S=10`
- 检测轮询：`task_registry.build_registry` 里 `poll_interval_s=0.2`

## 六、未提交改动清单（git status @ 交接时点）

本次纯可见光改造（建议一个 commit）：
- `backend/.../firedetection/FireDetectionService.java`
- `backend/.../manage/service/impl/DualStreamServiceImpl.java`
- `backend/.../manage/service/DualStreamServiceImplTest.java`
- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `ai-service/app/{config/settings.py, inference/visible/detector.py, services/{fire_event_reporter,task_registry}.py, video/source.py}`
- `ai-service/tests/{test_fire_event_reporter,test_task_registry_backend_reporting,test_visible_detector}.py`
- 模型权重（未跟踪）：`ai-service/weights/visible-fire-best-20260727.pt`、`visible-fire-best-inference-20260727.pt`（大文件，确认是否入库/LFS）

**与本次无关的前置改动（勿混入）**：`AGENTS.md`、`backend/.../wayline/service/impl/PlannedWaylineServiceImpl.java`、`backend/.../wayline/PlannedWaylineServiceTest.java`（TRAJ_DAMP_DIS_OUT_OF_RANGE 转弯截距修复，07-27 下午的另一件事）。

## 七、后续实现：悬停后激光测距定位（Codex，2026-07-28）

用户确认的新流程已经在代码中完成：

```text
可见光连续两帧确认
  → 立即创建并告警同一个火情事件（laserLocationState=LASER_LOCATING）
  → 下发 visible-fire-hold，航线飞行时暂停航线，否则直接悬停
  → 水平速度 ≤0.3m/s、垂直速度绝对值 ≤0.2m/s，连续稳定 1s
  → 获取悬停后的新鲜可见光 ROI（≤1.5s）
  → tap zoom 对准 ROI，再取一帧新鲜 ROI
  → 开启激光测距，验证激光目标落在 ROI 内
  → 连续取得 3 个 NORMAL 样本（间隔 300ms、样本散布 ≤15m）
  → 使用 DJI MSDK 返回的目标经纬度和高度更新原事件为 PRECISE
```

关键行为：

- 初次事件创建和告警不等待悬停、云台或测距，因此不会降低识别与首报告效率。
- `LASER_LOCATING` 阶段的 OSD 经纬度只保存在飞机遥测字段，不作为火点坐标展示或参与空间合并、自动抵近、地图标注和航线规划。
- 悬停超时为 8s；测距最多 3 次。失败后同一事件更新为 `LASER_FAILED`，不创建第二条事件。
- 成功坐标直接取 DJI MSDK 激光测距结果，不由项目自行做像素投影计算；误差半径暂定 5m。
- 定位流程结束后不会自动恢复航线，必须由操作者决定后续动作。
- 新流程只由可见光事件触发，不调用红外识别或红外测温。

主要新增文件：

- `backend/.../event/model/param/FireLaserLocationParam.java`
- `backend/.../event/service/impl/VisibleFireLocalizationDispatcher.java`
- `backend/.../manage/model/dto/VisibleRoiSnapshotDTO.java`
- `rcplus-msdk-agent/.../api/VisibleFireLaserLocator.kt`
- `rcplus-msdk-agent/.../api/VisibleRoiSnapshotResponse.kt`
- `frontend/.../fire/fire-event-location.mjs`

设计与实施计划已经单独提交：

- `9211714 docs: design visible fire laser geolocation`
- `032a2cc docs: plan visible fire laser geolocation`

### 自动化验证结果

- AI：`168 passed, 1 skipped`
- Backend：`433 tests, 0 failures, 0 errors, 0 skipped`
- RC Plus Agent：`:app:testDebugUnitTest` 通过
- RC Plus APK：`:app:assembleDebug` 通过；产物为 `rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk`
- Frontend policy tests：`99 passed, 0 failed`
- Frontend：`npm run build:test` 与 `npm run build` 均通过（只有既有 chunk-size warning）
- `git diff --check` 通过

### 当前部署状态

操作者确认无人机已落地后，已于 2026-07-28 02:24 完成现场部署：

- 后端和 AI 服务均已干净重启，健康检查返回 HTTP 200。
- 新 APK 已覆盖安装到 RC Plus 并正常启动，包版本 `0.1.0`，设备身份正确解析为飞机 `1581F7K3D249C00AEK3P`。
- Agent 命令轮询、OSD/HMS 上报正常，未发现 Android 崩溃。
- 可见光检测任务已重建，AI 持续输出 `channel=visible`，后端持续接收；启动时 `thermal-monitor-off` 已由 Agent 确认执行。
- 火情监测直播页面曾因旧 Vite 服务经历失败的 HMR 后保留失效模块而点击无响应；完整重启 frontend 并强制刷新浏览器后恢复，未修改业务代码。

尚待实火/实飞验证的是完整动态流程：两帧确认后悬停、tap zoom 对准、激光三样本定位以及同一火情事件从 `LASER_LOCATING` 更新为 `PRECISE`。

### RC Plus 旧包覆盖故障与修复

首次部署后，RC Plus 在 02:32:03 被另一个同包名、同 `versionCode=1` 的旧 APK 覆盖。设备侧旧包只有 9 个 DEX，不包含 `VisibleFireLaserLocator`，因此产生了两个同时出现的症状：

- DJI UX 飞行界面白屏；
- 后端下发 `visible-fire-hold` 后，Agent 立即返回 `ignored / unsupported-action:visible-fire-hold`，火情事件随即被标记为 `LASER_FAILED`。

已将 `rcplus-msdk-agent/app/build.gradle.kts` 版本提升为 `versionCode=2`、`versionName=0.1.1`，重新执行 Agent 全量单元测试和 APK 构建。2026-07-28 02:43 重新安装后进行了设备侧反向验证：

- `dumpsys package` 显示 `versionCode=2`、`versionName=0.1.1`；
- 从 RC Plus 拉回的实际安装包有 26 个 DEX；
- 实际安装包的 `classes22.dex` 中确认存在激光定位代码；
- 主界面和 DJI UX 飞行界面均正常显示，可见光相机 Surface 持续输出；
- Agent 真实 SN、命令轮询、OSD/HMS 上报正常；
- 可见光检测任务已恢复并持续输出 `channel=visible`。

当前 0.1.1 APK SHA-256：

```text
2468354a10702f7f02cd10371d5e5241d9a1c5b35db80540ec4cd6984289738f
```
