import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('wayline aircraft locate button is one-shot instead of continuous follow', () => {
  const source = readSource('src/components/GMap.vue')

  assert.match(source, /function locateAircraftPosition \(\)/)
  assert.doesNotMatch(source, /aircraftFollowEnabled\.value = !aircraftFollowEnabled\.value/)
  assert.doesNotMatch(source, /if \(aircraftFollowEnabled\.value\) \{\s*setAircraftView\(position\)\s*\}/)
})

test('wayline treats MSDK aircraft coordinates as WGS before rendering on AMap', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const tsaSource = readSource('src/pages/page-web/projects/tsa.vue')
  const gmapSource = readSource('src/components/GMap.vue')

  assert.doesNotMatch(tsaSource, /__coordinateSource:\s*'gcj'/)
  assert.doesNotMatch(waylineSource, /__coordinateSource:\s*'gcj'/)
  assert.doesNotMatch(gmapSource, /osd\\?\\.__coordinateSource === 'gcj'/)
  assert.match(waylineSource, /setFlightPositionFromWgs\(device\.aircraftSn, device\.longitude, device\.latitude/)
  assert.match(waylineSource, /setFlightPositionFromWgs\(sn, msdkDevice\.longitude, msdkDevice\.latitude/)
})

test('wayline keeps MSDK device refresh from rewriting global current aircraft OSD', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const syncMsdkMatch = waylineSource.match(/function syncMsdkOnlineAircrafts[\s\S]*?\n}\n\nfunction syncSelectedAircraftFlightPosition/)

  assert.ok(syncMsdkMatch)
  assert.doesNotMatch(syncMsdkMatch[0], /SET_DEVICE_INFO/)
  assert.match(waylineSource, /source:\s*'msdk-agent'/)
  assert.match(waylineSource, /source:\s*'cloud-topology'/)
  assert.match(waylineSource, /existing\?\.source === 'msdk-agent' && summary\.source !== 'msdk-agent'/)
})

test('GMap ignores non-selected aircraft OSD when no wayline tracking target exists', () => {
  const gmapSource = readSource('src/components/GMap.vue')

  assert.match(gmapSource, /const trackingSn = planningState\.flightPosition\?\.aircraftSn \|\| planningState\.aircraftSn/)
  assert.match(gmapSource, /if \(!trackingSn\) return/)
})

test('entering the wayline page recenters the map on the aircraft (one-shot, deferred until position arrives)', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const gmapSource = readSource('src/components/GMap.vue')
  const planningSource = readSource('src/hooks/use-wayline-planning.ts')

  // hook 暴露一次性居中信号
  assert.match(planningSource, /recenterAircraftToken:\s*0/)
  assert.match(planningSource, /export function requestAircraftRecenter \(\)/)
  // wayline 进入页面时触发
  assert.match(waylineSource, /requestAircraftRecenter\(\)/)
  // GMap 监听 token：有位置立即居中，否则挂起等位置到达后居中一次
  assert.match(gmapSource, /watch\(\(\) => planningState\.recenterAircraftToken/)
  assert.match(gmapSource, /pendingAircraftRecenter = true/)
  assert.match(gmapSource, /if \(pendingAircraftRecenter\) \{[\s\S]*pendingAircraftRecenter = false[\s\S]*setAircraftView\(position\)/)
  // 不能退化成持续跟随
  assert.doesNotMatch(gmapSource, /aircraftFollowEnabled/)
})

test('wayline planning controls open from the planner toolbar (FlightHub layout)', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const workspaceSource = readSource('src/components/wayline-planner/PlannerWorkspace.vue')
  const toolbarSource = readSource('src/components/wayline-planner/PlannerToolbar.vue')

  // 页面入口：监测页签下整体 teleport 到地图浮层
  assert.match(waylineSource, /class="wayline-header-actions"/)
  assert.match(waylineSource, /title="导入航线"/)
  assert.match(waylineSource, /<PlannerWorkspace/)
  assert.match(waylineSource, /to="#wayline-planning-overlay-host"/)
  assert.match(waylineSource, /:on-start-placing="onStartPlacing"/)
  assert.match(waylineSource, /:on-save="onSavePlannedWayline"/)
  // 旧浮层结构必须已移除
  assert.doesNotMatch(waylineSource, /wayline-map-planning-bar/)
  assert.doesNotMatch(waylineSource, /togglePlanningOverlay/)
  // 工具栏承载 布点/撤销/清空/保存/执行 入口
  assert.match(toolbarSource, /＋航点/)
  assert.match(toolbarSource, /撤销/)
  assert.match(toolbarSource, /清空/)
  assert.match(toolbarSource, /下发执行/)
  assert.match(toolbarSource, /onStartPlacing/)
  // 三段式容器：统计条/航点列表/参数抽屉
  assert.match(workspaceSource, /MissionStatsBar/)
  assert.match(workspaceSource, /WaypointListPanel/)
  assert.match(workspaceSource, /WaypointParamDrawer/)
})

test('wayline map exposes FlightHub-style layer tools and route stats', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const gmapSource = readSource('src/components/GMap.vue')
  const planningSource = readSource('src/hooks/use-wayline-planning.ts')

  assert.match(planningSource, /selectedWaypointId:\s*''/)
  assert.match(planningSource, /export function selectWaypoint \(id: string\)/)
  assert.match(planningSource, /export const planningRouteStats = computed/)
  assert.match(planningSource, /totalDistanceM/)
  assert.match(planningSource, /estimatedSeconds/)

  assert.match(gmapSource, /class="wayline-map-toolbox"/)
  assert.match(gmapSource, /class="\{'active': waylineMapLayer === 'standard'\}"/)
  assert.match(gmapSource, /class="\{'active': waylineMapLayer === 'satellite'\}"/)
  assert.match(gmapSource, /toggleRangingTool/)
  assert.match(gmapSource, /stopRangingTool/)
  assert.match(gmapSource, /rangingToolActive \? '停止测距' : '测距'/)
  assert.match(gmapSource, /map\.setLayers\(\[standard\]\)/)
  assert.match(gmapSource, /map\.setLayers\(\[standard, satellite, roadNet\]\.filter\(Boolean\)\)/)
  assert.match(gmapSource, /AMap\.TileLayer\.RoadNet/)
  assert.match(gmapSource, /applyWaylineDefaultLayer/)
  assert.match(gmapSource, /watch\(\(\) => planningState\.active/)
  assert.match(gmapSource, /AMap\.RangingTool/)
  assert.match(gmapSource, /AMap\.TileLayer\.Satellite/)
  assert.match(gmapSource, /AMap\.createDefaultLayer/)
  assert.match(gmapSource, /selectWaypoint\(wp\.id\)/)
  assert.match(gmapSource, /wayline-planning-marker--start/)
  assert.match(gmapSource, /wayline-planning-marker--selected/)
  assert.match(gmapSource, /showDir:\s*true/)

  const statsBarSource = readSource('src/components/wayline-planner/MissionStatsBar.vue')
  assert.match(statsBarSource, /computeRouteStats/)
  assert.match(statsBarSource, /formatDistance/)
  assert.match(statsBarSource, /formatDuration/)
  assert.match(statsBarSource, /里程/)
  assert.match(statsBarSource, /预计/)
  const wpListSource = readSource('src/components/wayline-planner/WaypointListPanel.vue')
  assert.match(wpListSource, /selected:\s*planningState\.selectedWaypointId === wp\.id/)
  assert.match(wpListSource, /@click="onSelect\(wp\.id\)"/)
})
