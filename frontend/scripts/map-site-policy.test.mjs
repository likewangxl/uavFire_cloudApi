import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveSiteView, resolveInitialMapView } from '../src/hooks/map-site-policy.mjs'

const site = { longitude: 110, latitude: 35, coordinateSystem: 'WGS84', zoom: 17 }
const fallback = { center: [108, 34], zoom: 5 }
test('server deployment point wins over the previous map view', () => {
  assert.deepEqual(resolveInitialMapView(site, { center: [116, 39], zoom: 12 }, fallback), {
    center: [110, 35], zoom: 17, source: 'site'
  })
})
test('invalid or absent server coordinates do not masquerade as current position', () => {
  for (const invalid of [null, {}, { ...site, longitude: null }, { ...site, longitude: 181 },
    { ...site, latitude: NaN }, { ...site, longitude: 0, latitude: 0 }, { ...site, coordinateSystem: 'BD09' }]) {
    assert.equal(resolveSiteView(invalid), null)
    assert.equal(resolveInitialMapView(invalid, null, fallback).source, 'unconfigured')
  }
})
test('Gaode coordinates are converted to WGS84 while GPS stays unchanged', () => {
  assert.deepEqual(resolveSiteView(site).center, [110, 35])
  const converted = resolveSiteView({ ...site, coordinateSystem: 'GCJ02' }).center
  assert.ok(converted[0] < 110 && converted[0] > 109.98)
  assert.ok(Math.abs(converted[1] - 35) < 0.02)
  assert.notDeepEqual(converted, [110, 35])
})
test('changing the deployment point replaces cache even on the same server IP', () => {
  const updated = { ...site, longitude: 111 }
  assert.equal(resolveInitialMapView(updated, { center: [110, 35], zoom: 18 }, fallback).center[0], 111)
  assert.equal(resolveInitialMapView(null, { center: [110, 35], zoom: 999 }, fallback).zoom, 17)
})
