import test from 'node:test'
import assert from 'node:assert/strict'

import {
  isSameTrackPoint,
  plannedWaylineTrackState,
  selectActiveTrackRecord,
  shouldAcceptFlightPosition,
} from '../flight-track-policy.mjs'

test('rejects an older position sample from the same aircraft', () => {
  const current = { aircraftSn: 'M300', updatedAt: 2000 }
  assert.equal(shouldAcceptFlightPosition(current, { aircraftSn: 'M300', updatedAt: 1999 }), false)
  assert.equal(shouldAcceptFlightPosition(current, { aircraftSn: 'M300', updatedAt: 2000 }), true)
  assert.equal(shouldAcceptFlightPosition(current, { aircraftSn: 'M300', updatedAt: 2001 }), true)
  assert.equal(shouldAcceptFlightPosition(current, { aircraftSn: 'FC100', updatedAt: 1000 }), true)
})

test('uses a flight id as the track boundary and stops at terminal status', () => {
  assert.deepEqual(plannedWaylineTrackState({ flightId: 'flight-1', taskStatus: 'executing' }), {
    sessionId: 'flight-1', recording: true, terminal: false,
  })
  assert.deepEqual(plannedWaylineTrackState({ flightId: 'flight-1', taskStatus: 'finished' }), {
    sessionId: 'flight-1', recording: false, terminal: true,
  })
  assert.deepEqual(plannedWaylineTrackState({ plannedWaylineId: 'route-1', taskStatus: 'prepared' }), {
    sessionId: 'route-1', recording: false, terminal: false,
  })
  assert.deepEqual(plannedWaylineTrackState({ plannedWaylineId: 'route-1', taskStatus: 'publishing' }), {
    sessionId: 'route-1', recording: false, terminal: false,
  })
})

test('keeps a stale prepared task from stealing an executing mission track', () => {
  const staleThreePointTask = {
    plannedWaylineId: 'old-3-point',
    taskStatus: 'publishing',
    totalWaypoints: 3,
    updateTime: 1000,
  }
  const currentTenPointTask = {
    plannedWaylineId: 'current-10-point',
    taskStatus: 'executing',
    totalWaypoints: 10,
    updateTime: 2000,
  }

  assert.equal(
    selectActiveTrackRecord([staleThreePointTask, currentTenPointTask])?.plannedWaylineId,
    'current-10-point',
  )
  assert.equal(
    selectActiveTrackRecord([staleThreePointTask, currentTenPointTask], 'current-10-point')?.totalWaypoints,
    10,
  )
  assert.equal(selectActiveTrackRecord([staleThreePointTask]), null)
})

test('does not let polling switch away from the route that owns the track', () => {
  const records = [
    { plannedWaylineId: 'newer-other', taskStatus: 'executing', updateTime: 3000 },
    { plannedWaylineId: 'locked', taskStatus: 'paused', updateTime: 2000 },
  ]
  assert.equal(selectActiveTrackRecord(records, 'locked')?.plannedWaylineId, 'locked')
  assert.equal(selectActiveTrackRecord(records, 'missing'), null)
})

test('deduplicates effectively identical map samples', () => {
  assert.equal(isSameTrackPoint([108.1, 34.2], [108.10000001, 34.20000001]), true)
  assert.equal(isSameTrackPoint([108.1, 34.2], [108.10001, 34.2]), false)
})
