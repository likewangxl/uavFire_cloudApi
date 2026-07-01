import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('shared GMap does not load the screenshot-derived UOM flyable reference layer by default', () => {
  const gmapSource = readSource('src/components/GMap.vue')

  assert.doesNotMatch(gmapSource, /useUomAirspaceReferenceLayer/)
  assert.doesNotMatch(gmapSource, /uomAirspace/)
  assert.doesNotMatch(gmapSource, /uom-airspace-toolbox/)
})

test('DJI FlySafe imported zones remain available for planning compliance', () => {
  const complianceSource = readSource('src/hooks/use-flight-area-compliance.ts')

  assert.match(complianceSource, /dji_flysafe_xian\.json/)
  assert.match(complianceSource, /geojsonToZones/)
})

test('shared GMap loads flight area overlays so TSA can show DJI FlySafe zones', () => {
  const gmapSource = readSource('src/components/GMap.vue')

  assert.match(gmapSource, /import \{ loadFlightAreas \} from '\/@\/hooks\/use-flight-area-compliance'/)
  assert.match(gmapSource, /loadFlightAreas\(\)/)
})
