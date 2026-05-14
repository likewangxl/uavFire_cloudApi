import test from 'node:test'
import assert from 'node:assert/strict'

import { buildRemoteSessionDisconnectAudit } from '../src/pages/page-web/projects/remote-session-debug.mjs'

test('builds disconnect audit payload with explicit source and session fields', () => {
  assert.deepEqual(buildRemoteSessionDisconnectAudit({
    source: 'user_click_exit',
    gatewaySn: 'gw-1',
    aircraftSn: 'ac-1',
    clientId: 'client-1',
    officialTakeoffPhase: 'idle',
    cloudControlAuthorized: true,
    drcLinkState: 2,
    joystickAvailable: true,
  }), {
    source: 'user_click_exit',
    gatewaySn: 'gw-1',
    aircraftSn: 'ac-1',
    clientId: 'client-1',
    officialTakeoffPhase: 'idle',
    cloudControlAuthorized: true,
    drcLinkState: 2,
    joystickAvailable: true,
  })
})

test('defaults missing source to unknown', () => {
  assert.equal(buildRemoteSessionDisconnectAudit({
    gatewaySn: 'gw-1',
    clientId: 'client-1',
  }).source, 'unknown')
})
