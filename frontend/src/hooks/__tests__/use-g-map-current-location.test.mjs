import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../use-g-map.ts', import.meta.url), 'utf8')

test('shared TSA and wayline map uses server deployment policy without automatic browser relocation', () => {
  assert.match(source, /const cached = readCachedCenter\(\)/)
  assert.match(source, /const site = window\.__UAVFIRE_SITE_LOCATION__/)
  assert.match(source, /resolveInitialMapView\(site, cached,/)
  assert.match(source, /center: initial\.center/)
  assert.match(source, /zoom: initial\.zoom/)
  assert.doesNotMatch(source, /navigator\.geolocation/)
  assert.match(source, /saveMapCenter\(\[c\.lng, c\.lat\], map\.getZoom\(\)\)/)
})
