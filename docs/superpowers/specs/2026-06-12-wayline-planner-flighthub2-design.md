# 航线规划页司空2 风格改造 — 设计文档

日期：2026-06-12
分支：`feature/fire-precision-and-realtime-detection`
状态：已与用户逐节确认

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
| FC100 呈现 | 顶部选项卡切换「监测规划 / 投放任务」两种模式，地图只显示当前模式的航线 |
| 实现路径 | 组件化重构，数据与执行层（store/API）完全不动 |

## 第①节：页面结构与组件拆分

`wayline.vue` 重写为薄壳，只负责顶部页签与模式切换：

```
wayline.vue（薄壳：页签「监测规划 | 投放任务」）
└─ frontend/src/components/wayline-planner/
   ├─ PlannerWorkspace.vue      监测规划模式根容器（三段式布局壳）
   ├─ RouteListPanel.vue        左侧上半：已存航线列表（加载/新建/删除/重命名）
   ├─ WaypointListPanel.vue     左侧下半：航点卡片列表（与地图双向联动）
   ├─ WaypointParamDrawer.vue   右侧参数抽屉（现有表单逻辑平移重构）
   ├─ MissionStatsBar.vue       顶部统计胶囊（里程/预计时间/航点数，前端实时计算）
   ├─ PlannerToolbar.vue        底部工具栏（加点模式/撤销[=删除最后一个航点]/清空/保存/下发）
   ├─ ElevationProfile.vue      高度剖面（手写 SVG，不引入图表库）
   └─ Fc100DeliveryView.vue     投放任务页签（现有 FC100 区块整体平移，逻辑零改动）
```

约束与原则：

- **数据与执行层完全不动**：`use-wayline-planning.ts` store、`api/wayline.ts`、点选执行/OSD 轨迹跟踪/trackedAircraftSn 闸门等已实飞验证的逻辑原样复用。
- 新增 UI 状态（当前页签、抽屉开合、剖面开合）放轻量的 `use-planner-ui.ts`，不混入飞行 store。
- 统计算法：里程用 GCJ02 坐标累加航段距离；预计时间 = Σ(航段距离 ÷ 该段速度)，航点速度覆写优先、否则全局速度，另加悬停动作时长。
- 弹窗/抽屉组件使用 antd-vue 2.x 的 `v-model:visible`（项目版本 2.2.8，不是 4.x 的 `open`）。

## 第②节：地图层改造

`GMap.vue` 为多页面共享组件，改动收敛为两点：

1. **底图切换**：新增卫星图层（`AMap.TileLayer.Satellite` + `AMap.TileLayer.RoadNet` 路网标注叠加），右下角「卫星/矢量」切换控件；航线页默认卫星图，其他页面默认行为不变。
2. **覆盖物渲染抽离**：把 GMap.vue 内嵌的规划覆盖物代码（现约 754–860 行区域）抽到新模块 `frontend/src/hooks/use-planner-overlays.ts`，GMap.vue 只留挂载调用。

`use-planner-overlays.ts` 实现的覆盖物风格与交互：

- 航点 = HTML content Marker：序号圆点 + 常驻信息牌（高度·速度·动作摘要）；信息牌在低缩放级别自动隐藏防拥挤；选中态放大高亮。
- 航线 = Polyline 带方向箭头（`showDir`）；监测航线绿色、FC100 投放航线橙红色。
- 航段中点距离胶囊标签（随缩放隐藏）。
- 起飞点黄色 H 标记 + 虚线引导段连到 1 号航点。
- **拖拽改位**：marker 开 `draggable`，dragend 更新 store（GCJ02→WGS84 同步换算）。
- **中点插点**：相邻航点中点显示半透明 "+" 幽灵点，点击在该位置插入新航点。
- **删点**：航点右键删除；航点卡片与参数抽屉内提供删除按钮。

## 第③节：高度剖面与 DEM 后端

**数据源**：Copernicus GLO-30（30m 分辨率），AWS 公开 S3 桶 `copernicus-dem-30m`，无需账号授权。

**数据准备**：`scripts/fetch-dem.sh`：输入经纬度范围 → 下载对应 1°×1° 瓦片 → `gdal_translate` 转为 SRTM `.hgt` 格式（3601×3601 int16 大端网格）→ 存入配置目录。gdal 仅数据准备时本机使用，运行时零依赖。

**后端**（零新增 Java 依赖）：

- `HgtTerrainElevationService implements TerrainElevationService`：内存映射读取 `.hgt` 瓦片 + 双线性插值 + 瓦片 LRU 缓存。
- 注入策略：配置 `uavfire.terrain.dem-dir` 且目录存在瓦片时启用，否则回落 `MissingTerrainElevationService`（条件装配，不破坏现有行为）。
- 新端点 `POST /terrain/elevations`：批量查询 `[{lat,lng}…]`（WGS84）→ `[海拔|null…]`，单次上限 500 点。
- **附带收益**：`RayDemFireGeoLocationService` 注入同一接口，火点定位从 `DEM_MISSING` 变为真正可用，为子项目"火点精确坐标"铺路。

**前端剖面**（`ElevationProfile.vue`）：

- 沿航线按 30m 步长采样，调批量接口取地形高程。
- 纵轴对齐：以起飞点处 DEM 海拔为基准 0，地形线画 `(地形海拔 − 起飞点海拔)`，航线画航点相对起飞点高度，可直接读出近似离地间隙。
- 航点变动后 500ms 防抖重查；接口失败或瓦片缺失时降级为只画航点折线并提示"地形数据缺失"。

## 第④节：错误处理与测试

**后端测试**：

- 用代码生成的合成 `.hgt` 小文件验证：双线性插值正确性（已知网格值→期望插值）、瓦片边界点、缺瓦片返回 `Optional.empty`。
- 批量接口：点数上限、参数校验、部分点缺数据时的 null 填充。

**前端测试**（沿用 `frontend/scripts/*.test.mjs` 模式）：

- 统计计算（里程/预计时间，含速度覆写与悬停时长）。
- 插点/删点/拖拽后的 store 更新与坐标换算。
- 剖面采样与降级逻辑。

**回归保护**：

- 现有 wayline 相关测试（`wayline-aircraft-position`、`fc100-delivery-ui` 等）必须全部保持绿，作为"飞行执行链路未被破坏"的证据。
- lint + build 通过。

**降级路径**：DEM 缺失→剖面降级不报错；信息牌低缩放自动隐藏；FC100 平移只动挂载位置不动逻辑。

## 明确不做（本期范围外）

- Cesium 3D 地形（可作二期独立子项目）。
- 更换地图引擎（MapLibre/Mapbox）。
- 测绘类面状区域自动航线生成（司空2 的 Mapping 任务）。
- 火点精确坐标、火情识别提速（后续子项目，单独设计）。

## 视觉参考

brainstorm mockup 存档于 `.superpowers/brainstorm/53446-1781231495/content/`（`layout.html` 布局选型、`map-style.html` 覆盖物选型、`final-design.html` 组合效果）。
