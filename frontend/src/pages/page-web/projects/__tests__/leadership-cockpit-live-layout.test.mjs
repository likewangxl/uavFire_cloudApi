import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  buildLivePaneState,
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
