import test from 'node:test'
import assert from 'node:assert/strict'

import {
  getRemoteReconnectDecision,
} from '../src/pages/page-web/projects/remote-control-reconnect-policy.mjs'

test('same gateway reconnect reuses existing session instead of forcing exit first', () => {
  assert.equal(getRemoteReconnectDecision({
    remoteConnected: true,
    currentGatewaySn: 'GW-1',
    targetGatewaySn: 'GW-1',
    cloudControlAuthorized: false,
  }), 'reuse_existing_session')
})

test('switching gateway still requires disconnect', () => {
  assert.equal(getRemoteReconnectDecision({
    remoteConnected: true,
    currentGatewaySn: 'GW-1',
    targetGatewaySn: 'GW-2',
    cloudControlAuthorized: true,
  }), 'disconnect_before_connect')
})
