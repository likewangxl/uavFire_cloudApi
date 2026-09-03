import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { buildCockpitSummary } from '../leadership-cockpit-summary.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

function buildRichSummary () {
  return buildCockpitSummary({
    fireEvents: [
      {
        eventId: 'high-1',
        fireLevel: 'HIGH',
        status: 'NEW',
        missionNo: '',
        geoQuality: 'AUTO_WAYPOINT_READY',
        confidence: 0.95,
        latitude: 34.66791,
        longitude: 109.32667,
        lastSeenTime: 1760000000000
      }
    ],
    aiEvents: [
      { riskLevel: 'HIGH', fusionScore: 0.91, eventTimestamp: 1760000005000 }
    ],
    msdkDevices: [
      {
        aircraftSn: 'M30T-001',
        model: 'M30T',
        online: true,
        batteryPercent: 67,
        gpsCount: 18,
        rtkCount: 12,
        height: 88.4,
        horizontalSpeed: 12.4,
        verticalSpeed: -1.2,
        windSpeed: 3.6
      }
    ],
    deliveryTargets: [
      {
        deviceSn: 'FC100-001',
        callsign: 'FC100',
        online: true,
        streamStatus: 'running',
        taskStatus: 'BOUND',
        batteryPercent: 76,
        primaryPlayUrl: 'webrtc://fc100/live'
      }
    ],
    deliveryTaskStatuses: [
      { taskId: 'task-1', status: 'RUNNING', phase: 'DELIVERING', progressPercent: 35, displayMessage: 'heading to target' }
    ],
    dualStreamGroup: {
      sessionState: 'RUNNING',
      visiblePlayUrl: 'webrtc://visible',
      thermalPlayUrl: 'webrtc://thermal'
    }
  })
}

test('builds visual context metrics for every cockpit visual tab', () => {
  const summary = buildRichSummary()

  assert.deepEqual(
    summary.visualContextMetrics.map.map(item => item.key),
    ['activeFireEvents', 'highestFireLevel', 'aiEvents', 'liveOnline']
  )
  assert.deepEqual(
    summary.visualContextMetrics['fire-monitor'].map(item => item.key),
    ['monitorTarget', 'visibleStream', 'thermalStream', 'aiRecent', 'flightHud']
  )
  assert.equal(summary.visualContextMetrics['fire-monitor'].find(item => item.key === 'monitorTarget').value, 'M30T')
  assert.deepEqual(
    summary.visualContextMetrics['delivery-execution'].map(item => item.key),
    ['deliveryTarget', 'liveSource', 'taskPhase', 'taskProgress', 'deliveryBattery']
  )
  assert.equal(summary.visualContextMetrics['delivery-execution'].find(item => item.key === 'deliveryTarget').value, 'FC100')
  assert.equal(summary.visualContextMetrics['delivery-execution'].find(item => item.key === 'taskPhase').value, 'DELIVERING')
  assert.equal(summary.visualContextMetrics['delivery-execution'].find(item => item.key === 'taskProgress').value, '35%')
})

test('builds a continuous instrument belt below each visual tab', () => {
  const summary = buildRichSummary()

  for (const tabKey of ['map', 'fire-monitor', 'delivery-execution']) {
    assert.deepEqual(
      summary.visualInstrumentBelt[tabKey].sections.map(item => item.key),
      ['videoLink', 'flightReadouts', 'aiObservation'],
      `${tabKey} should use one non-card instrument belt structure`
    )
    assert.deepEqual(
      summary.visualInstrumentBelt[tabKey].events.map(item => item.key),
      ['osd', 'thermal', 'aiTask'],
      `${tabKey} should expose a compact system event strip`
    )
  }

  const fireMonitorBelt = summary.visualInstrumentBelt['fire-monitor']
  assert.equal(fireMonitorBelt.sections.find(item => item.key === 'videoLink').signals.find(item => item.key === 'visible').status, 'online')
  assert.equal(fireMonitorBelt.sections.find(item => item.key === 'videoLink').signals.find(item => item.key === 'thermal').status, 'online')
  assert.equal(fireMonitorBelt.sections.find(item => item.key === 'flightReadouts').readouts.find(item => item.key === 'ALT').value, '88.4')
  assert.match(fireMonitorBelt.sections.find(item => item.key === 'aiObservation').summary, /1/)

  const deliveryBelt = summary.visualInstrumentBelt['delivery-execution']
  assert.equal(deliveryBelt.sections.find(item => item.key === 'flightReadouts').readouts.find(item => item.key === 'BATT').value, '76%')
  assert.match(deliveryBelt.events.find(item => item.key === 'aiTask').label, /35%/)
})

test('builds side health rows and explicit empty-state hints from real data availability', () => {
  const healthy = buildCockpitSummary({
    fireEvents: [{ eventId: 'event-1', fireLevel: 'LOW', status: 'NEW' }],
    aiEvents: [],
    msdkDevices: [{ aircraftSn: 'M30T-001', online: true }],
    deliveryTargets: [{ deviceSn: 'FC100-001', callsign: 'FC100', online: true, streamStatus: 'idle' }],
    dualStreamGroup: { sessionState: 'IDLE' }
  })

  assert.deepEqual(
    healthy.sideHealthRows.map(item => item.key),
    ['backendApi', 'dualStream', 'zlmPlayback', 'deliveryPlatform', 'dataRefresh']
  )
  assert.equal(healthy.sideHealthRows.find(item => item.key === 'backendApi').value, '正常')
  assert.equal(healthy.sideHealthRows.find(item => item.key === 'dualStream').label, 'MSDK Agent 连接设备')
  assert.equal(healthy.sideHealthRows.find(item => item.key === 'dualStream').value, '1/1')
  assert.equal(healthy.sideHealthRows.find(item => item.key === 'deliveryPlatform').label, '司运平台连接设备')
  assert.equal(healthy.sideHealthRows.find(item => item.key === 'deliveryPlatform').value, '1/1')
  assert.notEqual(healthy.sideHealthRows.find(item => item.key === 'dualStream').label, '双光直播')
  assert.notEqual(healthy.sideHealthRows.find(item => item.key === 'deliveryPlatform').label, 'FC100 平台')
  assert.match(healthy.emptyStateHints.aiRisk, /AI/)
  assert.match(healthy.emptyStateHints.aircraft, /2/)

  const partial = buildCockpitSummary({
    fireEventsError: 'fire-events-unavailable',
    aiEventsError: 'ai-events-unavailable',
    dualStreamError: 'dual-stream-unavailable'
  })

  assert.equal(partial.sideHealthRows.find(item => item.key === 'backendApi').value, '部分异常')
  assert.match(partial.emptyStateHints.aiRisk, /ai-events-unavailable/)
  assert.match(partial.emptyStateHints.fireEvents, /fire-events-unavailable/)
})

test('builds fire queue stats and aircraft role groups for side panels', () => {
  const summary = buildCockpitSummary({
    fireEvents: [
      { eventId: 'new-1', fireLevel: 'HIGH', status: 'NEW', missionNo: '', geoQuality: 'AUTO_WAYPOINT_READY' },
      { eventId: 'linked-1', fireLevel: 'MEDIUM', status: 'MISSION_CREATED', missionNo: 'MISSION-001', geoQuality: 'DEM_MISSING' }
    ],
    msdkDevices: [
      { aircraftSn: 'M30T-001', model: 'M30T', online: true }
    ],
    deliveryTargets: [
      { deviceSn: 'FC100-001', callsign: 'FC100', online: true, streamStatus: 'running' }
    ]
  })

  assert.deepEqual(
    summary.fireQueueStats.map(item => item.key),
    ['pending', 'missionLinked', 'routeReady']
  )
  assert.equal(summary.fireQueueStats.find(item => item.key === 'pending').value, '1')
  assert.equal(summary.fireQueueStats.find(item => item.key === 'missionLinked').value, '1')
  assert.equal(summary.fireQueueStats.find(item => item.key === 'routeReady').value, '1')
  assert.deepEqual(summary.aircraftGroups.map(group => group.key), ['monitor', 'delivery'])
  assert.equal(summary.aircraftGroups.find(group => group.key === 'monitor').rows[0].name, 'M30T')
  assert.equal(summary.aircraftGroups.find(group => group.key === 'delivery').rows[0].name, 'DJI Flycart100')
})

test('cockpit visual tabs share one stage and one instrument belt', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const deliveryPanelSource = readSource('src/components/cockpit/CockpitDeliveryExecutionPanel.vue')

  assert.match(
    cockpitSource,
    /class="visual-stage"/,
    'all visual tabs should be mounted inside a shared visual-stage container'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="\{ 'live-mode': activeVisualTab !== 'map' \}"/,
    'live tabs should not switch the center panel into a different row-height mode'
  )
  assert.match(
    cockpitSource,
    /class="visual-instrument-belt"/,
    'the lower empty area should render a shared continuous instrument belt'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="visual-insight-card"/,
    'the lower area should not use repeated intelligence cards'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="visual-context-grid"/,
    'the lower area should no longer use a KPI card grid'
  )
  assert.match(
    cockpitSource,
    /\.map-panel\s*\{[\s\S]*?grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;/,
    'center panel should reserve a fixed lower row so the instrument belt cannot be clipped'
  )
  assert.match(
    cockpitSource,
    /\.leadership-cockpit\s*\{[\s\S]*?min-height:\s*calc\(100vh - 60px\);[\s\S]*?overflow:\s*auto;/,
    'cockpit page should remain scrollable instead of compressing the center visual row into the viewport'
  )
  assert.match(
    cockpitSource,
    /\.content-grid\s*\{[\s\S]*?min-height:\s*clamp\(860px,\s*82vh,\s*1120px\);[\s\S]*?height:\s*auto;/,
    'content grid should keep the situation and live visuals tall while allowing side columns to render fully'
  )
  assert.match(
    cockpitSource,
    /\.visual-stage\s*\{[\s\S]*?height:\s*100%;/,
    'visual-stage should shrink inside the reserved center row instead of pushing the lower belt out'
  )
  assert.doesNotMatch(
    deliveryPanelSource,
    /min-height:\s*clamp\(560px,\s*64vh,\s*860px\)/,
    'delivery live frame should no longer keep a taller independent height rule'
  )
})

test('highlighted summary cards render rich hover popovers instead of native-only titles', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /<a-popover[\s\S]*?overlayClassName="cockpit-summary-popover"/,
    'highlighted summary metrics should use Ant Design popovers for rich hover details'
  )
  assert.match(
    cockpitSource,
    /item\.detailPopover/,
    'summary card rendering should branch on structured detailPopover data'
  )
  assert.match(
    cockpitSource,
    /popover\.rows/,
    'the popover should render event and aircraft detail rows from summary data'
  )
  assert.match(
    cockpitSource,
    /class="shell-card summary-card interactive"[\s\S]*?:class="item\.tone"[\s\S]*?>/,
    'rich popover cards should render without a native title attribute on the card'
  )
})

test('map tab renders real geographic situation layers with optional Tellux mode', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const situationMapSource = readSource('src/pages/page-web/projects/CockpitSituationMap.vue')
  const situationSource = readSource('src/pages/page-web/projects/leadership-cockpit-situation.mjs')
  const baseMapSource = readSource('src/hooks/tianditu.ts')

  assert.match(
    cockpitSource,
    /<CockpitSituationMap/,
    'map tab should render the real geographic situation map component'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="fire-zone fire-major"/,
    'map tab should not depend on the static fire-zone as the primary fire layer'
  )
  assert.match(
    situationMapSource,
    /maplibregl\.Map/,
    'situation map should use the existing MapLibre stack'
  )
  assert.match(
    situationMapSource,
    /import \{ buildTiandituStyle \} from '\/@\/hooks\/tianditu'/,
    'situation map should reuse the shared base-map style builder'
  )
  assert.match(
    situationMapSource,
    /buildTiandituStyle\('satellite'\)/,
    'situation map should default to the satellite imagery view'
  )
  assert.match(
    situationMapSource,
    />\s*高清卫星影像\s*<\/button>/,
    'flat map toggle should identify the trial satellite imagery'
  )
  assert.match(
    baseMapSource,
    /mt3\.googlecnapps\.club\/maps\/vt\?lyrs=s&x=\{x\}&y=\{y\}&z=\{z\}&src=app&scale=2&from=app/,
    'satellite mode should use the configured trial XYZ tile source'
  )
  assert.match(
    baseMapSource,
    /tiles:\s*\[SATELLITE_TILE_URL\][\s\S]*?tileSize:\s*256[\s\S]*?maxzoom:\s*20/,
    'the 2x satellite image should retain a 256px logical XYZ tile size through zoom 20'
  )
  assert.match(
    situationMapSource,
    /:deep\(\.maplibregl-ctrl-bottom-left\)[\s\S]*?bottom:\s*52px;/,
    'MapLibre scale should sit above the fire priority legend instead of overlapping it'
  )
  assert.doesNotMatch(
    situationMapSource,
    /t\{s\}\.tianditu\.gov\.cn/,
    'MapLibre tile URLs should not use the unsupported Leaflet-style {s} subdomain placeholder'
  )
  assert.match(
    situationMapSource,
    /fireMarkers|routeLines|aircraftMarkers|errorCircles/,
    'situation map should render fire, route, aircraft, and error-radius layers'
  )
  assert.match(
    situationSource,
    /loadTelluxModule[\s\S]*?@vite-ignore/,
    'Tellux should be loaded dynamically so it does not block the default cockpit bundle'
  )
})

test('terrain tab loads Tellux dynamically and keeps MapLibre terrain as fallback only', () => {
  const situationMapSource = readSource('src/pages/page-web/projects/CockpitSituationMap.vue')
  const telluxAdapterSource = readSource('src/pages/page-web/projects/tellux-situation-adapter.ts')

  assert.match(
    situationMapSource,
    /viewMode\s*=\s*ref<'2d' \| 'tellux'>/,
    'situation map should expose a Tellux mode alongside flat 2D'
  )
  assert.match(
    situationMapSource,
    /if \(!quantizedMeshTerrainUrl && !city3dTilesUrl && !telluxModuleUrl\)[\s\S]*?activateFallbackTerrain\(\)/,
    '3D mode should use MapLibre DEM fallback when no real terrain or city model source is configured'
  )
  assert.match(
    situationMapSource,
    /loadTelluxModule\(telluxModuleUrl,\s*\(\)\s*=>\s*import\('\.\/tellux-situation-adapter'\)\)/,
    'terrain mode should dynamically load Tellux when a usable terrain source or module is configured'
  )
  assert.match(
    situationMapSource,
    /mountTelluxStage/,
    'loaded Tellux modules should receive the shared SituationLayer data and container'
  )
  assert.match(
    situationMapSource,
    /type:\s*'raster-dem'/,
    'MapLibre terrain should remain available as a fallback preview'
  )
  assert.match(
    situationMapSource,
    /telluxFallbackActive/,
    'fallback state should be explicit instead of pretending MapLibre terrain is Tellux'
  )
  assert.match(
    situationMapSource,
    /quantizedMeshTerrainUrl/,
    'Tellux configuration should include a quantized-mesh terrain URL option'
  )
  assert.match(
    telluxAdapterSource,
    /new tellux\.Viewer/,
    'built-in adapter should instantiate the real Tellux viewer'
  )
  assert.match(
    telluxAdapterSource,
    /terrain:\s*options\.quantizedMeshTerrainUrl[\s\S]*?\{\s*url:\s*options\.quantizedMeshTerrainUrl\s*\}/,
    'Tellux adapter should pass quantized-mesh terrain URLs into Tellux terrain.url'
  )
  assert.match(
    situationMapSource,
    /VITE_CITY_3D_TILES_URL/,
    '3D city mode should accept an authorized standard 3D Tiles endpoint'
  )
  assert.match(
    telluxAdapterSource,
    /viewer\.load3DTileset\([\s\S]*?type:\s*'url'[\s\S]*?url:\s*options\.city3dTilesUrl/,
    'Tellux adapter should load the configured photogrammetry city tileset'
  )
  assert.match(
    telluxAdapterSource,
    /source:\s*\{\s*type:\s*'geojson'/,
    'Tellux adapter should reuse SituationLayer route and error-circle data as GeoJSON overlays'
  )
  assert.match(
    telluxAdapterSource,
    /viewer\.scene\.threeScene\.add\(businessRoot\)/,
    'Tellux adapter should add fire and aircraft business objects to the Tellux Three.js scene'
  )
})

test('side columns match the center cockpit height and expose all fire events action', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /\.content-grid\s*\{[\s\S]*?align-items:\s*stretch;/,
    'content grid should stretch side columns to the center panel height'
  )
  assert.match(
    cockpitSource,
    /\.column\s*\{[\s\S]*?height:\s*100%;[\s\S]*?max-height:\s*100%;/,
    'side columns should fill the row height without extending below the center'
  )
  assert.match(
    cockpitSource,
    /\.column-scroll\s*\{[\s\S]*?overflow:\s*visible;/,
    'side columns should render their content fully and let the page scroll'
  )
  assert.match(
    cockpitSource,
    /\.column-scroll\s*\{[\s\S]*?padding-bottom:\s*0;/,
    'side column scroll areas should end flush with the center panel while relying on internal scrolling'
  )
  assert.match(
    cockpitSource,
    /class="column column-scroll right-status-column"/,
    'right rail should use a dedicated stretching layout instead of the generic side column spacing'
  )
  assert.match(
    cockpitSource,
    /\.right-status-column\s*\{[\s\S]*?grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);[\s\S]*?align-content:\s*stretch;/,
    'right rail should let the link status panel absorb remaining height so its bottom aligns with the center'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="view-all-fire-events"/,
    'fire queue should not keep a bottom action that creates a visible height gap'
  )
  assert.match(
    cockpitSource,
    /to="\/fire-events"/,
    'view all fire events action should navigate to the fire event list route'
  )
  assert.match(
    cockpitSource,
    /class="fire-events-panel-header-action"[\s\S]*?\{\{ cockpitSummary\.activeFireEvents\.length \}\}/,
    'header action should show the total active fire event count'
  )
  assert.match(
    cockpitSource,
    /class="fire-events-panel-title"[\s\S]*?class="fire-events-panel-header-action"[\s\S]*?查看更多/,
    'fire queue should expose a visible header action instead of only a bottom link after the event list'
  )
})

test('visual tabs use an explicit switch handler so fire monitor clicks render the livestream panel', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /@click="switchVisualTab\(tab\.key\)"/,
    'visual tabs should call a handler instead of only assigning active tab inline'
  )
  assert.match(
    cockpitSource,
    /function switchVisualTab[\s\S]*?activeVisualTab\.value = tab[\s\S]*?if \(tab === 'fire-monitor'\)[\s\S]*?syncLivePlayers\(\)/,
    'switching to fire monitor should immediately sync live players after the DOM updates'
  )
  assert.match(
    cockpitSource,
    /v-else-if="activeVisualTab === 'fire-monitor'" class="livestream-stage dual-stream-stage"/,
    'fire monitor tab should render the dual-stream livestream stage'
  )
})

test('left panels render differentiated AI radar and fire priority queue surfaces', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /class="shell-card panel-card ai-radar-panel"/,
    'AI recognition should use a dedicated radar panel surface instead of a generic panel card'
  )
  assert.match(
    cockpitSource,
    /class="ai-radar-stage"/,
    'AI recognition should include a radar-stage visual module'
  )
  assert.match(
    cockpitSource,
    /class="ai-radar-channel-strip"/,
    'AI recognition should expose visible, thermal, and fusion channel chips'
  )
  assert.match(
    cockpitSource,
    /class="shell-card panel-card fire-priority-panel"/,
    'fire events should use a dedicated priority queue panel surface'
  )
  assert.match(
    cockpitSource,
    /class="column column-scroll left-ops-column"[\s\S]*?class="shell-card panel-card response-closure-panel"/,
    'left rail should include a response closure panel so its content fills the cockpit height'
  )
  assert.match(
    cockpitSource,
    /v-for="item in leftResponseItems"[\s\S]*?class="response-closure-item"/,
    'response closure panel should render operational readiness items from cockpit data'
  )
  assert.match(
    cockpitSource,
    /\.left-ops-column\s*\{[\s\S]*?grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;/,
    'left rail should use a three-part layout that keeps its bottom aligned with the center and right columns'
  )
  assert.match(
    cockpitSource,
    /\.fire-priority-panel\s*\{[\s\S]*?border-color:\s*rgba\(69,\s*221,\s*255,\s*0\.28\);/,
    'fire priority panel border should match the cockpit blue panel border color'
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-queue"/,
    'fire events should render as a grouped priority queue rather than a repeated generic info list'
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-row"/,
    'fire queue rows should have their own row structure'
  )
  assert.match(
    cockpitSource,
    /\.fire-priority-row::before\s*\{[\s\S]*?width:\s*3px;/,
    'fire priority rows should use a compact severity rail'
  )
})

test('left redesign preserves the mockup hierarchy with icons, a wider rail, and table rows', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /grid-template-columns:\s*460px\s+minmax\(0,\s*1fr\)\s+340px;/,
    'left cockpit rail should be widened so fire queue rows do not collapse'
  )
  assert.match(
    cockpitSource,
    /<VideoCameraOutlined[\s\S]*?<FireOutlined[\s\S]*?<DeploymentUnitOutlined/,
    'AI channel chips should use visible icon components, not text-only pills'
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-table-head"[\s\S]*?优先级[\s\S]*?置信度[\s\S]*?位置 \/ 区域[\s\S]*?任务状态/,
    'fire priority queue should render a compact four-field table header'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="fire-priority-table-head"[\s\S]*?事件 ID \/ 类型/
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="fire-priority-table-head"[\s\S]*?任务绑定/
  )
  assert.match(
    cockpitSource,
    /\.fire-priority-table-head,\s*\.fire-priority-row\s*\{[\s\S]*?grid-template-columns:\s*44px\s+68px\s+minmax\(126px,\s*1fr\)\s+82px;/,
    'fire priority rows should use four larger readable columns'
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-level"[\s\S]*?class="fire-priority-confidence"[\s\S]*?class="fire-priority-location"[\s\S]*?class="fire-priority-status-text"/,
    'fire queue row fields should be separated into priority, confidence, location, and task status cells'
  )
  assert.doesNotMatch(cockpitSource, /class="fire-priority-id"/)
  assert.doesNotMatch(cockpitSource, /class="fire-priority-mission"/)
  assert.match(cockpitSource, /\.fire-priority-confidence\s*\{[\s\S]*?font-size:\s*14px;/)
})

test('fire priority queue keeps primary rows compact and moves event details into hover popovers', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /<a-popover[\s\S]*?overlayClassName="fire-event-detail-popover"[\s\S]*?class="fire-priority-row"/,
    'each fire queue row should expose a hover detail popover'
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-table-head"[\s\S]*?优先级[\s\S]*?置信度[\s\S]*?位置 \/ 区域[\s\S]*?任务状态/
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="fire-priority-table-head"[\s\S]*?事件 ID \/ 类型/
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="fire-priority-table-head"[\s\S]*?任务绑定/
  )
  assert.match(
    cockpitSource,
    /\.fire-priority-table-head,\s*\.fire-priority-row\s*\{[\s\S]*?grid-template-columns:\s*44px\s+68px\s+minmax\(126px,\s*1fr\)\s+82px;/
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-status-text"[\s\S]*?formatFireStatusLabel\(event\.status\)/
  )
  assert.match(
    cockpitSource,
    /class="fire-event-detail-panel"[\s\S]*?事件 ID[\s\S]*?event\.eventId[\s\S]*?完整坐标[\s\S]*?formatFireEventLocation\(event\)[\s\S]*?任务编号[\s\S]*?event\.missionNo/
  )
  assert.match(
    cockpitSource,
    /:global\(.fire-event-detail-popover[\s\S]*?:global\(.fire-event-detail-panel/
  )
})

test('fire queue long identifiers move out of rows and into hover details', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.doesNotMatch(cockpitSource, /class="fire-priority-id"/)
  assert.doesNotMatch(cockpitSource, /class="fire-priority-mission"/)
  assert.match(cockpitSource, /事件 ID[\s\S]*?\{\{ event\.eventId \}\}/)
  assert.match(cockpitSource, /任务编号[\s\S]*?\{\{ event\.missionNo \|\| '未关联任务' \}\}/)
})

test('right rail uses operational status cards instead of generic stacked info cards', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const summarySource = readSource('src/pages/page-web/projects/leadership-cockpit-summary.mjs')

  assert.match(
    cockpitSource,
    /class="shell-card panel-card aircraft-status-panel"[\s\S]*?class="aircraft-status-overview"[\s\S]*?在线节点[\s\S]*?直播链路/
  )
  assert.match(
    cockpitSource,
    /class="aircraft-node-list"[\s\S]*?class="aircraft-node-card"[\s\S]*?class="aircraft-node-meter"/
  )
  assert.match(
    cockpitSource,
    /class="shell-card panel-card link-status-panel"[\s\S]*?class="link-status-timeline"[\s\S]*?class="link-node-card"/
  )
  assert.match(
    cockpitSource,
    /v-for="task in cockpitSummary\.taskRows"[\s\S]*?class="link-node-card primary"/
  )
  assert.match(
    cockpitSource,
    /v-for="row in cockpitSummary\.sideHealthRows"[\s\S]*?class="link-node-card"/
  )
  assert.doesNotMatch(
    cockpitSource,
    /<div class="info-list">[\s\S]*?cockpitSummary\.sideHealthRows/
  )
  assert.match(
    cockpitSource,
    /\.aircraft-status-panel[\s\S]*?\.aircraft-node-card/
  )
  assert.match(
    cockpitSource,
    /\.link-status-panel[\s\S]*?\.link-node-card/
  )
  assert.match(
    summarySource,
    /key:\s*'delivery'[\s\S]*?label:\s*'投放设备'/,
    'right rail delivery group should be labelled as delivery equipment'
  )
  assert.doesNotMatch(
    cockpitSource,
    /class="aircraft-node-main"[\s\S]*?<span>\{\{ group\.label \}\}<\/span>/,
    'aircraft node cards should not repeat the group label above the device name'
  )
  assert.doesNotMatch(summarySource, /label:\s*'FC100 投放机'/)
})

test('situation map does not reset camera on every data refresh after user navigation', () => {
  const situationMapSource = readSource('src/pages/page-web/projects/CockpitSituationMap.vue')

  assert.match(
    situationMapSource,
    /let hasInitialViewportFit = false/,
    'situation map should track whether the initial bounds fit has already happened'
  )
  assert.match(
    situationMapSource,
    /map\.on\('dragstart', markUserCameraInteraction\)[\s\S]*?map\.on\('zoomstart', markUserCameraInteraction\)/,
    'dragging or zooming should mark the camera as user-controlled'
  )
  assert.match(
    situationMapSource,
    /function fitInitialSituationBounds \(\)[\s\S]*?if \(hasInitialViewportFit \|\| userCameraInteraction\) return[\s\S]*?hasInitialViewportFit = true/,
    'initial fitting should run once and stop after user camera interaction'
  )
  assert.match(
    situationMapSource,
    /function renderLayers \(\)[\s\S]*?renderMarkers\(\)[\s\S]*?fitInitialSituationBounds\(\)[\s\S]*?}/,
    'layer refreshes should call the guarded initial fit instead of unconditional fitBounds'
  )
  assert.doesNotMatch(
    situationMapSource,
    /function renderLayers \(\)[\s\S]*?renderMarkers\(\)[\s\S]*?fitSituationBounds\(\)[\s\S]*?}/,
    'renderLayers should not unconditionally fit bounds on every periodic data refresh'
  )
})

test('cockpit annotations remove explanatory copy and make fire coordinates and delivery tasks readable', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  for (const removedCopy of [
    '火情识别 / 双光复核 / FC100 投放 / 飞机状态',
    '来自 dual-stream task events，展示最近识别和复核状态。',
    '领导视角聚焦火势范围、保护圈、力量投向、受威胁对象和处置效果。',
    '按风险等级和最近更新时间排序，展示当前重点处置对象。',
    '投放任务来自 delivery 接口；系统链路健康没有统一 health 汇总接口的部分明确标注。'
  ]) {
    assert.doesNotMatch(cockpitSource, new RegExp(removedCopy.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }

  assert.match(
    cockpitSource,
    /<h3>投放与链路状态<\/h3>/,
    'delivery status panel title should not over-specify FC100'
  )
  assert.doesNotMatch(
    cockpitSource,
    /<h4>\{\{ task\.taskName \|\| task\.taskId \|\| '投放任务' \}\}<\/h4>/,
    'raw delivery task UUID should not be the card title'
  )
  assert.match(
    cockpitSource,
    /class="link-node-head"[\s\S]*?\{\{ task\.taskName \|\| '投放任务' \}\}[\s\S]*?class="link-node-id"[\s\S]*?任务编号[\s\S]*?formatMiddleEllipsis\(task\.taskId \|\| task\.missionId,\s*8,\s*4,\s*'--'\)/,
    'delivery task identifiers should move to a labelled, shortened detail row'
  )
  assert.match(
    cockpitSource,
    /formatDeliveryTaskMessage\(task\)/,
    'delivery task cards should show a compact readable status message'
  )
  assert.doesNotMatch(
    cockpitSource,
    /\{\{ task\.message \|\| task\.displayMessage \|\| task\.reason/,
    'delivery task cards should not render raw backend messages with full identifiers'
  )
  assert.match(
    cockpitSource,
    /class="fire-priority-location"[\s\S]*?formatFireLocationSummary\(event\)[\s\S]*?formatFireErrorRadius\(event\)/,
    'fire rows should render a compact location summary and move full coordinates into hover details'
  )
  assert.match(
    cockpitSource,
    /class="fire-event-detail-list"[\s\S]*?完整坐标[\s\S]*?formatFireEventLocation\(event\)/,
    'full coordinates should remain available in the hover detail panel'
  )
})
