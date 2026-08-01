import test from 'node:test'
import assert from 'node:assert/strict'

import {
  formatFireLocation,
  isUsableFireLocation
} from '../fire/fire-event-location.mjs'
import { buildSituationLayers } from '../leadership-cockpit-situation.mjs'
import { buildCockpitSummary } from '../leadership-cockpit-summary.mjs'

test('pending and failed laser events never expose provisional aircraft coordinates', () => {
  assert.equal(
    formatFireLocation({ lat: 34.9, lng: 109.3, geoQuality: 'LASER_LOCATING' }),
    '正在精确定位'
  )
  assert.equal(
    formatFireLocation({ lat: 34.9, lng: 109.3, geoQuality: 'LASER_FAILED' }),
    '激光定位失败'
  )
  assert.equal(isUsableFireLocation({ lat: 34.9, lng: 109.3, geoQuality: 'LASER_LOCATING' }), false)
})

test('pending laser events are excluded while precise laser events are placed', () => {
  const layers = buildSituationLayers({
    fireEvents: [
      { eventId: 'pending', lat: 34.9, lng: 109.3, geoQuality: 'LASER_LOCATING' },
      { eventId: 'precise', detectionKind: 'FIRE', locationStatus: 'PRECISE', lat: 34.8, lng: 109.2, geoQuality: 'PRECISE', geoMethod: 'LASER_RANGEFINDER' }
    ]
  })

  assert.equal(layers.fireMarkers.length, 1)
  assert.equal(layers.fireMarkers[0].eventId, 'precise')
  assert.equal(layers.unlocatedEvents[0].eventId, 'pending')
})

test('route-ready metrics exclude pending laser coordinates and include precise fixes', () => {
  const summary = buildCockpitSummary({
    fireEvents: [
      { eventId: 'pending', lat: 34.9, lng: 109.3, geoQuality: 'LASER_LOCATING' },
      { eventId: 'precise', detectionKind: 'FIRE', locationStatus: 'PRECISE', lat: 34.8, lng: 109.2, geoQuality: 'PRECISE', geoMethod: 'LASER_RANGEFINDER' }
    ]
  })

  assert.equal(summary.metrics.find(item => item.key === 'geoQuality').note, '1/2 可生成航线')
})

test('degraded aircraft observations and precise smoke observations never become fire routes', () => {
  const layers = buildSituationLayers({
    fireEvents: [
      {
        eventId: 'degraded-fire',
        detectionKind: 'FIRE',
        locationStatus: 'DEGRADED_OSD',
        geoMethod: 'AIRCRAFT_OBSERVATION',
        aircraftLat: 34.9,
        aircraftLng: 109.3
      },
      {
        eventId: 'precise-smoke',
        detectionKind: 'SMOKE',
        locationStatus: 'PRECISE',
        geoMethod: 'LASER_RANGEFINDER',
        lat: 34.8,
        lng: 109.2
      }
    ]
  })

  assert.equal(layers.fireMarkers.length, 0)
  assert.equal(layers.routeLines.length, 0)
  assert.equal(layers.aircraftMarkers.length, 1)
  assert.equal(layers.unlocatedEvents.find(item => item.eventId === 'degraded-fire').reason, '飞机观测位置，非火点精确位置')
  assert.equal(layers.unlocatedEvents.find(item => item.eventId === 'precise-smoke').reason, '烟雾观测定位点，可能不是实际起火源')
})
