import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('delivery execution tab hides the lower KPI grid so live video can use the space', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const deliveryPanelSource = readSource('src/components/cockpit/CockpitDeliveryExecutionPanel.vue')

  assert.match(
    cockpitSource,
    /v-if="activeVisualTab !== 'delivery-execution'"\s+class="map-kpi-grid"/,
    'delivery tab should not render the lower KPI grid'
  )
  assert.doesNotMatch(
    cockpitSource,
    /const deliveryExecutionKpis = computed/,
    'delivery KPI cards should not be maintained for the hidden lower grid'
  )
  assert.match(
    deliveryPanelSource,
    /min-height:\s*clamp\(560px,\s*64vh,\s*860px\)/,
    'delivery live frame should expand into the recovered vertical space'
  )
  assert.match(
    deliveryPanelSource,
    /aspect-ratio:\s*16\s*\/\s*9/,
    'delivery live frame should use the same video stage ratio as fire monitoring'
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
