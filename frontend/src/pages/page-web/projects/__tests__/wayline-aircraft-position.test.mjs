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

test('wayline planning controls open from the left panel entry', () => {
  const waylineSource = readSource('src/pages/page-web/projects/wayline.vue')

  assert.match(waylineSource, /class="wayline-header-actions"/)
  assert.match(waylineSource, /title="导入航线"/)
  assert.match(waylineSource, /:title="planningOverlayOpen \? '收起航线规划' : '创建新航线'"/)
  assert.match(waylineSource, /@click="togglePlanningOverlay"/)
  assert.match(waylineSource, /<MinusOutlined v-if="planningOverlayOpen" \/>/)
  assert.match(waylineSource, /<PlusOutlined v-else \/>/)
  assert.doesNotMatch(waylineSource, /class="wayline-planning-entry"/)
  assert.match(waylineSource, /<div v-if="planningOverlayOpen" class="wayline-map-planning-bar">/)
  assert.match(waylineSource, /<div v-if="planningOverlayOpen && planningDrawerOpen" class="wayline-map-planning-drawer">/)
  assert.match(waylineSource, /<span>航线规划<\/span>/)
  assert.match(waylineSource, /function togglePlanningOverlay \(\) \{[\s\S]*if \(planningOverlayOpen\.value\) \{[\s\S]*planningOverlayOpen\.value = false[\s\S]*planningDrawerOpen\.value = false[\s\S]*return[\s\S]*\}[\s\S]*planningOverlayOpen\.value = true[\s\S]*planningDrawerOpen\.value = true[\s\S]*\}/)
  assert.match(waylineSource, /const planningOverlayOpen = ref\(false\)/)
  assert.match(waylineSource, /MinusOutlined/)
  assert.match(waylineSource, /DownOutlined/)
  assert.match(waylineSource, /class="wayline-map-planning-action-stack"/)
  assert.match(waylineSource, /class="wayline-map-collapse-button"/)
  assert.doesNotMatch(waylineSource, /class="wayline-map-planning-drawer-actions"[\s\S]*class="wayline-map-collapse-button"/)
  assert.match(waylineSource, /\.wayline-map-planning-bar \{[\s\S]*width: min\(480px, calc\(100% - 36px\)\);/)
  assert.match(waylineSource, /\.wayline-map-planning-bar \{[\s\S]*grid-template-columns: max-content repeat\(3, minmax\(58px, 1fr\)\) auto;/)
  assert.match(waylineSource, /\.wayline-map-planning-title \{[\s\S]*margin-right: 8px;/)
  assert.match(waylineSource, /\.wayline-map-planning-drawer \{[\s\S]*top: 112px;[\s\S]*left: 18px;[\s\S]*width: min\(480px, calc\(100% - 36px\)\);/)
  assert.match(waylineSource, /\.wayline-map-planning-bar \{[\s\S]*background: rgba\(31, 31, 31, 0\.74\);/)
  assert.match(waylineSource, /\.wayline-map-planning-drawer,[\s\S]*\.wayline-map-planning-mini \{[\s\S]*background: rgba\(31, 31, 31, 0\.74\);/)
  assert.match(waylineSource, /\.wayline-map-planning-drawer-grid \{[\s\S]*grid-template-columns: repeat\(2, minmax\(0, 1fr\)\);/)
  assert.match(waylineSource, /\.wayline-map-planning-command-grid \{[\s\S]*grid-template-columns: repeat\(3, minmax\(0, 1fr\)\);/)
  assert.doesNotMatch(waylineSource, /class="wayline-map-planning-drawer-primary-actions"/)
  assert.doesNotMatch(waylineSource, /<span class="planning-label">飞行器（可选）<\/span>/)
  assert.match(waylineSource, /class="wayline-map-planning-actions"[\s\S]*@click="onStartPlacing"/)
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
  assert.match(gmapSource, /map\.setLayers\(\[standard, satellite\]\)/)
  assert.match(gmapSource, /watch\(\(\) => planningState\.active/)
  assert.match(gmapSource, /AMap\.RangingTool/)
  assert.match(gmapSource, /AMap\.TileLayer\.Satellite/)
  assert.match(gmapSource, /AMap\.createDefaultLayer/)
  assert.match(gmapSource, /selectWaypoint\(wp\.id\)/)
  assert.match(gmapSource, /wayline-planning-marker--start/)
  assert.match(gmapSource, /wayline-planning-marker--selected/)
  assert.match(gmapSource, /showDir:\s*true/)

  assert.match(waylineSource, /planningRouteStats/)
  assert.match(waylineSource, /formatRouteDistance/)
  assert.match(waylineSource, /formatRouteDuration/)
  assert.match(waylineSource, /航程/)
  assert.match(waylineSource, /预计/)
  assert.match(waylineSource, /selected:\s*planningState\.selectedWaypointId === wp\.id/)
  assert.match(waylineSource, /@click="selectPlanningWaypoint\(wp\.id\)"/)
})
