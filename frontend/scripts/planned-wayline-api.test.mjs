import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const frontendRoot = new URL('..', import.meta.url).pathname
const repoRoot = new URL('../..', import.meta.url).pathname

const waylineApiPath = join(frontendRoot, 'src/api/wayline.ts')
const waylineTypesPath = join(frontendRoot, 'src/types/wayline.ts')
const planningHookPath = join(frontendRoot, 'src/hooks/use-wayline-planning.ts')
const waylinePagePath = join(frontendRoot, 'src/pages/page-web/projects/wayline.vue')
const fc100ViewPath = join(frontendRoot, 'src/components/wayline-planner/Fc100DeliveryView.vue')
const fc100HookPath = join(frontendRoot, 'src/hooks/use-fc100-delivery.ts')
const waylineFormatPath = join(frontendRoot, 'src/components/wayline-planner/wayline-format.ts')
const missionParamsPath = join(frontendRoot, 'src/components/wayline-planner/MissionParamsPanel.vue')
const plannerOverlaysPath = join(frontendRoot, 'src/hooks/use-planner-overlays.ts')

const fc100View = readFileSync(fc100ViewPath, 'utf8')
const fc100Hook = readFileSync(fc100HookPath, 'utf8')
const waylineMissionMonitorPath = join(frontendRoot, 'src/components/WaylineMissionMonitor.vue')
const plannedControllerPath = join(repoRoot, 'backend/uavfire/src/main/java/com/yx/uavfire/wayline/controller/PlannedWaylineController.java')

test('frontend wayline API exposes planned-wayline CRUD and task action endpoints', () => {
  const source = readFileSync(waylineApiPath, 'utf8')
  const controller = readFileSync(plannedControllerPath, 'utf8')

  assert.match(controller, /planned-waylines/)
  for (const exportedName of [
    'getPlannedWaylines',
    'getPlannedWayline',
    'createPlannedWayline',
    'updatePlannedWayline',
    'deletePlannedWayline',
    'importPlannedWaylineKmzFile',
    'publishPlannedWayline',
    'generatePlannedWaylineFile',
    'preparePlannedWaylineTask',
    'executePlannedWaylineTask',
    'cancelPlannedWaylineTask',
  ]) {
    assert.match(source, new RegExp(`export\\s+(?:const|async function|function)\\s+${exportedName}\\b`))
  }

  assert.match(source, /\/planned-waylines\?page=\$\{page\.page\}&page_size=\$\{page\.page_size\}/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}`\s*[\r\n]+\s*const result = await request\.get\(url\)/)
  assert.match(source, /\/planned-waylines`\s*[\r\n]+\s*const validatedBody = validatePlannedWaylineBody\(body\)[\r\n]+\s*const result = await request\.post\(url, validatedBody\)/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}`\s*[\r\n]+\s*const validatedBody = validatePlannedWaylineBody\(body\)[\r\n]+\s*const result = await request\.put\(url, validatedBody\)/)
  assert.match(source, /\/planned-waylines\/import-kmz/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}\/publish/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}\/generate-file/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}\/prepare/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}\/execute/)
  assert.match(source, /\/planned-waylines\/\$\{plannedWaylineId\}\/cancel/)
})

test('planned-wayline types describe saved records, task states, and action payloads', () => {
  const source = readFileSync(waylineTypesPath, 'utf8')

  assert.match(source, /export\s+enum\s+PlannedWaylineStatus\b/)
  for (const typeName of [
    'PlannedWaypoint',
    'PlannedWaylineRecord',
    'CreatePlannedWaylineBody',
    'UpdatePlannedWaylineBody',
    'PublishPlannedWaylineResult',
    'PreparePlannedWaylineTaskBody',
  ]) {
    assert.match(source, new RegExp(`export\\s+(?:interface|type)\\s+${typeName}\\b`))
  }

  for (const field of [
    'plannedWaylineId',
    'aircraftModelKey',
    'gatewaySn',
    'aircraftSn',
    'defaultHeight',
    'maxSpeed',
    'publishedWaylineId',
    'kmzUrl',
    'kmzMd5',
    'flightId',
    'dockSn',
    'droneSn',
    'taskStatus',
    'taskStatusReason',
    'taskProgress',
    'aircraftGcjLng',
    'aircraftGcjLat',
    'aircraftLng',
    'aircraftLat',
    'aircraftUpdatedAt',
    'publisher',
    'publishTime',
    'waypoints',
  ]) {
    assert.match(source, new RegExp(`\\b${field}\\b`))
  }

  for (const statusName of [
    'DRAFT',
    'FILE_GENERATED',
    'PUBLISHING',
    'PREPARED',
    'EXECUTING',
    'COMPLETED',
    'FAILED',
    'CANCELED',
  ]) {
    assert.match(source, new RegExp(`\\b${statusName}\\b`))
  }
})

test('planning hook can build save payloads and hydrate saved planned-wayline records', () => {
  const source = readFileSync(planningHookPath, 'utf8')

  for (const exportedName of [
    'buildPlannedWaylineBody',
    'loadPlannedWayline',
    'resetPlanningDraft',
    'setEditingPlannedWayline',
  ]) {
    assert.match(source, new RegExp(`export\\s+function\\s+${exportedName}\\b`))
  }

  assert.match(source, /editingPlannedWaylineId/)
  assert.match(source, /aircraftModelKey/)
  assert.match(source, /waypoints:\s*bodyWaypoints\.map\(\(wp,\s*idx\)/)
  assert.match(source, /order:\s*idx\s*\+\s*1/)
})

test('planning hook sanitizes saved payloads before calling planned-wayline APIs', () => {
  const source = readFileSync(planningHookPath, 'utf8')

  assert.match(source, /wgs84togcj02/)
  assert.match(source, /function\s+finiteNumber\b/)
  assert.match(source, /value === null \|\| value === undefined \|\| value === ''/)
  assert.match(source, /function\s+normalizePlannedWaypoint\b/)
  assert.match(source, /function\s+normalizePositiveNumber\b/)
  assert.match(source, /Number\.isFinite\(gcjLng\)/)
  assert.match(source, /Number\.isFinite\(wgsLng\)/)
  assert.match(source, /gcj02towgs84\(gcjLng,\s*gcjLat\)/)
  assert.match(source, /wgs84togcj02\(wgsLng,\s*wgsLat\)/)
  assert.match(source, /defaultHeight:\s*normalizePositiveNumber\(state\.defaultHeight,\s*DEFAULT_HEIGHT_M\)/)
  assert.match(source, /maxSpeed:\s*normalizePositiveNumber\(state\.maxSpeed,\s*DEFAULT_MAX_SPEED\)/)
  assert.match(source, /waypoints:\s*bodyWaypoints\.map\(\(wp,\s*idx\)\s*=>\s*buildPlannedWaypointBody\(normalizePlannedWaypoint\(wp\),\s*idx\)\)/)
})

test('planned-wayline API rejects invalid save payloads before sending requests', () => {
  const source = readFileSync(waylineApiPath, 'utf8')

  assert.match(source, /function\s+validatePlannedWaylineBody\b/)
  assert.match(source, /function\s+assertPlannedWaypoint\b/)
  assert.match(source, /value === null \|\| value === undefined \|\| value === ''/)
  assert.match(source, /message\.error\(`航点坐标缺失/)
  assert.match(source, /message\.error\('规划航线参数不完整/)
  assert.match(source, /createPlannedWayline[\s\S]*validatePlannedWaylineBody\(body\)[\s\S]*request\.post\(url,\s*validatedBody\)/)
  assert.match(source, /updatePlannedWayline[\s\S]*validatePlannedWaylineBody\(body\)[\s\S]*request\.put\(url,\s*validatedBody\)/)
})

test('planned-wayline API normalizes snake-case backend records before page actions use IDs', () => {
  const source = readFileSync(waylineApiPath, 'utf8')

  assert.match(source, /function\s+normalizePlannedWaypointResponse\b/)
  assert.match(source, /function\s+normalizePlannedWaylineResponse\b/)
  assert.match(source, /plannedWaylineId:\s*record\?\.plannedWaylineId\s*\?\?\s*record\?\.planned_wayline_id/)
  assert.match(source, /gcjLng:\s*record\?\.gcjLng\s*\?\?\s*record\?\.gcj_lng/)
  assert.match(source, /aircraftGcjLng:\s*record\?\.aircraftGcjLng\s*\?\?\s*record\?\.aircraft_gcj_lng/)
  assert.match(source, /currentWaypointIndex:\s*record\?\.currentWaypointIndex\s*\?\?\s*record\?\.current_waypoint_index/)
  assert.match(source, /getPlannedWaylines[\s\S]*result\.data\.data\.list\.map\(normalizePlannedWaylineResponse\)/)
  assert.match(source, /getPlannedWayline[\s\S]*normalizePlannedWaylineResult\(result\.data\)/)
  assert.match(source, /generatePlannedWaylineFile[\s\S]*normalizePlannedWaylineResult\(result\.data\)/)
})

test('wayline page renders saved planned-wayline management and calls planned APIs', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  for (const copy of [
    '航线任务',
    '监测规划',
    '投放任务',
    '已生成航线',
    '监测航线库',
    '保存',
    '另存为',
    '生成航线文件',
    '下发准备',
    '开始执行',
    '取消任务',
  ]) {
    assert.match(source, new RegExp(copy))
  }

  for (const symbol of [
    'getPlannedWaylines',
    'createPlannedWayline',
    'updatePlannedWayline',
    'deletePlannedWayline',
    'importPlannedWaylineKmzFile',
    'generatePlannedWaylineFile',
    'preparePlannedWaylineTask',
    'executePlannedWaylineTask',
    'cancelPlannedWaylineTask',
    'getPlannedWayline',
    'loadPlannedWayline',
    'buildPlannedWaylineBody',
  ]) {
    // 符号现分布在视图组件与 use-fc100-delivery store 两处
    assert.ok(new RegExp(`\\b${symbol}\\b`).test(source) || new RegExp(`\\b${symbol}\\b`).test(fc100Hook), symbol)
  }

  assert.match(source, /refreshPlannedWaylines/)
  assert.match(source, /tab="监测规划"[\s\S]*监测航线库/)
  const fc100View = readFileSync(fc100ViewPath, 'utf8')
  assert.match(source, /tab="投放任务"/)
  assert.match(fc100View, /FC100 投放航线库/)
  assert.doesNotMatch(fc100View, /FC100 投放任务/)
  assert.doesNotMatch(source, /选择投放航线后，在执行面板创建任务、启动航线并完成到点后投放控制。/)
  assert.match(source, /class="wayline-mode-collapse generated-wayline-collapse"[\s\S]*header="已生成航线"[\s\S]*id="data"/)
  assert.match(source, /v-else-if="waylinesData\.data\.length !== 0"/)
  assert.match(source, /class="wayline-mode-collapse generated-wayline-collapse"/)
  assert.match(source, /:deep\(\.wayline-mode-collapse\.ant-collapse > \.ant-collapse-item > \.ant-collapse-header\)/)
  assert.match(source, /color:\s*#fff/)
  assert.match(source, /grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/)
  assert.match(source, /grid-auto-flow:\s*row dense/)
  assert.match(source, /grid-column:\s*1 \/ -1/)
  assert.match(source, /\.wayline-button-wrap\s*{[\s\S]*white-space:\s*normal/)
  assert.match(source, /white-space:\s*normal/)
  assert.match(source, /overflow-wrap:\s*anywhere/)
  assert.match(fc100View, /class="planned-wayline-actions planned-wayline-actions--minimal"/)
  assert.match(fc100View, /class="planned-wayline-actions planned-wayline-actions--minimal"[\s\S]*onOpenDeliveryTask\(record, \$event\)[\s\S]*onDeletePlannedWayline\(record\)/)
  assert.match(fc100View, /\.planned-wayline-actions--minimal\s*{[\s\S]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/)
  assert.match(source, /\.project-wayline-wrapper\s+:deep\(\.ant-btn\)\s*{[\s\S]*font-size:\s*14px/)
  assert.match(source, /\.project-wayline-wrapper\s+:deep\(\.ant-btn\)\s*{[\s\S]*font-family:\s*inherit/)
  assert.match(source, /\.project-wayline-wrapper\s+:deep\(\.ant-btn-sm\)\s*{[\s\S]*font-size:\s*14px/)
  assert.match(source, /label:\s*'下发准备'[\s\S]*wrap:\s*true/)
  assert.match(source, /class="delivery-terminal"[\s\S]*到点后投放控制[\s\S]*创建飞行任务[\s\S]*开始执行/)
  assert.doesNotMatch(fc100View, />\s*开始FC100执行\s*</)
  assert.doesNotMatch(fc100View, />\s*刷新FC100状态\s*</)
  assert.match(fc100View, /\.fc100-task-actions\s*{[\s\S]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
  assert.doesNotMatch(source, /font-size:\s*16px/)
  assert.match(fc100Hook, /function\s+selectFc100GeneratedWayline\b/)
  assert.match(fc100View, /@click\.stop="onOpenDeliveryTask\(record, \$event\)"/)
  assert.match(source, /plannedWaylinesPagination/)
  assert.match(source, /plannedWaylinesCanRefresh/)
  assert.match(source, /onPlannedWaylinesScroll/)
  assert.match(source, /refreshWaylineFiles/)
  assert.match(source, /title="导入航线"/)
  assert.match(source, /:accept="plannerTab === 'delivery' \? '\.kmz,\.kml' : '\.kmz'"/)
  assert.match(source, /:custom-request="uploadFile"/)
  assert.match(source, /const uploadFile = async \(options/)
  assert.match(source, /options\?\.file/)
  assert.match(source, /文件格式错误，请选择 KMZ 文件/)
  assert.match(source, /savePlannedWaylineModal/)
  assert.match(source, /plannedWaylineDetailVisible/)
  assert.match(source, /selectedPlannedWayline/)
  assert.match(source, /showPlannedWaylineDetail/)
  assert.match(source, /getPlannedWaylineActions/)
  assert.match(source, /canOverwritePlannedWayline/)
  assert.match(source, /onGeneratePlannedWaylineFile/)
  assert.match(source, /onPreparePlannedWaylineTask/)
  assert.match(source, /function\s+openPrepareTargetModal\b/)
  assert.match(source, /listMsdkDevices/)
  assert.match(source, /function\s+upsertOnlineAircraft\b/)
  assert.match(source, /function\s+syncManagedTopoAircrafts\b/)
  assert.match(source, /function\s+syncMsdkOnlineAircrafts\b/)
  assert.match(source, /await\s+refreshOnlineAircrafts\(\)/)
  assert.match(source, /aircrafts\.length\s*===\s*1/)
  assert.match(source, /selectedAircraftSn\.value[\s\S]*record\.droneSn[\s\S]*record\.aircraftSn/)
  assert.match(source, /选择下发飞行器/)
  assert.match(source, /onExecutePlannedWaylineTask/)
  assert.match(source, /onCancelPlannedWaylineTask/)
  assert.match(source, /formatPlannedWaylineStatus\(record\)/)
  assert.match(source, /planned-wayline-reason/)
  assert.match(source, /record\.taskStatusReason/)
  assert.match(source, /Modal\.confirm/)
  assert.match(source, /生成航线文件后不可直接覆盖/)
  assert.doesNotMatch(source, /发布到航线库/)
  assert.doesNotMatch(source, /isPlannedWaylinePublished/)
  assert.match(source, /editingId\s*&&\s*!editingRecord/)
  assert.match(source, /当前编辑的规划航线不在列表中/)
  assert.match(source, /当前状态的规划航线不能直接覆盖/)
})

test('wayline page clears editable waypoint list after save and previews saved cards on map', () => {
  const source = readFileSync(waylinePagePath, 'utf8')
  const hook = readFileSync(planningHookPath, 'utf8')
  const map = readFileSync(plannerOverlaysPath, 'utf8')

  assert.match(source, /@click\.stop="showPlannedWaylineDetail\(record\)"/)
  assert.match(source, /@click="onPreviewPlannedWayline\(record\)"/)
  assert.match(source, /previewPlannedWayline\(record\)/)
  assert.match(source, /resetPlanningDraft\(\)/)
  assert.match(hook, /previewWaypoints:\s*\[\]/)
  assert.match(hook, /export function previewPlannedWayline\b/)
  assert.match(hook, /export function clearPlannedWaylinePreview\b/)
  assert.match(map, /const renderPlanningWaypoints = computed/)
  assert.match(map, /planningState\.previewWaypoints\.length > 0 \? planningState\.previewWaypoints : planningState\.waypoints/)
  assert.match(map, /planningState\.previewWaypoints/)
})

test('wayline page hides stale planned-wayline task errors and refits map preview', () => {
  const source = readFileSync(waylinePagePath, 'utf8')
  const monitor = readFileSync(waylineMissionMonitorPath, 'utf8')
  const map = readFileSync(plannerOverlaysPath, 'utf8')

  const waylineFormat = readFileSync(waylineFormatPath, 'utf8')
  assert.match(waylineFormat, /function\s+getPlannedWaylineTaskReason\b/)
  assert.match(source, /getPlannedWaylineTaskReason\(record\)/)
  assert.doesNotMatch(source, /v-if="record\.taskStatusReason"/)
  assert.match(monitor, /const\s+taskStatusReason\s*=\s*computed/)
  assert.match(monitor, /taskStatus\.value\s*===\s*'failed'/)
  assert.match(monitor, /v-if="taskStatusReason"/)
  assert.doesNotMatch(monitor, /v-if="record\.taskStatusReason"/)
  assert.match(source, /clearPlannedWaylineTaskReason\(record\)[\s\S]*preparePlannedWaylineTask/)
  assert.match(map, /function\s+fitPlanningPreviewToMap\b/)
  assert.match(map, /fitPlanningPreviewToMap\(\)/)
})

test('wayline map renders live aircraft flight position and follow control', () => {
  const source = readFileSync(waylinePagePath, 'utf8')
  const hook = readFileSync(planningHookPath, 'utf8')
  const map = readFileSync(join(frontendRoot, 'src/components/GMap.vue'), 'utf8')
  const overlays = readFileSync(plannerOverlaysPath, 'utf8')

  assert.match(hook, /flightPosition:\s*null/)
  assert.match(hook, /export function setFlightPositionFromRecord\b/)
  assert.match(hook, /export function setFlightPositionFromWgs\b/)
  assert.match(source, /function applyPlannedWaylineFlightPosition\b[\s\S]*setFlightPositionFromRecord\(record\)/)
  assert.match(source, /function syncSelectedAircraftFlightPosition\b/)
  assert.match(source, /watch\(\s*\(\)\s*=>\s*selectedAircraftSn\.value/)
  assert.match(fc100Hook, /syncFc100DeviceFlightPosition\(fc100PlanningState\.selectedDeviceProps\)/)
  assert.match(overlays, /let flightMarker:/)
  assert.match(overlays, /function updateFlightPositionOverlay/)
  assert.match(overlays, /flightPositionContent\(label\)/)
  assert.match(map, /class="aircraft-follow-control"/)
  assert.match(map, /title="切换到飞机位置"/)
  assert.match(map, /\.aircraft-follow-control\s*{[\s\S]*bottom:\s*82px/)
  assert.match(map, /@click="locateAircraftPosition"/)
  assert.match(overlays, /map\.easeTo\(\{ center:/)
})

test('wayline page clears planned route overlays when leaving the page', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  assert.match(source, /onUnmounted\(\(\)\s*=>\s*{[\s\S]*resetPlanningDraft\(\)/)
  assert.match(source, /if\s*\(planningState\.executing\)\s*{[\s\S]*return[\s\S]*}/)
  assert.match(source, /selectedAircraftSn\.value\s*=\s*''/)
})

test('wayline page uses DJI-safe default planned-wayline names', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  assert.match(readFileSync(waylineFormatPath, 'utf8'), /function\s+formatSafePlannedWaylineTimestamp\b/)
  assert.match(readFileSync(waylineFormatPath, 'utf8'), /function\s+sanitizeDjiWaylineName\b/)
  assert.match(source, /formatSafePlannedWaylineTimestamp\(new Date\(\)\)/)
  assert.match(source, /sanitizeDjiWaylineName\(editingName \|\| '规划航线'\)/)
  assert.match(source, /const name = sanitizeDjiWaylineName\(savePlannedWaylineModal\.name\)/)
  assert.doesNotMatch(source, /规划航线 \$\{new Date\(\)\.toLocaleString\(\)\}/)
  assert.doesNotMatch(source, /\$\{editingName \|\| '规划航线'\} 副本/)
})

test('wayline page rebuilds save payload from visible waypoint coordinates', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  assert.match(source, /gcj02towgs84/)
  assert.match(source, /wgs84togcj02/)
  assert.match(source, /function\s+buildPagePlannedWaypointBody\b/)
  assert.match(source, /function\s+buildPagePlannedWaylineBody\b/)
  assert.match(source, /planningState\.waypoints\.map\(\(wp,\s*idx\)\s*=>\s*buildPagePlannedWaypointBody\(wp,\s*idx,\s*defaultHeight\)\)/)
  assert.match(source, /body\s*=\s*buildPagePlannedWaylineBody\(name,\s*aircraftModelKey\)/)
})

test('wayline page applies route height changes to existing planned waypoints before saving', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  const missionParams = readFileSync(missionParamsPath, 'utf8')
  assert.match(missionParams, /function\s+onDefaultHeightChange\b/)
  assert.match(missionParams, /@change="onDefaultHeightChange"/)
  assert.match(missionParams, /planningState\.waypoints\.forEach\(wp\s*=>\s*{[\s\S]*wp\.height\s*=\s*n[\s\S]*}\)/)
  assert.match(missionParams, /const\s+previousDefaultHeight\s*=\s*Number\(planningState\.defaultHeight\)/)
})

test('planning draft restore keeps advanced waypoint speed and action fields', () => {
  const source = readFileSync(planningHookPath, 'utf8')

  assert.match(source, /const restoredWaypoints = draftWaypoints[\s\S]*\.map\(wp => normalizePlannedWaypoint\(wp as PlannedWaypoint\)\)/)
  assert.doesNotMatch(source, /id:\s*wp\.id,[\s\S]*height:\s*Number\(wp\.height\),[\s\S]*\}\)\)/)
})

test('wayline page exposes FC100 planning from saved generated KMZ records', () => {
  const source = `${readFileSync(waylinePagePath, 'utf8')}\n${readFileSync(fc100ViewPath, 'utf8')}`
  const deliveryApi = readFileSync(join(frontendRoot, 'src/api/fire/delivery.ts'), 'utf8')

  for (const copy of [
    'FC100 投放航线库',
    '导入航线',
    '创建飞行任务',
    '开始执行',
    '到点后投放控制',
  ]) {
    assert.match(source, new RegExp(copy))
  }
  assert.match(fc100Hook, /DJI FlyCart 100/)
  for (const _ of []) {
  }
  assert.doesNotMatch(fc100View, /FC100 投放执行面板/)
  assert.doesNotMatch(fc100View, /刷新FC100设备/)
  assert.match(source, /option-label-prop="label"/)
  assert.match(source, /v-for="device in fc100AircraftDevices"/)
  assert.match(source, /:label="formatFc100DeliveryAircraftModel\(\) \+ ' · ' \+ device\.deviceSn"/)
  assert.match(fc100Hook, /function\s+isFc100AircraftDevice\s*\(device:\s*DeliveryDeviceDTO\)[\s\S]*bindStatus !== 'rc' && deviceType !== 'rc'/)
  assert.match(fc100Hook, /const\s+fc100AircraftDevices\s*=\s*computed\(\(\)\s*=>\s*fc100PlanningState\.devices\.filter\(isFc100AircraftDevice\)\)/)
  assert.match(fc100Hook, /function\s+formatFc100DeviceSelectLabel\s*\(device:\s*DeliveryDeviceDTO\)[\s\S]*formatFc100DeliveryAircraftModel\(\)[\s\S]*device\.deviceSn/)
  assert.match(source, /class="delivery-device-option"[\s\S]*prepare-target-option-name[\s\S]*delivery-device-option-sn/)
  assert.match(fc100Hook, /function\s+formatFc100DeliveryAircraftModel\s*\(\)\s*{[\s\S]*DJI FlyCart 100/)
  assert.match(fc100View, /FC100 投放航线库[\s\S]*机型 \{\{ formatFc100DeliveryAircraftModel\(\) \}\}/)
  assert.doesNotMatch(fc100View, /FC100 投放航线库[\s\S]*机型 \{\{ record\.aircraftModelKey/)

  for (const symbol of [
    'deliveryApi',
    'DeliveryDeviceDTO',
    'DeliveryDeviceProperties',
    'DeliveryTaskStatus',
    'DeliveryTaskOperationResult',
    'fc100PlanningState',
    'handleFc100RefreshDevices',
    'handleFc100SelectDevice',
    'beforeFc100WaylineUpload',
    'uploadFc100WaylineFile',
    'handleFc100ImportGeneratedWaylineTask',
    'handleFc100StartGeneratedWaylineTask',
    'handleFc100GeneratedWaylineTaskStatus',
    'buildFc100StartPreflightWarnings',
  ]) {
    assert.ok(new RegExp(`\\b${symbol}\\b`).test(source) || new RegExp(`\\b${symbol}\\b`).test(fc100Hook), symbol)
  }

  assert.match(fc100Hook, /deliveryApi\.listDevices/)
  assert.match(fc100Hook, /deliveryApi\.deviceProps/)
  assert.match(fc100Hook, /deliveryApi\.importCreateWaylineTask/)
  assert.match(fc100Hook, /deliveryApi\.importGeneratedPlannedWaylineTask/)
  assert.match(fc100Hook, /deliveryApi\.startWaylineTask\(taskId,\s*deviceSn\)/)
  assert.match(fc100Hook, /deliveryApi\.waylineTaskStatus/)
  assert.doesNotMatch(source, /fetch\(record\.kmzUrl\)/)
  assert.doesNotMatch(source, /form\.append\('file',\s*file,\s*file\.name\)/)
  assert.match(source, /record\.kmzUrl/)
  assert.match(readFileSync(waylinePagePath, 'utf8'), /record\.publishedWaylineId/)
  assert.match(fc100View, /@click="onFc100PreviewGeneratedWayline\(record\)"/)
  assert.match(fc100Hook, /function\s+onFc100PreviewGeneratedWayline\b/)
  assert.match(fc100Hook, /function\s+onFc100PreviewGeneratedWayline[\s\S]*previewPlannedWayline\(record\)/)
  assert.doesNotMatch(fc100Hook, /function\s+onFc100PreviewGeneratedWayline[\s\S]*handleFc100ImportGeneratedWaylineTask\(record\)/)
  assert.match(fc100View, /onOpenDeliveryTask\(record, \$event\)/)
  assert.match(fc100Hook, /onFc100UseGeneratedWayline\(record\)/)
  assert.match(fc100Hook, /FC100 开始执行航线前检查未通过/)

  assert.match(deliveryApi, /importCreateWaylineTask:\s*\(body:\s*FormData/)
  assert.match(deliveryApi, /importGeneratedPlannedWaylineTask:\s*\(body:/)
  assert.match(deliveryApi, /\/api\/fire\/delivery\/wayline-tasks\/import-planned-create/)
  assert.match(deliveryApi, /startWaylineTask:\s*\(taskId:\s*string,\s*deviceSn\?:\s*string/)
})

test('planned wayline api preserves per-waypoint overrides and actions on save and load', () => {
  const source = readFileSync(waylineApiPath, 'utf8')
  const assertFn = source.slice(source.indexOf('function assertPlannedWaypoint'), source.indexOf('function validatePlannedWaylineBody'))
  // 保存路径：校验函数必须透传 L1 覆写字段与动作
  for (const field of ['speed', 'gimbalPitch', 'gimbalYaw', 'headingMode', 'headingAngle', 'poiLng', 'poiLat', 'poiAlt', 'turnMode', 'turnDamping', 'actions']) {
    assert.match(assertFn, new RegExp(`\\b${field}\\b`), `assertPlannedWaypoint drops ${field}`)
  }
  // 读回路径：响应归一化必须带回动作与覆写
  const normFn = source.slice(source.indexOf('function normalizePlannedWaypointResponse'), source.indexOf('function normalizePlannedWaylineResponse'))
  for (const field of ['speed', 'gimbalPitch', 'turnMode', 'actions']) {
    assert.match(normFn, new RegExp(`\\b${field}\\b`), `normalizePlannedWaypointResponse drops ${field}`)
  }
})
