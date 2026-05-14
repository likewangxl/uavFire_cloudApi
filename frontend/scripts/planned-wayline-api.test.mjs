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
const plannedControllerPath = join(repoRoot, 'backend/sample/src/main/java/com/dji/sample/wayline/controller/PlannedWaylineController.java')

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
    assert.match(source, new RegExp(`export\\s+interface\\s+${typeName}\\b`))
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
  assert.match(source, /waypoints:\s*state\.waypoints\.map\(\(wp,\s*idx\)/)
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
  assert.match(source, /waypoints:\s*state\.waypoints\.map\(\(wp,\s*idx\)\s*=>\s*buildPlannedWaypointBody\(normalizePlannedWaypoint\(wp\),\s*idx\)\)/)
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
  assert.match(source, /getPlannedWaylines[\s\S]*result\.data\.data\.list\.map\(normalizePlannedWaylineResponse\)/)
  assert.match(source, /getPlannedWayline[\s\S]*normalizePlannedWaylineResult\(result\.data\)/)
  assert.match(source, /generatePlannedWaylineFile[\s\S]*normalizePlannedWaylineResult\(result\.data\)/)
})

test('wayline page renders saved planned-wayline management and calls planned APIs', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  for (const copy of [
    '已保存规划航线',
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
    'generatePlannedWaylineFile',
    'preparePlannedWaylineTask',
    'executePlannedWaylineTask',
    'cancelPlannedWaylineTask',
    'getPlannedWayline',
    'loadPlannedWayline',
    'buildPlannedWaylineBody',
  ]) {
    assert.match(source, new RegExp(`\\b${symbol}\\b`))
  }

  assert.match(source, /refreshPlannedWaylines/)
  assert.match(source, /plannedWaylinesPagination/)
  assert.match(source, /plannedWaylinesCanRefresh/)
  assert.match(source, /onPlannedWaylinesScroll/)
  assert.match(source, /refreshWaylineFiles/)
  assert.match(source, /savePlannedWaylineModal/)
  assert.match(source, /plannedWaylineDetailVisible/)
  assert.match(source, /selectedPlannedWayline/)
  assert.match(source, /showPlannedWaylineDetail/)
  assert.match(source, /getPlannedWaylineActions/)
  assert.match(source, /canOverwritePlannedWayline/)
  assert.match(source, /onGeneratePlannedWaylineFile/)
  assert.match(source, /onPreparePlannedWaylineTask/)
  assert.match(source, /onExecutePlannedWaylineTask/)
  assert.match(source, /onCancelPlannedWaylineTask/)
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
  const map = readFileSync(join(frontendRoot, 'src/components/GMap.vue'), 'utf8')

  assert.match(source, /@click\.stop="showPlannedWaylineDetail\(record\)"/)
  assert.match(source, /@click="onPreviewPlannedWayline\(record\)"/)
  assert.match(source, /previewPlannedWayline\(record\)/)
  assert.match(source, /resetPlanningDraft\(\)/)
  assert.match(hook, /previewWaypoints:\s*\[\]/)
  assert.match(hook, /export function previewPlannedWayline\b/)
  assert.match(hook, /export function clearPlannedWaylinePreview\b/)
  assert.match(map, /const renderPlanningWaypoints = computed/)
  assert.match(map, /planningState\.previewWaypoints/)
})

test('wayline page clears planned route overlays when leaving the page', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  assert.match(source, /onUnmounted\(\(\)\s*=>\s*{[\s\S]*resetPlanningDraft\(\)/)
  assert.match(source, /if\s*\(planningState\.executing\)\s*{[\s\S]*return[\s\S]*}/)
  assert.match(source, /selectedAircraftSn\.value\s*=\s*''/)
})

test('wayline page uses DJI-safe default planned-wayline names', () => {
  const source = readFileSync(waylinePagePath, 'utf8')

  assert.match(source, /function\s+formatSafePlannedWaylineTimestamp\b/)
  assert.match(source, /function\s+sanitizeDjiWaylineName\b/)
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
