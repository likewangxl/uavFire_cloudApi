import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildSituationLayers,
  loadTelluxModule
} from '../leadership-cockpit-situation.mjs'

test('builds fire markers from located fire events', () => {
  const layers = buildSituationLayers({
    fireEvents: [
      {
        eventId: 'fire-high-1',
        fireLevel: 'HIGH',
        status: 'NEW',
        lat: 34.66791,
        lng: 109.32667,
        confidence: 0.95,
        detectionKind: 'FIRE',
        locationStatus: 'PRECISE',
        geoQuality: 'PRECISE',
        geoMethod: 'LASER_RANGEFINDER',
        geoErrorRadiusM: 42,
        missionNo: 'MISSION-001',
        lastSeenTime: 1760000000000
      }
    ]
  })

  assert.equal(layers.fireMarkers.length, 1)
  assert.equal(layers.fireMarkers[0].id, 'fire:fire-high-1')
  assert.deepEqual(layers.fireMarkers[0].coordinates, [109.32667, 34.66791])
  assert.equal(layers.fireMarkers[0].tone, 'danger')
  assert.match(layers.fireMarkers[0].popup.detail, /MISSION-001/)
})

test('keeps unlocated or low quality fire events out of map markers', () => {
  const layers = buildSituationLayers({
    fireEvents: [
      { eventId: 'missing-location', fireLevel: 'HIGH', status: 'NEW', lat: null, lng: null },
      { eventId: 'bad-quality', fireLevel: 'MEDIUM', status: 'NEW', lat: 34.1, lng: 109.1, geoQuality: 'DEM_MISSING' }
    ]
  })

  assert.equal(layers.fireMarkers.length, 0)
  assert.equal(layers.unlocatedEvents.length, 2)
  assert.deepEqual(layers.unlocatedEvents.map(item => item.eventId), ['missing-location', 'bad-quality'])
})

test('builds error circles from geo error radius without calling them real fire boundaries', () => {
  const layers = buildSituationLayers({
    fireEvents: [
      {
        eventId: 'fire-high-1',
        fireLevel: 'HIGH',
        status: 'NEW',
        lat: 34.66791,
        lng: 109.32667,
        detectionKind: 'FIRE',
        locationStatus: 'PRECISE',
        geoQuality: 'PRECISE',
        geoMethod: 'LASER_RANGEFINDER',
        geoErrorRadiusM: 80
      }
    ]
  })

  assert.equal(layers.errorCircles.length, 1)
  assert.equal(layers.errorCircles[0].radiusM, 80)
  assert.equal(layers.errorCircles[0].estimated, true)
  assert.equal(layers.errorCircles[0].label, '定位误差圈')
})

test('builds route lines from mission waypoints', () => {
  const layers = buildSituationLayers({
    fireEvents: [
      {
        eventId: 'fire-high-1',
        fireLevel: 'HIGH',
        status: 'MISSION_CREATED',
        lat: 34.66791,
        lng: 109.32667,
        detectionKind: 'FIRE',
        locationStatus: 'PRECISE',
        geoQuality: 'PRECISE',
        geoMethod: 'LASER_RANGEFINDER',
        missionNo: 'MISSION-001'
      }
    ],
    missionWaypoints: {
      'MISSION-001': [
        { waypointIndex: 1, lat: 34.66, lng: 109.32, alt: 120 },
        { waypointIndex: 2, lat: 34.66791, lng: 109.32667, alt: 110 }
      ]
    }
  })

  assert.equal(layers.routeLines.length, 1)
  assert.equal(layers.routeLines[0].missionNo, 'MISSION-001')
  assert.deepEqual(layers.routeLines[0].coordinates, [[109.32, 34.66], [109.32667, 34.66791]])
  assert.match(layers.routeLines[0].popup.detail, /2 个航点/)
})

test('builds aircraft markers from monitor and delivery aircraft coordinates', () => {
  const layers = buildSituationLayers({
    msdkDevices: [
      {
        aircraftSn: 'M4T-001',
        model: 'DJI Matrice 4T',
        online: true,
        latitude: 34.66,
        longitude: 109.32,
        batteryPercent: 62,
        height: 120,
        horizontalSpeed: 8.2
      }
    ],
    deliveryTargets: [
      {
        deviceSn: 'FC100-001',
        callsign: 'DJI Flycart100',
        model: 'DJI Flycart100',
        online: true,
        latitude: 34.68,
        longitude: 109.35,
        batteryPercent: 70,
        streamStatus: 'running'
      }
    ]
  })

  assert.equal(layers.aircraftMarkers.length, 2)
  assert.match(layers.aircraftMarkers[0].popup.title, /DJI Matrice 4T/)
  assert.match(layers.aircraftMarkers[1].popup.title, /DJI Flycart100/)
})

test('computes map bounds from visible situation coordinates', () => {
  const layers = buildSituationLayers({
    fireEvents: [{ eventId: 'fire-1', detectionKind: 'FIRE', locationStatus: 'PRECISE', fireLevel: 'HIGH', lat: 34.66, lng: 109.32, geoQuality: 'PRECISE', geoMethod: 'LASER_RANGEFINDER' }],
    msdkDevices: [{ aircraftSn: 'M4T-001', model: 'DJI Matrice 4T', online: true, latitude: 34.68, longitude: 109.35 }]
  })

  assert.deepEqual(layers.bounds, {
    west: 109.32,
    south: 34.66,
    east: 109.35,
    north: 34.68
  })
})

test('loads Tellux only through an optional dynamic module url', async () => {
  const missing = await loadTelluxModule('')
  assert.equal(missing.status, 'unconfigured')

  const loaded = await loadTelluxModule('data:text/javascript,export default {name:"TelluxMock"}')
  assert.equal(loaded.status, 'loaded')
  assert.equal(loaded.module.default.name, 'TelluxMock')
})
