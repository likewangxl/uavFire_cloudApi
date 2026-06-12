# 航线规划页司空2 风格改造 — 设计文档

日期：2026-06-12
分支：`feature/fire-precision-and-realtime-detection`
状态：已与用户逐节确认；经双视角评审（代码库对照 + 外部资料核验）修订 v2

修订记录：
- v2（2026-06-12）：评审后修订——H 标记分模式定义数据来源；补 `WaylineMissionMonitor` 与任务生命周期归属；修正 GMap.vue 现状描述（卫星图层已存在）与抽离行号；DEM 转换改 `gdalwarp`；标注 GLO-30 为 DSM；高程接口鉴权与自适应采样；页签与飞行覆盖物规则；FC100 平移边界；测试证据分类；任务级参数区；新增 2D 模拟预演。

## 背景与目标

当前航线规划页（`frontend/src/pages/page-web/projects/wayline.vue`，约 3600 行）同时承载 M4T 点选规划与 FC100 投放航线两套逻辑，交互方式为"地图点选加航点 + 顶部控制条"，地图为高德默认浅色矢量图。目标是对标大疆司空2（FlightHub 2）的规划方式与地图展示方式，完成整页改造。

本设计是三个子项目中的第一个（其余两个：火点精确坐标、火情识别实时化，后续单独设计）。

## 已确认的产品决策

| 决策点 | 结论 |
|---|---|
| 改造范围 | 整个航线规划页（M4T 规划升级交互，FC100 投放一并统一视觉） |
| 必须的交互 | 航点拖拽/插点/删点、航点列表+参数抽屉、航线统计、高度可视化（四项全要） |
| 底图 | 高德 AMap 卫星图层 + 路网标注 + 深色 UI（现有 key，零新增授权）；MapLibre/Cesium 评估后不采用，Cesium 3D 地形可作二期 |
| 页面布局 | 司空2 经典三段式（左列表 + 右参数抽屉 + 顶部统计胶囊 + 底部工具栏）+ 底部高度剖面 |
| 地图覆盖物 | "信息常显"风格：每个航点常驻信息牌（高度·速度·动作摘要），监测航线绿色、投放航线橙红色 |
| 高度剖面 | 航点高度折线 + 地形线（需要落地真实 DEM 高程服务） |
| FC100 呈现 | 顶部选项卡切换「监测规划 / 投放任务」两种模式；航线/航点覆盖物随页签切换，**飞机位置/轨迹跨页签常显** |
| 起飞点 H 标记 | 分模式：M4T 用飞机当前位置近似（无飞机位置时不画）；FC100 用 mission 真实 takeoff 坐标 |
| 模拟预演 | 本期做 2D 沿航线动画推演（纯前端） |
| 实现路径 | 组件化重构，数据与执行层（store/API）完全不动 |

## 第①节：页面结构与组件拆分

`wayline.vue` 重写为薄壳，只负责顶部页签与模式切换：

```
wayline.vue（薄壳：页签「监测规划 | 投放任务」）
└─ frontend/src/components/wayline-planner/
   ├─ PlannerWorkspace.vue      监测规划模式根容器（三段式布局壳）
   ├─ RouteListPanel.vue        左侧上半：已存航线列表（加载/新建/删除/重命名/下发），
   │                            行内继续嵌入 WaylineMissionMonitor（见下）
   ├─ WaypointListPanel.vue     左侧下半：航点卡片列表（与地图双向联动）
   ├─ WaypointParamDrawer.vue   右侧参数抽屉（航点级参数，现有表单逻辑平移重构）
   ├─ MissionParamsPanel.vue    任务级参数区（RTH 高度/finishAction/失控动作/安全起飞高度等，
   │                            现 wayline.vue:664-740 的航线级表单平移）
   ├─ MissionStatsBar.vue       顶部统计胶囊（里程/预计时间/航点数，前端实时计算）
   ├─ PlannerToolbar.vue        底部工具栏（加点模式/撤销[=删除最后一个航点]/清空/保存/下发/模拟预演）
   ├─ SimulationBar.vue         模拟预演控制条（播放/暂停/倍速/进度，见第②节）
   ├─ ElevationProfile.vue      高度剖面（手写 SVG，不引入图表库）
   └─ Fc100DeliveryView.vue     投放任务页签（FC100 专属 UI 平移，边界见下）
```

**任务执行监控（评审修订）**：现 `wayline.vue:75-83` 在航线行 `record.flightId` 存在时渲染 `WaylineMissionMonitor.vue`（暂停 / 恢复 / 断点续飞 `canResumeBreakpoint` / 中止，调用 `pause`/`query-breakpoint`/`stop` 等 API）。该组件**原样保留**，继续嵌入 `RouteListPanel.vue` 的航线行内；暂停/恢复/断点续飞/中止/详情/编辑入口是 `RouteListPanel` 的必须职责，不得在平移中丢失。

**FC100 平移边界（评审修订）**：`wayline.vue` 中 FC100 相关引用约 300 处。划界原则——仅 FC100 **专属 UI 区块**（设备选择器 `:107-141`、执行/停止/返航/绳控 `:183-247`、终端命令区等）平移进 `Fc100DeliveryView.vue`；**共享设施不平移**：`<GMap>` 实例、保存/详情弹窗（`:425-480`，两页签共用）、`use-wayline-planning.ts` store。实现计划阶段先产出 FC100 区块的函数/模板清单再动手。

约束与原则：

- **数据与执行层完全不动**：`use-wayline-planning.ts` store、`api/wayline.ts`、点选执行/OSD 轨迹跟踪/trackedAircraftSn 闸门等已实飞验证的逻辑原样复用。
- 新增 UI 状态（当前页签、抽屉开合、剖面开合、预演状态）放轻量的 `use-planner-ui.ts`，不混入飞行 store。
- 统计算法：里程用 GCJ02 坐标累加航段距离；预计时间 = Σ(航段距离 ÷ 该段速度)，航点速度覆写优先、否则全局速度，另加悬停动作时长。
- 弹窗/抽屉组件使用 antd-vue 2.x 的 `v-model:visible`（项目版本 2.2.8，不是 4.x 的 `open`）。

## 第②节：地图层改造

`GMap.vue` 为多页面共享组件。**现状（评审核实）**：卫星图层切换已存在——`GMap.vue:28`「卫星」按钮、`:877-905` `ensureSatelliteLayer()`（`AMap.TileLayer.Satellite`）与 `setWaylineMapLayer()`。本期增量改动：

1. **底图增强**（在现有 `setWaylineMapLayer` 基础上）：卫星模式叠加 `AMap.TileLayer.RoadNet` 路网标注层（仓库当前无 RoadNet 引用）；航线页进入时默认切到卫星图层，其他页面默认行为不变。
2. **覆盖物渲染抽离**：把 GMap.vue 内嵌的规划覆盖物代码（实测约 `:756-1040`：`renderPlanningWaypoints` :758、`rebuildPlanningOverlays` :817、`updateFlightPositionOverlay` :946 及飞行轨迹 polyline）**整体**抽到新模块 `frontend/src/hooks/use-planner-overlays.ts`——含飞行位置/轨迹覆盖物，GMap.vue 只留挂载调用。

`use-planner-overlays.ts` 实现的覆盖物风格与交互：

- 航点 = HTML content Marker：序号圆点 + 常驻信息牌（高度·速度·动作摘要）；信息牌按缩放级别显隐（注：高德 `LabelMarker` 海量点方案不支持 HTML 内容，不可用于信息牌，故采用显隐策略）；选中态放大高亮。
- 航线 = Polyline 带方向箭头（`showDir`，官方建议 `strokeWeight >= 6` 时箭头才清晰）；监测航线绿色、FC100 投放航线橙红色。
- 航段中点距离胶囊标签（随缩放隐藏）。
- **起飞点 H 标记（分模式，评审修订）**：
  - M4T 监测模式：以**飞机当前位置**近似作 H（选中飞机且 `flightPosition` 有值时绘制 H + 虚线引导段到 1 号航点）；规划阶段无飞机位置时**不画 H**，仅画航线本体。
  - FC100 投放模式：用 mission 的 `takeoffLat/Lng/Alt`（`Fc100WpmlBuilder` 已有 `takeOffRefPoint`）。
- **页签与覆盖物规则（评审修订）**：航线/航点/距离标签等规划覆盖物随页签切换显隐；**飞机位置与轨迹覆盖物跨页签常显**（执行中无论在哪个页签都能看到飞机）；`trackedAircraftSn` 闸门逻辑不变。
- **拖拽改位**：marker 开 `draggable`，dragend 更新 store（GCJ02→WGS84 同步换算）。
- **中点插点**：相邻航点中点显示半透明 "+" 幽灵点，点击在该位置插入新航点。
- **删点**：航点右键删除；航点卡片与参数抽屉内提供删除按钮。
- **2D 模拟预演（本期新增）**：纯前端动画——幻影飞机图标沿航线按各段速度插值移动（含悬停动作停留），`SimulationBar.vue` 控制播放/暂停/倍速/进度；预演与真实执行状态互斥（执行中禁用预演）。

附注（外部核验）：项目用 AMap JSAPI 2.0（`frontend/src/constants/index.ts`），Marker 拖拽/HTML content/rightclick、Polyline showDir 均为 2.0 官方能力；现有 key 可用。若系统转商用需高德商业授权，2021-12 后新申请的 key 需配安全密钥——现有 key 不受影响，仅作记录。

## 第③节：高度剖面与 DEM 后端

**数据源**：Copernicus GLO-30（30m 分辨率），AWS 公开 S3 桶 `copernicus-dem-30m`（eu-central-1），无需账号，curl 直接 HTTPS 下载。对象路径如 `Copernicus_DSM_COG_10_N39_00_E115_00_DEM/Copernicus_DSM_COG_10_N39_00_E115_00_DEM.tif`（注意路径中分辨率字段是 `10` 弧秒，不是 30）。

**重要语义（评审修订）**：GLO-30 是 **DSM**（数字表面模型，含树冠/建筑高度），不是裸地 DTM。对林区飞行，剖面显示的"离地间隙"实际是"距冠层顶间隙"，真实离地余量更大（偏保守，安全方向）；对火点定位反而有利（森林火发生在冠层）。此语义在剖面图 UI 注明"地形含植被冠层"。

**数据准备**：`scripts/fetch-dem.sh`：输入经纬度范围 → 下载对应 1°×1° COG 瓦片 → **`gdalwarp`** 转 SRTM `.hgt`（评审修订：GLO-30 瓦片为 3600×3600 pixel-is-area，SRTMHGT 强制 3601×3601 pixel-is-point，`gdal_translate` 直转不可行）：

```bash
gdalwarp -ts 3601 3601 -te {west} {south} {west+1} {south+1} -r bilinear in.tif N39E115.hgt
```

输出文件名必须严格匹配 `NXXEYYY.hgt`（驱动从文件名解析坐标）。N50° 以北瓦片宽度降为 2400 像素（中国仅黑龙江最北端涉及），`-r bilinear` 上采样即可，脚本统一处理。gdal 仅数据准备时本机使用，运行时零依赖。

**后端**（零新增 Java 依赖）：

- `HgtTerrainElevationService implements TerrainElevationService`：内存映射读取 `.hgt`（3601×3601 int16 **大端**、行序北→南、无文件头、无效值 −32768；Java `readShort()` 原生大端无需换序）+ 双线性插值 + 瓦片 LRU 缓存。
- 注入策略：配置 `uavfire.terrain.dem-dir` 且目录存在瓦片时启用，否则回落 `MissingTerrainElevationService`（条件装配，不破坏现有行为）。
- 新端点 `POST /terrain/elevations`：批量查询 `[{lat,lng}…]`（WGS84）→ `[海拔|null…]`，单次上限 500 点；**走现有 JWT 鉴权链路**（评审修订）。
- **附带收益**：`RayDemFireGeoLocationService` 注入同一接口，火点定位从 `DEM_MISSING` 变为真正可用，为子项目"火点精确坐标"铺路。

**前端剖面**（`ElevationProfile.vue`）：

- 采样步长**自适应**（评审修订）：`步长 = max(30m, 总航线长 ÷ 500)`，保证单次请求 ≤500 点（20km 航线步长 40m），无需分批。
- 纵轴基准（评审修订，与 H 标记一致）：M4T 模式优先用飞机当前位置（H 点）处 DEM 海拔为基准 0；无飞机位置时用 **1 号航点正下方 DEM 海拔**；FC100 模式用 takeoff 点 DEM 海拔。地形线画 `(地形海拔 − 基准海拔)`，航线画航点相对高度，可读出近似离地间隙；基准为飞机位置时 UI 注明"以飞机当前位置为基准（近似）"。
- 航点变动后 500ms 防抖重查；接口失败或瓦片缺失时降级为只画航点折线并提示"地形数据缺失"。

## 第④节：错误处理与测试

**后端测试**：

- 用代码生成的合成 `.hgt` 小文件验证：双线性插值正确性（已知网格值→期望插值）、瓦片边界点、缺瓦片返回 `Optional.empty`、无效值（−32768）处理。
- 批量接口：点数上限、参数校验、部分点缺数据时的 null 填充、未带 JWT 返回 401。

**前端测试**（沿用 `frontend/scripts/*.test.mjs` 模式）：

- 统计计算（里程/预计时间，含速度覆写与悬停时长）。
- 插点/删点/拖拽后的 store 更新与坐标换算。
- 剖面采样（自适应步长、基准选择）与降级逻辑。
- 模拟预演的时间轴插值（各段速度、悬停停留）。

**回归保护（评审修订，分类）**：

- **store/API 契约类测试**（重构后必须保持绿，是"执行链路未破坏"的独立证据）：`use-wayline-planning` store 行为、API 调用契约相关断言。
- **DOM 结构类测试**（`wayline-aircraft-position.test.mjs`、`fc100-delivery-ui.test.mjs` 中断言旧 DOM 的部分）：随重构同步更新，更新后保持绿，但不作为独立回归证据。
- lint + build 通过。

**降级路径**：DEM 缺失→剖面降级不报错；信息牌低缩放自动隐藏；FC100 平移只动挂载位置不动逻辑；执行中禁用模拟预演。

## 明确不做（本期范围外）

- Cesium 3D 地形（可作二期独立子项目）。
- 更换地图引擎（MapLibre/Mapbox）。
- 测绘类面状区域自动航线生成（司空2 的 Mapping 任务）。
- FABDEM 等去冠层 DTM 数据源（如后续林区离地精度有更高要求再引入）。
- 火点精确坐标、火情识别提速（后续子项目，单独设计）。

## 视觉参考

brainstorm mockup 存档于 `.superpowers/brainstorm/53446-1781231495/content/`（`layout.html` 布局选型、`map-style.html` 覆盖物选型、`final-design.html` 组合效果）。
