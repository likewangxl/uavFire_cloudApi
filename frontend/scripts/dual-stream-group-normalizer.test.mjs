import test from 'node:test'
import assert from 'node:assert/strict'

import { normalizeDualStreamGroup } from '../src/api/dual-stream-normalizer.mjs'

test('normalizeDualStreamGroup maps snake_case backend fields to camelCase frontend fields', () => {
  const normalized = normalizeDualStreamGroup({
    drone_sn: 'RC_PLUS_LOCAL',
    session_state: 'RUNNING',
    connection_state: 'CAPABILITY_READY',
    visible_state: 'running',
    thermal_state: 'degraded',
    playback_status: 'visible-live-ready',
    visible_play_url: 'webrtc://192.168.0.30:58925/live/RC_PLUS_LOCAL-0',
    thermal_play_url: null,
    visible_supported: true,
    thermal_supported: true
  })

  assert.equal(normalized.droneSn, 'RC_PLUS_LOCAL')
  assert.equal(normalized.sessionState, 'RUNNING')
  assert.equal(normalized.connectionState, 'CAPABILITY_READY')
  assert.equal(normalized.visibleState, 'running')
  assert.equal(normalized.thermalState, 'degraded')
  assert.equal(normalized.playbackStatus, 'visible-live-ready')
  assert.equal(normalized.visiblePlayUrl, 'webrtc://192.168.0.30:58925/live/RC_PLUS_LOCAL-0')
  assert.equal(normalized.thermalPlayUrl, null)
  assert.equal(normalized.visibleSupported, true)
  assert.equal(normalized.thermalSupported, true)
})
