import assert from 'node:assert/strict'
import test from 'node:test'

import {
  isTrialExpired,
  resolveTrialDecision,
  TRIAL_EXPIRES_AT_DISPLAY,
  TRIAL_EXPIRES_AT_EPOCH_MS,
  TRIAL_EXPIRES_AT_ISO,
} from '../../../../trial/trial-expiration-policy.mjs'

test('trial cutoff is 2026-10-01 00:00 in Asia/Shanghai', () => {
  assert.equal(TRIAL_EXPIRES_AT_ISO, '2026-09-30T16:00:00.000Z')
  assert.equal(TRIAL_EXPIRES_AT_EPOCH_MS, Date.parse('2026-10-01T00:00:00+08:00'))
  assert.match(TRIAL_EXPIRES_AT_DISPLAY, /2026.*10.*1.*00:00/)
})

test('trial remains usable immediately before cutoff', () => {
  assert.equal(isTrialExpired(TRIAL_EXPIRES_AT_EPOCH_MS - 1), false)
})

test('trial is disabled at and after cutoff', () => {
  assert.equal(isTrialExpired(TRIAL_EXPIRES_AT_EPOCH_MS), true)
  assert.equal(isTrialExpired(TRIAL_EXPIRES_AT_EPOCH_MS + 1), true)
})

test('mode off does not expire and until without a date keeps the historical cutoff', () => {
  assert.equal(resolveTrialDecision({ mode: 'off' }, TRIAL_EXPIRES_AT_EPOCH_MS + 1).expired, false)
  assert.equal(resolveTrialDecision({ mode: 'until' }, TRIAL_EXPIRES_AT_EPOCH_MS).expired, true)
})
