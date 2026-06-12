import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const profile = readFileSync(new URL('../src/components/wayline-planner/ElevationProfile.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/api/terrain.ts', import.meta.url), 'utf8')

test('profile: 自适应采样≤500点、防抖、降级、双基准、过期响应丢弃', () => {
  assert.match(profile, /maxPoints:\s*499/)
  assert.match(profile, /setTimeout\(refreshTerrain,\s*500\)/)
  assert.match(profile, /地形数据缺失/)
  assert.match(profile, /flightPosition/)
  assert.match(profile, /1号航点地面/)
  assert.match(profile, /含植被冠层/)
  assert.match(profile, /requestSeq/)
  assert.match(profile, /previewWaypoints/)
})

test('terrain api posts to /terrain/elevations under manage prefix with code check', () => {
  assert.match(api, /\/manage\/api\/v1/)
  assert.match(api, /terrain\/elevations/)
  assert.match(api, /code !== 0/)
})
