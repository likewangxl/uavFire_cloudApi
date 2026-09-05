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
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')

  assert.match(overlaysSource, /function locateAircraftPosition \(\)/)
  assert.doesNotMatch(source, /aircraftFollowEnabled\.value = !aircraftFollowEnabled\.value/)
  assert.doesNotMatch(overlaysSource, /if \(aircraftFollowEnabled\.value\) \{\s*setAircraftView\(position\)\s*\}/)
})

test('wayline treats MSDK aircraft coordinates as WGS before rendering on AMap', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const tsaSource = readSource('src/pages/page-web/projects/tsa.vue')
  const gmapSource = readSource('src/components/GMap.vue')
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')

  assert.doesNotMatch(tsaSource, /__coordinateSource:\s*'gcj'/)
  assert.doesNotMatch(waylineSource, /__coordinateSource:\s*'gcj'/)
  assert.doesNotMatch(gmapSource, /osd\\?\\.__coordinateSource === 'gcj'/)
  assert.match(waylineSource, /setFlightPositionFromWgs\(device\.aircraftSn, device\.longitude, device\.latitude/)
  assert.match(waylineSource, /setFlightPositionFromWgs\(sn, msdkDevice\.longitude, msdkDevice\.latitude/)
})

test('wayline keeps MSDK device refresh from rewriting global current aircraft OSD', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const syncMsdkBlock = waylineSource.match(/function syncMsdkOnlineAircrafts[\s\S]*?function syncSelectedAircraftFlightPosition/)?.[0] ?? ''

  assert.match(syncMsdkBlock, /function syncMsdkOnlineAircrafts/)
  assert.doesNotMatch(syncMsdkBlock, /SET_DEVICE_INFO/)
  assert.match(syncMsdkBlock, /SET_MSDK_DEVICE_STATE/)
  assert.match(waylineSource, /function upsertOnlineAircraft \(seen: Set<string>, summary: AircraftSummary\)/)
  assert.match(waylineSource, /source:\s*'msdk-agent'/)
  assert.match(waylineSource, /source:\s*'cloud-topology'/)
  assert.match(waylineSource, /existing\?\.source === 'msdk-agent' && summary\.source !== 'msdk-agent'/)
})

test('GMap ignores non-selected aircraft OSD when no wayline tracking target exists', () => {
  const gmapSource = readSource('src/components/GMap.vue')
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')

  assert.match(gmapSource, /const trackingSn = planningState\.flightPosition\?\.aircraftSn \|\| planningState\.aircraftSn/)
  assert.match(gmapSource, /if \(!trackingSn\) return/)
})

test('wayline track is mission-scoped and rejects stale position rewinds', () => {
  const planningSource = readSource('src/hooks/use-wayline-planning.ts')
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const gmapSource = readSource('src/components/GMap.vue')
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')

  assert.match(planningSource, /shouldAcceptFlightPosition\(state\.flightPosition, position\)/)
  assert.match(planningSource, /export function syncPlannedWaylineTrackSession/)
  assert.match(waylineSource, /syncPlannedWaylineTrackSession\(record, allowTrackSwitch\)/)
  assert.match(waylineSource, /applyPlannedWaylineFlightPosition\(executingRecord, true\)/)
  assert.match(waylineSource, /selectActiveTrackRecord\(/)
  assert.doesNotMatch(waylineSource, /return \['publishing', 'executing', 'paused', 'broken'\]\.includes\(status\)/)
  assert.match(planningSource, /position\.source === 'cloud-osd'/)
  assert.match(gmapSource, /if \(planningState\.flightTrackRecording\) return/)
  assert.match(waylineSource, /source:\s*'msdk-agent'/)
  assert.match(waylineSource, /if \(planningState\.flightTrackRecording\) return\s+setFlightPositionFromRecord\(record\)/)
  assert.match(gmapSource, /source:\s*'cloud-osd'/)
  assert.match(overlaysSource, /planningState\.flightTrackRecording && !isSameTrackPoint/)
  assert.match(overlaysSource, /watch\(\(\) => planningState\.flightTrackRevision/)
  assert.match(overlaysSource, /function resetFlightSessionOverlay \(\)/)
  assert.match(overlaysSource, /if \(!position\) \{ clearFlightPositionMarker\(\); return \}/)
  assert.match(overlaysSource, /flightHomePosition \|\| posLngLat\(pos\)/)
  assert.doesNotMatch(overlaysSource, /homeMarker\.setLngLat\(lngLat\)/)
  assert.ok(overlaysSource.indexOf("id: 'plan-route-line'") < overlaysSource.indexOf("id: 'plan-track-line'"))
})

test('wayline polls the Agent flight position at one hertz independently from topology', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')

  assert.match(waylineSource, /const MSDK_FLIGHT_REFRESH_INTERVAL_MS = 1_000/)
  assert.match(waylineSource, /function refreshMsdkFlightPosition \(\)/)
  assert.match(waylineSource, /setInterval\(refreshMsdkFlightPosition, MSDK_FLIGHT_REFRESH_INTERVAL_MS\)/)
  assert.match(waylineSource, /clearInterval\(msdkFlightTimer\)/)
})

test('saved wayline preview uses the saved max speed for waypoint labels', () => {
  const planningSource = readSource('src/hooks/use-wayline-planning.ts')
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')

  assert.match(planningSource, /state\.previewMaxSpeed = Number\.isFinite\(Number\(record\.maxSpeed\)\)/)
  assert.match(overlaysSource, /const previewSpeed = planningState\.previewWaypoints\.length > 0 \? planningState\.previewMaxSpeed/)
})

test('delivery tab hides monitor aircraft position marker and track', () => {
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')
  const gmapSource = readSource('src/components/GMap.vue')

  assert.match(overlaysSource, /function shouldRenderFlightPositionOverlay \(\)/)
  assert.match(overlaysSource, /return plannerUi\.activeTab === 'monitor'/)
  assert.match(overlaysSource, /if \(!shouldRenderFlightPositionOverlay\(\)\) \{ clearFlightPositionOverlay\(\); return \}/)
  assert.match(overlaysSource, /function matrice4tPositionContent \(/)
  assert.match(overlaysSource, /class="m4t-airframe"/)
  assert.doesNotMatch(overlaysSource, /class="flight-position-marker"><span>✈️<\/span>/)
  assert.match(gmapSource, /:deep\(\.m4t-airframe\)/)
  assert.match(overlaysSource, /function shouldRenderFc100PositionOverlay \(\)/)
  assert.match(overlaysSource, /function fc100PositionContent \(\)/)
  assert.match(overlaysSource, /class="fc100-position-marker"/)
  assert.match(overlaysSource, /class="fc100-airframe"/)
  assert.match(overlaysSource, /use-fc100-position/)
  assert.doesNotMatch(overlaysSource, /use-fc100-delivery/)
  assert.match(overlaysSource, /updateFc100PositionOverlay\(\)/)
  assert.match(gmapSource, /:deep\(\.fc100-position-marker\)/)
  assert.match(gmapSource, /:deep\(\.fc100-airframe\)/)
  assert.match(gmapSource, /:deep\(\.fc100-position-marker\)\s*\{[\s\S]*?background:\s*transparent/)
  assert.doesNotMatch(gmapSource, /:deep\(\.fc100-position-marker\)\s*\{[\s\S]*?border:\s*3px solid #fff/)
  assert.doesNotMatch(gmapSource, /:deep\(\.fc100-position-marker\)\s*\{[\s\S]*?background:\s*#f59f00/)
  assert.doesNotMatch(overlaysSource, /飞机位置\/轨迹跨页签常显/)
})

test('entering the wayline page keeps the browser-location viewport until the operator locates an aircraft', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')
  const gmapSource = readSource('src/components/GMap.vue')
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')
  const planningSource = readSource('src/hooks/use-wayline-planning.ts')

  // 保留显式定位飞机的能力，但进入页面时不再自动覆盖访问端电脑的位置。
  assert.match(planningSource, /recenterAircraftToken:\s*0/)
  assert.match(planningSource, /export function requestAircraftRecenter \(\)/)
  assert.doesNotMatch(waylineSource, /requestAircraftRecenter/)
  assert.match(gmapSource, /@click="locateAircraftPosition"/)
  // 显式请求时仍支持有位置立即居中，或等待下一次飞机位置后居中一次。
  assert.match(overlaysSource, /watch\(\(\) => planningState\.recenterAircraftToken/)
  assert.match(overlaysSource, /pendingAircraftRecenter = true/)
  assert.match(overlaysSource, /if \(pendingAircraftRecenter\) \{[\s\S]*pendingAircraftRecenter = false[\s\S]*setAircraftView\(position\)/)
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
  const overlaysSource = readSource('src/hooks/use-planner-overlays.ts')
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
  assert.match(gmapSource, /import \{ applyTiandituLayer \} from '\/@\/hooks\/tianditu'/)
  assert.match(gmapSource, /const apply = \(\) => applyTiandituLayer\(map, layer\)/)
  assert.match(gmapSource, /map\.getLayer\('td-img'\)/)
  assert.match(gmapSource, /map\.on\('styledata', onData\)/)
  assert.match(gmapSource, /applyWaylineDefaultLayer/)
  assert.match(gmapSource, /watch\(\(\) => planningState\.active/)
  assert.match(gmapSource, /useMapMeasure\(\(\) => root\?\.\$map\)/)
  assert.match(gmapSource, /measure\.start\(\)/)
  assert.match(gmapSource, /measure\.stop\(\)/)
  assert.match(overlaysSource, /selectWaypoint\(wp\.id\)/)
  assert.match(overlaysSource, /planner-wp-marker/)
  assert.match(overlaysSource, /planner-wp-marker--selected/)
  assert.match(overlaysSource, /planner-seg-label/)
  assert.match(overlaysSource, /planner-home-marker/)
  assert.match(overlaysSource, /planner-dir-arrow/)
  assert.match(overlaysSource, /arrowMarkers\.push\(m\)/)

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
