import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  formatDetectionStatus,
  formatLocationStatus,
  formatFlightStatus,
  formatGeoMethod,
  formatDetectionKind,
  formatEventStatus,
  formatMissionStatus,
  formatEventSource,
  formatGeoQuality,
  formatLocationExplanation,
  isPreciseLaserFireLocation,
  isRouteReadyFireEvent,
  reconcileFireEventUpdate,
  fireEventNotificationKey
} from '../fire/fire-event-status.mjs'

const DETECTION_STATES = [
  'DISARMED', 'ARMING', 'SCANNING', 'VISUAL_CONFIRMING', 'VISUAL_CONFIRMED',
  'HOLD_REQUESTED', 'HOVER_VERIFYING', 'TARGET_ALIGNING', 'LASER_MEASURING',
  'RESULT_DURABLE', 'RESUME_REQUESTED', 'MISSION_RESUMED', 'MANUAL_HOLD'
]
const LOCATION_STATES = ['LASER_LOCATING', 'PRECISE', 'DEGRADED_OSD']
const FLIGHT_STATES = [
  'HOLD_REQUESTED', 'HOVERING', 'TARGET_ALIGNING', 'LASER_MEASURING',
  'RESUME_REQUESTED', 'MISSION_RESUMED', 'SCANNING', 'MANUAL_HOLD'
]
const GEO_METHODS = ['LASER_RANGEFINDER', 'AIRCRAFT_OBSERVATION']
const DETECTION_KINDS = ['FIRE', 'SMOKE']
const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

test('every canonical Agent code has an intentional Chinese label', () => {
  for (const code of DETECTION_STATES) assertChinese(formatDetectionStatus(code), code)
  for (const code of LOCATION_STATES) assertChinese(formatLocationStatus(code), code)
  for (const code of FLIGHT_STATES) assertChinese(formatFlightStatus(code), code)
  for (const code of GEO_METHODS) assertChinese(formatGeoMethod(code), code)
  for (const code of DETECTION_KINDS) assertChinese(formatDetectionKind(code), code)
})

test('unknown and empty codes never echo raw values', () => {
  for (const formatter of [
    formatDetectionStatus,
    formatLocationStatus,
    formatFlightStatus,
    formatGeoMethod,
    formatDetectionKind,
    formatEventStatus,
    formatMissionStatus,
    formatEventSource,
    formatGeoQuality
  ]) {
    for (const value of [null, undefined, '', 'TOTALLY_UNKNOWN_RAW']) {
      assert.equal(formatter(value), '未知状态')
    }
  }
})

test('degraded and smoke precise locations use explicit safe explanations', () => {
  assert.equal(
    formatLocationExplanation({ detectionKind: 'FIRE', locationStatus: 'DEGRADED_OSD' }),
    '飞机观测位置，非火点精确位置'
  )
  assert.equal(
    formatLocationExplanation({ detectionKind: 'SMOKE', locationStatus: 'PRECISE' }),
    '烟雾观测定位点，可能不是实际起火源'
  )
})

test('only a valid precise laser fire point is marker and route ready', () => {
  const precise = {
    detectionKind: 'FIRE', locationStatus: 'PRECISE', geoQuality: 'PRECISE',
    geoMethod: 'LASER_RANGEFINDER', lat: 34.8, lng: 109.2
  }
  assert.equal(isPreciseLaserFireLocation(precise), true)
  assert.equal(isRouteReadyFireEvent(precise), true)
  assert.equal(isRouteReadyFireEvent({ ...precise, detectionKind: 'SMOKE' }), false)
  assert.equal(isRouteReadyFireEvent({ ...precise, locationStatus: 'DEGRADED_OSD', geoQuality: 'DEGRADED_OSD', geoMethod: 'AIRCRAFT_OBSERVATION' }), false)
  assert.equal(isRouteReadyFireEvent({ ...precise, locationStatus: 'LASER_LOCATING', geoQuality: 'LASER_LOCATING' }), false)
  assert.equal(isRouteReadyFireEvent({ ...precise, geoMethod: 'AIRCRAFT_OBSERVATION' }), false)
  assert.equal(isRouteReadyFireEvent({ ...precise, lat: 0, lng: 0 }), false)
})

test('fire updates reconcile by eventId and strictly increasing notificationVersion', () => {
  const initial = [{ eventId: 'event-1', notificationVersion: 1, state: 'VISUAL_CONFIRMED', confidence: 0.8 }]
  const updated = reconcileFireEventUpdate(initial, {
    eventId: 'event-1', notificationVersion: 2, state: 'RESULT_DURABLE', locationStatus: 'PRECISE'
  })
  assert.equal(updated.accepted, true)
  assert.equal(updated.events.length, 1)
  assert.equal(updated.events[0].confidence, 0.8)
  assert.equal(updated.events[0].state, 'RESULT_DURABLE')
  assert.equal(fireEventNotificationKey(updated.event), 'fire-event:event-1')

  for (const version of [2, 1]) {
    const ignored = reconcileFireEventUpdate(updated.events, {
      eventId: 'event-1', notificationVersion: version, state: 'SCANNING'
    })
    assert.equal(ignored.accepted, false)
    assert.deepEqual(ignored.events, updated.events)
  }

  const inserted = reconcileFireEventUpdate(updated.events, {
    eventId: 'event-2', notificationVersion: 1, state: 'VISUAL_CONFIRMED'
  })
  assert.equal(inserted.accepted, true)
  assert.equal(inserted.events.length, 2)
})

test('fire event pages consume websocket updates with the shared monotonic reconciliation contract', () => {
  const listSource = readFileSync(resolve(frontendRoot, 'src/pages/page-web/projects/fire/FireEventList.vue'), 'utf8')
  const cockpitSource = readFileSync(resolve(frontendRoot, 'src/pages/page-web/projects/leadership-cockpit.vue'), 'utf8')
  const manageSource = readFileSync(resolve(frontendRoot, 'src/api/manage.ts'), 'utf8')

  for (const source of [listSource, cockpitSource]) {
    assert.match(source, /useConnectWebSocket/)
    assert.match(source, /parseFireEventUpdateMessage/)
    assert.match(source, /reconcileFireEventUpdate/)
    assert.match(source, /fireEventNotificationKey/)
  }
  assert.match(manageSource, /FIRE_EVENT_UPDATE_BIZ_CODE\s*=\s*'fire_event_update'/)
  assert.match(manageSource, /notificationVersion/)
})

function assertChinese (label, raw) {
  assert.notEqual(label, raw)
  assert.notEqual(label, '未知状态')
  assert.match(label, /[\u3400-\u9fff]/)
}
