import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import {
  AXIS_DISTANCE_MIN_METERS,
  AXIS_DISTANCE_MAX_METERS,
  AXIS_VERTICAL_COMMAND_MS_PER_METER,
  getLatitudeOffsetForMeters,
  getLongitudeOffsetForMeters,
  buildAxisFlyToTarget,
  getVerticalCommandDurationMs,
} from '../src/pages/page-web/projects/axis-displacement-policy.mjs'

const tsaVue = readFileSync(new URL('../src/pages/page-web/projects/tsa.vue', import.meta.url), 'utf8')

function extractFunctionBody (source, functionName) {
  const signatures = [`async function ${functionName}`, `function ${functionName}`]
  const start = signatures
    .map((signature) => ({ signature, index: source.indexOf(signature) }))
    .find(({ index }) => index >= 0)
  assert.ok(start, `expected to find function ${functionName}`)

  const braceStart = source.indexOf('{', start.index)
  assert.ok(braceStart >= 0, `expected ${functionName} to have a function body`)

  let depth = 0
  for (let index = braceStart; index < source.length; index += 1) {
    const char = source[index]
    if (char === '{') depth += 1
    if (char === '}') depth -= 1
    if (depth === 0) {
      return source.slice(start.index, index + 1)
    }
  }

  assert.fail(`unterminated function body for ${functionName}`)
}

test('longitude offset is negative when moving west and positive when moving east', () => {
  const west = getLongitudeOffsetForMeters({ latitude: 34.658676, meters: 10, direction: 'west' })
  const east = getLongitudeOffsetForMeters({ latitude: 34.658676, meters: 10, direction: 'east' })
  assert.ok(west < 0, `expected west offset < 0, got ${west}`)
  assert.ok(east > 0, `expected east offset > 0, got ${east}`)
})

test('latitude offset is positive when moving north and negative when moving south', () => {
  const north = getLatitudeOffsetForMeters({ meters: 10, direction: 'north' })
  const south = getLatitudeOffsetForMeters({ meters: 10, direction: 'south' })
  assert.ok(north > 0, `expected north offset > 0, got ${north}`)
  assert.ok(south < 0, `expected south offset < 0, got ${south}`)
})

test('buildAxisFlyToTarget keeps height unchanged while moving east/west/north/south', () => {
  const west = buildAxisFlyToTarget({
    latitude: 34.658676,
    longitude: 109.3405,
    height: 30,
    meters: 12,
    direction: 'west',
  })
  const east = buildAxisFlyToTarget({
    latitude: 34.658676,
    longitude: 109.3405,
    height: 30,
    meters: 12,
    direction: 'east',
  })
  const north = buildAxisFlyToTarget({
    latitude: 34.658676,
    longitude: 109.3405,
    height: 30,
    meters: 12,
    direction: 'north',
  })
  const south = buildAxisFlyToTarget({
    latitude: 34.658676,
    longitude: 109.3405,
    height: 30,
    meters: 12,
    direction: 'south',
  })

  assert.equal(west.latitude, 34.658676)
  assert.equal(west.height, 30)
  assert.ok(west.longitude < 109.3405)

  assert.equal(east.latitude, 34.658676)
  assert.equal(east.height, 30)
  assert.ok(east.longitude > 109.3405)

  assert.ok(north.latitude > 34.658676)
  assert.equal(north.longitude, 109.3405)
  assert.equal(north.height, 30)

  assert.ok(south.latitude < 34.658676)
  assert.equal(south.longitude, 109.3405)
  assert.equal(south.height, 30)
})

test('vertical command duration scales with meters and respects minimum/maximum bounds', () => {
  assert.equal(getVerticalCommandDurationMs(1), AXIS_VERTICAL_COMMAND_MS_PER_METER)
  assert.equal(getVerticalCommandDurationMs(3), AXIS_VERTICAL_COMMAND_MS_PER_METER * 3)
  assert.equal(AXIS_DISTANCE_MIN_METERS, 1)
  assert.equal(AXIS_DISTANCE_MAX_METERS, 200)
})

test('horizontal axis controls use virtual-stick commands instead of fly_to_point', () => {
  const submitAxisDistanceControlBody = extractFunctionBody(tsaVue, 'submitAxisDistanceControl')

  assert.match(
    submitAxisDistanceControlBody,
    /direction === 'north'[\s\S]*KeyCode\.KEY_W/,
    'north movement should map to DRC pitch-forward'
  )
  assert.match(
    submitAxisDistanceControlBody,
    /direction === 'south'[\s\S]*KeyCode\.KEY_S/,
    'south movement should map to DRC pitch-backward'
  )
  assert.match(
    submitAxisDistanceControlBody,
    /direction === 'west'[\s\S]*KeyCode\.KEY_A/,
    'west movement should map to DRC roll-left'
  )
  assert.match(
    submitAxisDistanceControlBody,
    /KeyCode\.KEY_D/,
    'east movement should map to DRC roll-right'
  )
  assert.doesNotMatch(
    submitAxisDistanceControlBody,
    /postFlyToPoint\(/,
    'axis distance controls should not depend on fly_to_point for horizontal movement'
  )
  assert.match(
    tsaVue,
    /actionLoading\[device\.gateway\.sn\] === 'north'[\s\S]*:disabled="!canVirtualStick\(device\)"/,
    'north movement button should be gated on a ready virtual-stick executor'
  )
  assert.match(
    tsaVue,
    /actionLoading\[device\.gateway\.sn\] === 'west'[\s\S]*:disabled="!canVirtualStick\(device\)"/,
    'west movement button should be gated on a ready virtual-stick executor'
  )
  assert.match(
    tsaVue,
    /actionLoading\[device\.gateway\.sn\] === 'south'[\s\S]*:disabled="!canVirtualStick\(device\)"/,
    'south movement button should be gated on a ready virtual-stick executor'
  )
  assert.match(
    tsaVue,
    /actionLoading\[device\.gateway\.sn\] === 'east'[\s\S]*:disabled="!canVirtualStick\(device\)"/,
    'east movement button should be gated on a ready virtual-stick executor'
  )
})

test('axis distance controls announce success immediately when the timed DRC motion starts', () => {
  const holdVerticalControlBody = extractFunctionBody(tsaVue, 'holdVerticalControl')
  const holdDirectionalControlBody = extractFunctionBody(tsaVue, 'holdDirectionalControl')

  assert.match(
    holdVerticalControlBody,
    /successTiming:\s*'immediate'/,
    'vertical motion helper should announce at dispatch time'
  )
  assert.match(
    holdDirectionalControlBody,
    /successTiming:\s*'immediate'/,
    'horizontal motion helper should announce at dispatch time'
  )
})
