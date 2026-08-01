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
  formatFireLevel,
  formatLocationExplanation,
  isPreciseLaserFireLocation,
  isRouteReadyFireEvent,
  reconcileFireEventUpdate,
  mergeFireEventSnapshot,
  captureFireEventSnapshotWatermark,
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
const MISSION_STATES = [
  'CREATED', 'WAITING_REVIEW', 'APPROVED', 'ROUTE_GENERATED', 'ROUTE_EXPORTED',
  'SENT_TO_DELIVERY', 'ACCEPTED_BY_PILOT', 'IN_PROGRESS', 'PAYLOAD_RELEASE_PENDING',
  'PAYLOAD_RELEASED', 'RETURNING', 'REVIEWING', 'COMPLETED', 'REJECTED', 'CANCELLED',
  'FAILED', 'MANUAL_TAKEOVER', 'PAYLOAD_RELEASE_FAILED', 'RETURN_FAILED', 'ARCHIVED'
]
const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

test('every canonical Agent code has an intentional Chinese label', () => {
  for (const code of DETECTION_STATES) assertChinese(formatDetectionStatus(code), code)
  for (const code of LOCATION_STATES) assertChinese(formatLocationStatus(code), code)
  for (const code of FLIGHT_STATES) assertChinese(formatFlightStatus(code), code)
  for (const code of GEO_METHODS) assertChinese(formatGeoMethod(code), code)
  for (const code of DETECTION_KINDS) assertChinese(formatDetectionKind(code), code)
  for (const code of MISSION_STATES) assertChinese(formatMissionStatus(code), code)
  for (const code of ['HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']) assertChinese(formatFireLevel(code), code)
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
    formatGeoQuality,
    formatFireLevel
  ]) {
    for (const value of [null, undefined, '', 'TOTALLY_UNKNOWN_RAW']) {
      assert.equal(formatter(value), '未知状态')
    }
  }
})

test('a delayed lower-sequence HTTP v1 snapshot cannot overwrite websocket v2', async () => {
  let resolveSnapshot
  let resolveWebsocket
  const slowSnapshot = new Promise(resolve => { resolveSnapshot = resolve })
  const fastWebsocket = new Promise(resolve => { resolveWebsocket = resolve })
  let events = [{ eventId: 'event-1', notificationVersion: 1, lastAgentSequence: 1, source: 'AGENT_VISIBLE' }]

  const pendingHttp = slowSnapshot.then(snapshot => {
    events = mergeFireEventSnapshot(events, snapshot)
  })
  const pendingWebsocket = fastWebsocket.then(update => {
    events = reconcileFireEventUpdate(events, update).events
  })
  resolveWebsocket({
    eventId: 'event-1', notificationVersion: 2, agentSequence: 5, detectionKind: 'FIRE', state: 'RESULT_DURABLE',
    locationStatus: 'PRECISE', geoMethod: 'LASER_RANGEFINDER', fireLat: 34.8, fireLng: 109.2
  })
  await pendingWebsocket
  resolveSnapshot([{
    eventId: 'event-1', notificationVersion: 1, lastAgentSequence: 1, detectionKind: 'SMOKE', detectionStatus: 'VISUAL_CONFIRMED',
    locationStatus: 'LASER_LOCATING', source: 'AGENT_VISIBLE', deviceSn: 'drone-1'
  }])
  await pendingHttp

  assert.equal(events[0].notificationVersion, 2)
  assert.equal(events[0].detectionKind, 'FIRE')
  assert.equal(events[0].locationStatus, 'PRECISE')
  assert.equal(events[0].deviceSn, undefined)
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

test('notificationVersion gates toast while agentSequence gates state progression', () => {
  const initial = [{ eventId: 'event-1', notificationVersion: 1, lastAgentSequence: 4, state: 'LASER_MEASURING', confidence: 0.8 }]
  const updated = reconcileFireEventUpdate(initial, {
    eventId: 'event-1', notificationVersion: 2, agentSequence: 5, state: 'RESULT_DURABLE', locationStatus: 'PRECISE'
  })
  assert.equal(updated.accepted, true)
  assert.equal(updated.notificationAdvanced, true)
  assert.equal(updated.events.length, 1)
  assert.equal(updated.events[0].confidence, 0.8)
  assert.equal(updated.events[0].state, 'RESULT_DURABLE')
  assert.equal(fireEventNotificationKey(updated.event), 'fire-event:event-1')

  const sameNotification = reconcileFireEventUpdate(updated.events, {
    eventId: 'event-1', notificationVersion: 2, agentSequence: 6, state: 'RESUME_REQUESTED'
  })
  assert.equal(sameNotification.accepted, true)
  assert.equal(sameNotification.notificationAdvanced, false)
  assert.equal(sameNotification.events[0].state, 'RESUME_REQUESTED')

  const ignored = reconcileFireEventUpdate(sameNotification.events, {
    eventId: 'event-1', notificationVersion: 2, agentSequence: 5, state: 'RESULT_DURABLE'
  })
  assert.equal(ignored.accepted, false)
  assert.deepEqual(ignored.events, sameNotification.events)

  const inserted = reconcileFireEventUpdate(sameNotification.events, {
    eventId: 'event-2', notificationVersion: 1, agentSequence: 1, state: 'VISUAL_CONFIRMED'
  })
  assert.equal(inserted.accepted, true)
  assert.equal(inserted.notificationAdvanced, true)
  assert.equal(inserted.events.length, 2)
})

test('same notification v1 and v2 snapshots advance through the full agent flight loop', () => {
  let events = [{ eventId: 'event-1', notificationVersion: 1, lastAgentSequence: 1, detectionStatus: 'VISUAL_CONFIRMED' }]
  for (const [sequence, detectionStatus, flightStatus] of [
    [2, 'HOLD_REQUESTED', 'HOLD_REQUESTED'],
    [3, 'HOVER_VERIFYING', 'HOVERING'],
    [4, 'TARGET_ALIGNING', 'TARGET_ALIGNING'],
    [5, 'LASER_MEASURING', 'LASER_MEASURING']
  ]) {
    events = mergeFireEventSnapshot(events, [{
      eventId: 'event-1', notificationVersion: 1, lastAgentSequence: sequence, detectionStatus, flightStatus
    }])
  }
  assert.equal(events[0].detectionStatus, 'LASER_MEASURING')
  assert.equal(events[0].lastAgentSequence, 5)

  events = mergeFireEventSnapshot(events, [{
    eventId: 'event-1', notificationVersion: 2, lastAgentSequence: 6,
    detectionStatus: 'RESULT_DURABLE', flightStatus: 'LASER_MEASURING'
  }])
  for (const [sequence, detectionStatus, flightStatus] of [
    [7, 'RESUME_REQUESTED', 'RESUME_REQUESTED'],
    [8, 'MISSION_RESUMED', 'MISSION_RESUMED'],
    [9, 'SCANNING', 'SCANNING']
  ]) {
    events = mergeFireEventSnapshot(events, [{
      eventId: 'event-1', notificationVersion: 2, lastAgentSequence: sequence, detectionStatus, flightStatus
    }])
  }
  assert.equal(events[0].detectionStatus, 'SCANNING')
  assert.equal(events[0].flightStatus, 'SCANNING')
  assert.equal(events[0].notificationVersion, 2)
})

test('same sequence only fills fields and legacy v0 uses updateTime without rollback', () => {
  const sequenced = mergeFireEventSnapshot(
    [{ eventId: 'event-1', notificationVersion: 2, lastAgentSequence: 8, detectionStatus: 'MISSION_RESUMED' }],
    [{ eventId: 'event-1', notificationVersion: 2, lastAgentSequence: 8, detectionStatus: 'RESULT_DURABLE', deviceSn: 'drone-1' }]
  )
  assert.equal(sequenced[0].detectionStatus, 'MISSION_RESUMED')
  assert.equal(sequenced[0].deviceSn, 'drone-1')

  let legacy = [{ eventId: 'legacy-1', notificationVersion: 0, updateTime: 200, status: 'CONFIRMED' }]
  legacy = mergeFireEventSnapshot(legacy, [{ eventId: 'legacy-1', notificationVersion: 0, updateTime: 100, status: 'NEW' }])
  assert.equal(legacy[0].status, 'CONFIRMED')
  legacy = mergeFireEventSnapshot(legacy, [{ eventId: 'legacy-1', notificationVersion: 0, updateTime: 300, status: 'ARCHIVED' }])
  assert.equal(legacy[0].status, 'ARCHIVED')
})

test('authoritative snapshot prunes the event that falls outside the backend limit', () => {
  const current = Array.from({ length: 51 }, (_, index) => ({
    eventId: `event-${51 - index}`,
    notificationVersion: 1,
    lastAgentSequence: 1
  }))
  const merged = mergeFireEventSnapshot(current, current.slice(0, 50))

  assert.equal(merged.length, 50)
  assert.equal(merged.some(event => event.eventId === 'event-1'), false)
})

test('snapshot prunes deleted rows but retains websocket rows that arrived during the request for one cycle', () => {
  let events = [{ eventId: 'deleted-event', notificationVersion: 1, lastAgentSequence: 1 }]
  const requestWatermark = captureFireEventSnapshotWatermark()
  events = reconcileFireEventUpdate(events, {
    eventId: 'inflight-ws', notificationVersion: 1, agentSequence: 1, state: 'VISUAL_CONFIRMED'
  }).events
  events = events.map(event => new Proxy(event, {}))

  events = mergeFireEventSnapshot(events, [], { realtimeWatermark: requestWatermark })
  assert.deepEqual(events.map(event => event.eventId), ['inflight-ws'])

  const nextRequestWatermark = captureFireEventSnapshotWatermark()
  events = mergeFireEventSnapshot(events, [], { realtimeWatermark: nextRequestWatermark })
  assert.deepEqual(events, [])
})

test('fire event pages consume websocket updates with the shared monotonic reconciliation contract', () => {
  const listSource = readFileSync(resolve(frontendRoot, 'src/pages/page-web/projects/fire/FireEventList.vue'), 'utf8')
  const cockpitSource = readFileSync(resolve(frontendRoot, 'src/pages/page-web/projects/leadership-cockpit.vue'), 'utf8')
  const manageSource = readFileSync(resolve(frontendRoot, 'src/api/manage.ts'), 'utf8')

  for (const source of [listSource, cockpitSource]) {
    assert.match(source, /useConnectWebSocket/)
    assert.match(source, /parseFireEventUpdateMessage/)
    assert.match(source, /reconcileFireEventUpdate/)
    assert.match(source, /mergeFireEventSnapshot/)
    assert.match(source, /captureFireEventSnapshotWatermark/)
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
