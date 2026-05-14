import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildLivePaneState,
  swapPrimaryPreference
} from '../leadership-cockpit-live-layout.mjs'

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
