import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { createBrowserLocationControl, browserMapInitialView } from '../browser-map-location.mjs'

function fixture (secure = true) {
  let success, failure, options
  const listeners = new Map(), moves = []
  const element = () => ({ children: [], style: {}, appendChild (e) { this.children.push(e) }, setAttribute () {}, remove () {} })
  const env = { isSecureContext: secure, document: { createElement: element }, localStorage: { getItem: () => null, setItem () {} }, navigator: { geolocation: { getCurrentPosition (s, f, o) { success = s; failure = f; options = o } } } }
  const map = { on: (k, f) => listeners.set(k, f), off: k => listeners.delete(k), jumpTo: v => moves.push(v), getCenter: () => ({ lng: 120, lat: 30 }), getZoom: () => 16 }
  const control = createBrowserLocationControl(env), el = control.onAdd(map)
  return { control, el, moves, listeners, options, success: () => success({ coords: { longitude: 120, latitude: 30, accuracy: 25 } }), failure: code => failure({ code }) }
}

test('both requested pages opt into current browser location', () => {
  const shared = readFileSync(new URL('../use-g-map.ts', import.meta.url), 'utf8')
  const overview = readFileSync(new URL('../../pages/page-web/command-center/Overview.vue', import.meta.url), 'utf8')
  assert.match(shared, /const locationControl = createBrowserLocationControl\(\)/)
  assert.match(overview, /CockpitSituationMap browser-location/)
})
test('fresh WGS84 browser coordinates center map and display accuracy', () => {
  const f = fixture(); f.success()
  assert.deepEqual(f.moves, [{ center: [120, 30], zoom: 16 }])
  assert.match(f.el.children[1].textContent, /25/)
  assert.equal(f.options.maximumAge, 0)
})
test('interaction or route focus cancels late automatic relocation; button retries', () => {
  const f = fixture(); f.listeners.get('movestart')(); f.success()
  assert.equal(f.moves.length, 0)
  f.el.children[0].onclick(); f.success(); assert.equal(f.moves.length, 1)
})
test('unmounted map ignores pending result and removes listeners', () => {
  const f = fixture(); f.control.onRemove(); f.success()
  assert.equal(f.moves.length, 0); assert.equal(f.listeners.size, 0)
})
test('denial and timeout preserve viewport with retry guidance', () => {
  for (const code of [1, 2, 3]) {
    const f = fixture(); f.failure(code)
    assert.equal(f.moves.length, 0); assert.equal(f.el.children[0].disabled, false)
    assert.match(f.el.children[1].textContent, /重试/)
  }
})
test('insecure origin explains HTTPS requirement without requesting location', () => {
  const f = fixture(false)
  assert.equal(f.options, undefined); assert.match(f.el.children[1].textContent, /HTTPS/)
})
test('cached viewport precedes site fallback while fresh location is pending', () => {
  const site = { longitude: 110, latitude: 35, coordinateSystem: 'WGS84' }
  assert.deepEqual(browserMapInitialView(site, { getItem: () => '{"center":[120,30],"zoom":15}' }).center, [120, 30])
  assert.deepEqual(browserMapInitialView(site, { getItem: () => 'broken' }).center, [110, 35])
})
