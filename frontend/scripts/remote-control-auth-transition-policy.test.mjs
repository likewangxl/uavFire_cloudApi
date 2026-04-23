import test from 'node:test'
import assert from 'node:assert/strict'

import {
  CLOUD_CONTROL_AUTH_RELEASE_GRACE_MS,
  getCloudControlAuthTransitionDecision,
} from '../src/pages/page-web/projects/remote-control-auth-transition-policy.mjs'

test('temporary auth loss during reconnect is deferred instead of released immediately', () => {
  assert.deepEqual(getCloudControlAuthTransitionDecision({
    currentAuthorized: true,
    nextAuthorized: false,
    remoteConnected: true,
    reconnecting: true,
  }), {
    nextAuthorized: true,
    notice: 'defer_release',
  })
})

test('real auth recovery is applied immediately', () => {
  assert.deepEqual(getCloudControlAuthTransitionDecision({
    currentAuthorized: false,
    nextAuthorized: true,
    remoteConnected: true,
    reconnecting: false,
  }), {
    nextAuthorized: true,
    notice: 'restored',
  })
})

test('official takeoff treats auth release as expected autonomous transition', () => {
  assert.deepEqual(getCloudControlAuthTransitionDecision({
    currentAuthorized: true,
    nextAuthorized: false,
    remoteConnected: true,
    reconnecting: false,
    officialTakeoffLocked: true,
  }), {
    nextAuthorized: false,
    notice: 'autonomous_release',
  })
})

test('grace window stays fixed at 10 seconds', () => {
  assert.equal(CLOUD_CONTROL_AUTH_RELEASE_GRACE_MS, 10000)
})
