import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DRC_DISCONNECT_GRACE_MS,
  getDrcMqttDisconnectDecision,
} from '../src/pages/page-web/projects/drc-connection-policy.mjs'

test('single mqtt close while remote is connected defers disconnect', () => {
  assert.equal(getDrcMqttDisconnectDecision({
    status: 'close',
    remoteConnected: true,
    sameClient: true,
  }), 'defer_disconnect')
})

test('mqtt close preserves session while official takeoff is active', () => {
  assert.equal(getDrcMqttDisconnectDecision({
    status: 'close',
    remoteConnected: true,
    sameClient: true,
    officialTakeoffLocked: true,
  }), 'preserve_session')
})

test('mqtt open clears pending disconnect', () => {
  assert.equal(getDrcMqttDisconnectDecision({
    status: 'open',
    remoteConnected: true,
    sameClient: true,
  }), 'clear_pending_disconnect')
})

test('events from stale clients are ignored', () => {
  assert.equal(getDrcMqttDisconnectDecision({
    status: 'error',
    remoteConnected: true,
    sameClient: false,
  }), 'ignore')
})

test('grace period is fixed at 10 seconds', () => {
  assert.equal(DRC_DISCONNECT_GRACE_MS, 10000)
})
