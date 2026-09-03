import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../use-g-map.ts', import.meta.url), 'utf8')

test('shared TSA and wayline map attempts browser location even when a cached center exists', () => {
  assert.match(source, /const cached = readCachedCenter\(\)/)
  assert.match(source, /const initCenter = cached\?\.center \?\? DEFAULT_CENTER/)
  assert.match(source, /if \(navigator\.geolocation\)/)
  assert.doesNotMatch(source, /if \(!cached && navigator\.geolocation\)/)
  assert.match(source, /timeout:\s*15000/)
  assert.match(source, /maximumAge:\s*300000/)
  assert.match(source, /saveMapCenter\(p, DEFAULT_ZOOM\)/)
})
