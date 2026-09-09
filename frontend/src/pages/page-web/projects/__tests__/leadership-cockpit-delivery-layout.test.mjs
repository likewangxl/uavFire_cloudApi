import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('delivery execution tab uses the shared visual stage and instrument belt', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const deliveryPanelSource = readSource('src/components/cockpit/CockpitDeliveryExecutionPanel.vue')

  assert.match(
    cockpitSource,
    /class="visual-stage"/,
    'delivery execution should share the same visual-stage container as the map and monitor tabs'
  )
  assert.match(
    cockpitSource,
    /class="visual-instrument-belt"/,
    'delivery execution should keep the lower continuous instrument belt instead of hiding it'
  )
  assert.doesNotMatch(
    cockpitSource,
    /v-if="activeVisualTab === 'map'"\s+class="map-kpi-grid"/,
    'context metrics should no longer be map-only'
  )
  assert.doesNotMatch(
    deliveryPanelSource,
    /min-height:\s*clamp\(560px,\s*64vh,\s*860px\)/,
    'delivery live frame should not keep a taller independent stage height'
  )
  assert.doesNotMatch(
    deliveryPanelSource,
    /aspect-ratio:\s*16\s*\/\s*9/,
    'delivery live frame should fill the shared visual stage instead of enforcing a separate ratio'
  )
  assert.match(
    deliveryPanelSource,
    /border-radius:\s*26px/,
    'delivery live frame should share the rounded cockpit stage shape'
  )
  assert.match(
    deliveryPanelSource,
    /delivery-live-badge/,
    'delivery live frame should show a primary picture badge'
  )
  assert.match(
    deliveryPanelSource,
    /delivery-flight-hud/,
    'delivery live frame should place status HUD in the lower-left overlay'
  )
  assert.doesNotMatch(
    deliveryPanelSource,
    /dual-stream-preview|delivery-preview/,
    'delivery execution should not render an infrared preview window'
  )
  assert.match(
    deliveryPanelSource,
    /object-fit:\s*contain/,
    'video should preserve aspect ratio without cropping'
  )
})

test('delivery cockpit header and stage can shrink inside the center panel', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /\.map-header\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?flex-wrap:\s*wrap;/,
    'map header should wrap instead of pushing under the right status column'
  )
  assert.match(
    cockpitSource,
    /\.map-header-actions\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?flex-wrap:\s*wrap;/,
    'tab controls and status pill should wrap within the map panel'
  )
  assert.match(
    cockpitSource,
    /\.livestream-stage\s*\{[\s\S]*?min-width:\s*0;/,
    'live stage should not keep an intrinsic width larger than the center panel'
  )
  assert.match(
    cockpitSource,
    /\.dual-stream-shell\s*\{[\s\S]*?min-width:\s*0;/,
    'stream shell should shrink with the center grid column'
  )
  assert.match(
    cockpitSource,
    /\.dual-stream-stage-head\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?flex-wrap:\s*wrap;/,
    'stream stage header should wrap status controls inside the center panel'
  )
})

test('delivery execution panel does not keep a stale intrinsic width', () => {
  const deliveryPanelSource = readSource('src/components/cockpit/CockpitDeliveryExecutionPanel.vue')

  assert.match(
    deliveryPanelSource,
    /\.delivery-execution-panel\s*\{[\s\S]*?min-width:\s*0;/,
    'delivery execution root should shrink inside the cockpit center panel'
  )
  assert.match(
    deliveryPanelSource,
    /\.delivery-live-frame\s*\{[\s\S]*?min-width:\s*0;/,
    'delivery live frame should not keep the previous wider stage width'
  )
})

test('delivery execution view does not render duplicate offline status badges', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /<span\s+v-if="activeVisualTab !== 'delivery-execution'"[\s\S]*?class="status-pill"[\s\S]*?:class="visualPanelPillClass"[\s\S]*?>/,
    'top-right visual status pill should be hidden on delivery execution tab'
  )
  assert.doesNotMatch(
    cockpitSource,
    /role="delivery"[\s\S]{0,360}<span class="status-pill" :class="deliveryPanelPillClass">/,
    'delivery stage header should not repeat the FC100 offline status pill beside the selector'
  )
})

test('delivery execution target list excludes rc controller devices', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /function isFc100DeliveryAircraftDevice \(device: DeliveryDeviceDTO\)/,
    'delivery target filtering should use an explicit aircraft-device predicate'
  )
  assert.match(
    cockpitSource,
    /\.filter\((?:isFc100DeliveryAircraftDevice\)|device => isFc100DeliveryAircraftDevice\(device\) && !isMockCockpitDeviceSn\(device\.deviceSn\)\))/,
    'delivery target list should filter out non-aircraft delivery devices before mapping targets'
  )
  assert.match(
    cockpitSource,
    /deviceType[\s\S]*bindStatus[\s\S]*!== 'rc'/,
    'rc devices reported through deviceType or bindStatus should not appear in the selector'
  )
})

test('delivery execution selector uses compact full model and SN labels', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const selectorSource = readSource('src/components/cockpit/CockpitAircraftStreamSelector.vue')

  assert.match(
    cockpitSource,
    /callsign:\s*model/,
    'delivery selector should show the resolved full device model instead of a generated callsign suffix'
  )
  assert.match(
    cockpitSource,
    /DJI Flycart100/,
    'delivery selector should resolve FC100 devices to the full DJI Flycart100 model label'
  )
  assert.match(
    cockpitSource,
    /0-122-0/,
    'delivery selector should normalize DJI numeric FC100 model keys returned by Delivery Sync'
  )
  assert.match(
    cockpitSource,
    /compactLabel:\s*true/,
    'delivery selector targets should opt into compact model and SN display'
  )
  assert.doesNotMatch(
    cockpitSource,
    /callsign:\s*`FC100 投放/,
    'delivery selector should not show unexplained FC100 投放 suffix labels'
  )
  assert.doesNotMatch(
    cockpitSource,
    /aircraftMode[\s\S]{0,120}`模式/,
    'delivery selector should not put aircraft mode into the dropdown subtitle'
  )
  assert.match(
    selectorSource,
    /<span\s+v-if="!target\.compactLabel"\s+class="option-subtitle">/,
    'compact delivery labels should hide the dropdown subtitle details'
  )
})
