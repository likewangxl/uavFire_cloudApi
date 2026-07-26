import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  LIVE_RECONNECT_MAX_ATTEMPTS,
  buildLivePaneState,
  buildLivePlaybackKey,
  shouldReconnectLivePlayer,
  swapPrimaryPreference
} from '../leadership-cockpit-live-layout.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('defaults to visible primary and thermal preview when both urls exist', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://visible',
    thermalPlayUrl: 'webrtc://thermal',
    primaryPreference: 'visible'
  })

  assert.equal(state.primary.kind, 'visible')
  assert.equal(state.primary.url, 'webrtc://visible')
  assert.equal(state.preview.kind, 'thermal')
  assert.equal(state.preview.url, 'webrtc://thermal')
  assert.equal(state.preview.clickable, true)
})

test('falls back to thermal placeholder preview when thermal url is absent', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://visible',
    thermalPlayUrl: '',
    primaryPreference: 'visible'
  })

  assert.equal(state.primary.kind, 'visible')
  assert.equal(state.preview.kind, 'thermal-placeholder')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.clickable, true)
  assert.equal(state.preview.focusAction, 'focus-thermal')
})

test('thermal preference reuses the visible stream url while RC Plus switches the liveview source', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://visible',
    thermalPlayUrl: '',
    primaryPreference: 'thermal',
    appliedFocusAction: 'focus-thermal',
    appliedFocusStatus: 'applied',
    allowSharedThermalPreview: true
  })

  assert.equal(state.primary.kind, 'thermal-shared')
  assert.equal(state.primary.url, 'webrtc://visible')
  assert.equal(state.primary.crop, null)
  assert.equal(state.preview.kind, 'visible')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.clickable, true)
  assert.equal(state.preview.focusAction, 'focus-visible')
})

test('does not reuse the visible stream as the thermal preview', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://same-stream',
    thermalPlayUrl: 'webrtc://same-stream',
    primaryPreference: 'visible'
  })

  assert.equal(state.primary.kind, 'visible')
  assert.equal(state.primary.url, 'webrtc://same-stream')
  assert.equal(state.preview.kind, 'thermal-placeholder')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.clickable, true)
  assert.equal(state.preview.focusAction, 'focus-thermal')
})

test('shared single-source stream keeps thermal preview as a switch control without duplicate playback', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://shared-stream',
    thermalPlayUrl: 'webrtc://shared-stream',
    primaryPreference: 'visible',
    allowSharedThermalPreview: true
  })

  assert.equal(state.primary.kind, 'visible')
  assert.equal(state.primary.url, 'webrtc://shared-stream')
  assert.equal(state.primary.crop, null)
  assert.equal(state.preview.kind, 'thermal-placeholder')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.crop, null)
  assert.equal(state.preview.clickable, true)
  assert.equal(state.preview.focusAction, 'focus-thermal')
})

test('shared single-source stream plays thermal as primary after thermal preference', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://shared-stream',
    thermalPlayUrl: 'webrtc://shared-stream',
    primaryPreference: 'thermal',
    allowSharedThermalPreview: true
  })

  assert.equal(state.primary.kind, 'thermal-shared')
  assert.equal(state.primary.url, 'webrtc://shared-stream')
  assert.equal(state.primary.crop, null)
  assert.equal(state.preview.kind, 'visible')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.crop, null)
  assert.equal(state.preview.clickable, true)
  assert.equal(state.preview.focusAction, 'focus-visible')
})

test('swap toggles visible and thermal preferences', () => {
  assert.equal(swapPrimaryPreference('visible'), 'thermal')
  assert.equal(swapPrimaryPreference('thermal'), 'visible')
})

test('playback key ignores command lifecycle churn so polling cannot rebuild players', () => {
  const base = {
    primaryKind: 'thermal-shared',
    primaryUrl: 'webrtc://shared-stream',
    primaryCrop: null,
    previewKind: 'visible',
    previewUrl: '',
    previewCrop: null,
    currentMode: 'thermal'
  }
  const idleKey = buildLivePlaybackKey(base)
  const churnKey = buildLivePlaybackKey({
    ...base,
    lastCommandAction: 'measure-thermal-region',
    lastCommandStatus: 'pending'
  })

  assert.equal(churnKey, idleKey)
})

test('playback key changes when the camera mode actually switches', () => {
  const base = {
    primaryKind: 'thermal-shared',
    primaryUrl: 'webrtc://shared-stream',
    primaryCrop: null,
    previewKind: 'visible',
    previewUrl: '',
    previewCrop: null,
    currentMode: 'thermal'
  }

  assert.notEqual(
    buildLivePlaybackKey({ ...base, currentMode: 'visible' }),
    buildLivePlaybackKey(base)
  )
})

test('cockpit does not feed command state into the playback key', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  const keyBlock = cockpitSource.match(
    /const livePlaybackKey = computed\(\(\) => buildLivePlaybackKey\(\{[\s\S]*?\}\)\)/
  )?.[0] || ''

  assert.ok(keyBlock, 'livePlaybackKey computed should exist')
  assert.doesNotMatch(keyBlock, /lastCommand(Action|Status)/)
})

test('primary player loading and failure share the same right-side status HUD', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /v-else-if="primaryPlayerState\.loading"[\s\S]{0,180}class="dual-stream-status-card loading"/,
    'loading state should use the compact status card'
  )
  assert.match(
    cockpitSource,
    /v-else-if="primaryPlayerState\.error"[\s\S]{0,180}class="dual-stream-status-card error"/,
    'failure state should use the same compact status card'
  )
  assert.doesNotMatch(
    cockpitSource,
    /v-else-if="primaryPlayerState\.(?:loading|error)"[\s\S]{0,120}class="dual-stream-overlay/,
    'loading and failure states should not use the full-stage overlay'
  )
  assert.match(
    cockpitSource,
    /\.dual-stream-status-card\s*\{[\s\S]*?inset:\s*auto 24px 64px auto;[\s\S]*?width:\s*min\(420px,\s*calc\(100% - 48px\)\);/,
    'status card should live at the same right-side position previously used by playback failure'
  )
  assert.doesNotMatch(
    cockpitSource,
    /\.dual-stream-status-card\.error\s*\{[^}]*rgba\(47,\s*10,\s*17/,
    'failure status should not use the old heavy red panel background'
  )
})

test('played session that breaks mid-stream must auto reconnect', () => {
  assert.equal(shouldReconnectLivePlayer({ hasPlayed: true, attempts: 0 }), true)
})

test('failed reconnect attempts keep retrying until the cap', () => {
  assert.equal(shouldReconnectLivePlayer({ hasPlayed: false, attempts: 1 }), true)
  assert.equal(
    shouldReconnectLivePlayer({ hasPlayed: false, attempts: LIVE_RECONNECT_MAX_ATTEMPTS - 1 }),
    true
  )
  assert.equal(
    shouldReconnectLivePlayer({ hasPlayed: true, attempts: LIVE_RECONNECT_MAX_ATTEMPTS }),
    false
  )
})

test('initial connect failure never auto retries (polling owns that recovery)', () => {
  assert.equal(shouldReconnectLivePlayer({ hasPlayed: false, attempts: 0 }), false)
})

test('cockpit no longer swallows post-playback connection breaks', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')
  assert.doesNotMatch(
    cockpitSource,
    /ignore transient zlm connection state after playback started/,
    'broken connections after playback must schedule a reconnect instead of being ignored'
  )
  assert.match(
    cockpitSource,
    /scheduleLiveReconnect\(/,
    'cockpit should wire the reconnect scheduler'
  )
  assert.match(
    cockpitSource,
    /__cockpitDisposed/,
    'intentionally destroyed endpoints must not trigger reconnects'
  )
})
