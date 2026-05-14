import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const tsaVue = readFileSync(new URL('../src/pages/page-web/projects/tsa.vue', import.meta.url), 'utf8')

test('horizontal displacement gate matches the low-altitude takeoff completion floor', () => {
  assert.match(
    tsaVue,
    /const MIN_AIRBORNE_HEIGHT_M = 3\b/,
    'fly_to_point controls should unlock once the aircraft reaches the current low-altitude takeoff completion floor'
  )
  assert.doesNotMatch(
    tsaVue,
    /高度不低于 15 米/,
    'user-facing fly_to_point gating copy should not claim a 15m minimum anymore'
  )
})
