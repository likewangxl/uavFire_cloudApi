# 任务 S3：前端事件处置工作台骨架（智能集群巡检灭火 一期）

你是本仓库的实现工程师。仓库根目录即当前工作目录（uavFire_cloudApi_publish）。S1（释放安全边界）、S2/S2.1（operation 事件编排层）已完成。本任务只做前端（frontend/，Vue 3 + Vite 2 + TypeScript + Ant Design Vue 2）。

## 背景

后端已提供事件编排接口（见 backend/uavfire/src/main/java/com/yx/uavfire/fc100/operation/controller/OperationIncidentController.java，先读它确认真实契约）：

- POST /api/operations/incidents（由已确认火情创建）
- GET /api/operations/incidents（分页，status/level 过滤）
- GET /api/operations/incidents/{id}（详情，含 assignments）
- GET /api/operations/incidents/{id}/timeline
- POST /api/operations/incidents/{id}/assign-monitor | assign-delivery
- POST /api/operations/incidents/{id}/dispatch | abort | close | mark-false-alarm

事件状态（9 态）：CANDIDATE, CONFIRMED, DISPATCHING, RESPONDING, RECHECKING, RESOLVED, ARCHIVED, FALSE_ALARM, ABORTED。

## 工作内容

### 1. 新页面"事件处置工作台"

路径参考既有页面组织（frontend/src/pages/page-web/projects/，参考 leadership-cockpit.vue、tsa.vue 的路由注册方式）。五区布局：

```text
| 事件列表 IncidentListPanel |  地图 OperationMap  | 详情 IncidentDetailPanel |
|          底部：事件时间线 OperationTimeline                             |
```

组件（放 frontend/src/components/operation/ 或与既有组件组织一致的位置）：
- IncidentListPanel：事件列表 + 状态/级别筛选 + 状态徽标色。
- OperationMap：复用既有地图封装（先找 tsa.vue / CockpitSituationMap.vue 用的地图组件），展示火点标记、风险半径圆、UOM 参考层开关（复用既有图层逻辑）。
- IncidentDetailPanel：事件详情 + assignments 列表 + 预检结果占位（S5 后接入）+ 视频占位。
- OperationTimeline：时间线（复用 ant-design-vue Timeline）。
- OperationActionBar：按钮按状态可见性规则渲染：
  - 生成/派发类：确认火情(CANDIDATE)、生成灭火任务(CONFIRMED，暂置灰 TODO S6)、运行预检(置灰 TODO S5)、派发(CONFIRMED 且有 DELIVERY_PRIMARY)
  - 危险类（全部二次确认弹窗，展示后果文案）：派发、中止(携带原因必填)、标记误报(原因必填)、归档
  - 释放确认按钮：仅 UI 占位置灰（S7 接入）

### 2. API 层与类型

- frontend/src/api/operation/ 新增 client（参照 src/api/fire/client.ts 风格），TypeScript 类型与后端 DTO 对齐。
- 提供 mock 数据开关（参照项目既有 env/config 方式），mock 模式下页面可完整演示。

### 3. 测试（.mjs 策略测试，纳入 npm run test:policies）

- 按钮可见性规则：每个状态至少断言可见/不可见集合一次（9 态覆盖）。
- 危险操作必须二次确认的策略断言。
- 时间线排序与状态徽标映射。

### 4. 验收

- npm run test:policies 全绿（含既有 76 个）。
- npm run build 通过。
- npm run lint 通过。
- 真实输出记录到 work-records/codex/S3-report-20260702.md。

## 禁区

- 不改后端任何文件。
- 不动既有页面既有功能（只加路由/菜单入口）。
- 不实现视频/预检/释放的真实联动（占位即可）。
- 不 git commit。
