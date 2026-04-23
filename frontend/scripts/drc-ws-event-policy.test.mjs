import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DRC_LINK_STATE,
  getDrcWsEventDecision,
} from '../src/pages/page-web/projects/drc-ws-event-policy.mjs'

test('joystick invalid only disables joystick and preserves remote session', () => {
  assert.deepEqual(getDrcWsEventDecision({
    bizCode: 'joystick_invalid_notify',
    remoteConnected: true,
    result: 4,
  }), {
    updateDrcLinkState: null,
    updateJoystickAvailable: false,
    destroyRemoteSession: false,
    notice: 'noop',
  })
})

test('drc disconnect updates link state without destroying remote session', () => {
  assert.deepEqual(getDrcWsEventDecision({
    bizCode: 'drc_status_notify',
    remoteConnected: true,
    drcState: DRC_LINK_STATE.DISCONNECT,
    result: 0,
  }), {
    updateDrcLinkState: DRC_LINK_STATE.DISCONNECT,
    updateJoystickAvailable: null,
    destroyRemoteSession: false,
    notice: 'noop',
  })
})

test('official takeoff marks drc disconnect as expected autonomous transition', () => {
  assert.deepEqual(getDrcWsEventDecision({
    bizCode: 'drc_status_notify',
    remoteConnected: true,
    drcState: DRC_LINK_STATE.DISCONNECT,
    officialTakeoffLocked: true,
  }), {
    updateDrcLinkState: DRC_LINK_STATE.DISCONNECT,
    updateJoystickAvailable: null,
    destroyRemoteSession: false,
    notice: 'autonomous_disconnect',
  })
})

test('drc connected restores live flight control availability', () => {
  assert.deepEqual(getDrcWsEventDecision({
    bizCode: 'drc_status_notify',
    remoteConnected: true,
    drcState: DRC_LINK_STATE.CONNECT,
    result: 0,
  }), {
    updateDrcLinkState: DRC_LINK_STATE.CONNECT,
    updateJoystickAvailable: true,
    destroyRemoteSession: false,
    notice: 'noop',
  })
})

test('events are ignored when no remote session exists', () => {
  assert.deepEqual(getDrcWsEventDecision({
    bizCode: 'joystick_invalid_notify',
    remoteConnected: false,
    result: 0,
  }), {
    updateDrcLinkState: null,
    updateJoystickAvailable: null,
    destroyRemoteSession: false,
    notice: 'noop',
  })
})
