import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildCockpitSummary,
  sortFireEvents
} from '../leadership-cockpit-summary.mjs'

test('builds cockpit metrics from connected backend data sources', () => {
  const summary = buildCockpitSummary({
    fireEvents: [
      {
        id: 1,
        eventId: 'low-1',
        fireLevel: 'LOW',
        status: 'NEW',
        missionNo: null,
        geoQuality: 'DEM_MISSING',
        lastSeenTime: 1000
      },
      {
        id: 2,
        eventId: 'high-1',
        fireLevel: 'HIGH',
        status: 'MISSION_CREATED',
        missionNo: 'FIRE-002',
        missionStatus: 'CREATED',
        geoQuality: 'AUTO_WAYPOINT_READY',
        lastSeenTime: 900
      }
    ],
    aiEvents: [
      { riskLevel: 'LOW', fusionScore: 0.21 },
      { riskLevel: 'HIGH', fusionScore: 0.91 }
    ],
    msdkDevices: [
      { aircraftSn: 'M30T-001', online: true, batteryPercent: 46, gpsCount: 18, rtkCount: 12, height: 121.2 }
    ],
    deliveryTargets: [
      { deviceSn: 'FC100-001', callsign: 'FC100 投放 0001', online: true, streamStatus: 'running', taskStatus: 'BOUND', batteryPercent: 76 },
      { deviceSn: 'FC100-002', callsign: 'FC100 投放 0002', online: false, streamStatus: 'offline' }
    ],
    deliveryTaskStatuses: [
      { taskId: 'task-1', status: 'RUNNING', phase: 'DELIVERING', progressPercent: 35 }
    ],
    dualStreamGroup: { sessionState: 'RUNNING', visiblePlayUrl: 'webrtc://localhost/live/M30T-001-0' }
  })

  assert.equal(summary.metrics.find(item => item.key === 'activeFireEvents').value, '2')
  assert.equal(summary.metrics.find(item => item.key === 'highestFireLevel').value, 'HIGH')
  assert.equal(summary.metrics.find(item => item.key === 'aiEvents').note, '2 条风险记录')
  assert.equal(summary.metrics.find(item => item.key === 'liveOnline').value, '2')
  assert.equal(summary.metrics.find(item => item.key === 'aircraftOnline').value, '2/3')
  assert.equal(summary.metrics.find(item => item.key === 'deliveryTasks').value, '1')
  assert.equal(summary.metrics.find(item => item.key === 'minBattery').value, '46%')
  assert.equal(summary.metrics.find(item => item.key === 'geoQuality').note, '1/2 可生成航线')
  assert.equal(summary.aircraftRows.length, 3)
  assert.equal(summary.taskRows[0].phase, 'DELIVERING')
})

test('exposes full aircraft model details for summary card hover text', () => {
  const summary = buildCockpitSummary({
    msdkDevices: [
      {
        aircraftSn: 'M4T-001',
        model: 'DJI Matrice 4T',
        online: true,
        mode: 'READY',
        batteryPercent: 62
      }
    ],
    deliveryTargets: [
      {
        deviceSn: 'FC100-001',
        callsign: 'DJI Flycart100',
        model: 'DJI Flycart100',
        online: true,
        streamStatus: 'running',
        taskStatus: 'BOUND',
        batteryPercent: 76
      }
    ],
    dualStreamGroup: {
      droneSn: 'M4T-001',
      sessionState: 'RUNNING',
      visiblePlayUrl: 'webrtc://localhost/live/M4T-001-0'
    }
  })

  assert.match(summary.metrics.find(item => item.key === 'liveOnline').title, /DJI Matrice 4T/)
  assert.match(summary.metrics.find(item => item.key === 'liveOnline').title, /DJI Flycart100/)
  assert.match(summary.metrics.find(item => item.key === 'liveOnline').note, /DJI Matrice 4T/)
  assert.match(summary.metrics.find(item => item.key === 'liveOnline').note, /DJI Flycart100/)
  assert.match(summary.metrics.find(item => item.key === 'aircraftOnline').title, /DJI Matrice 4T/)
  assert.match(summary.metrics.find(item => item.key === 'aircraftOnline').title, /READY/)
  assert.match(summary.metrics.find(item => item.key === 'minBattery').title, /62%/)
  assert.match(summary.metrics.find(item => item.key === 'minBattery').note, /DJI Matrice 4T/)
  assert.match(summary.metrics.find(item => item.key === 'minBattery').note, /DJI Flycart100/)
  assert.equal(summary.aircraftRows.find(item => item.sn === 'FC100-001').name, 'DJI Flycart100')
})

test('normalizes numeric FC100 model keys before showing cockpit cards', () => {
  const summary = buildCockpitSummary({
    deliveryTargets: [
      {
        deviceSn: 'FC100-001',
        callsign: '0-122-0',
        deviceType: '0-122-0',
        deviceModelKey: '0-122-0',
        online: true,
        streamStatus: 'running',
        batteryPercent: 70
      }
    ]
  })

  const liveOnline = summary.metrics.find(item => item.key === 'liveOnline')
  const minBattery = summary.metrics.find(item => item.key === 'minBattery')

  assert.match(liveOnline.note, /DJI Flycart100/)
  assert.doesNotMatch(liveOnline.note, /0-122-0/)
  assert.match(minBattery.note, /DJI Flycart100/)
  assert.doesNotMatch(minBattery.note, /0-122-0/)
  assert.equal(summary.aircraftRows.find(item => item.sn === 'FC100-001').name, 'DJI Flycart100')
})

test('builds rich hover details for highlighted cockpit summary cards', () => {
  const summary = buildCockpitSummary({
    fireEvents: [
      {
        eventId: 'HIGH-FIRE-001',
        fireLevel: 'HIGH',
        status: 'NEW',
        missionNo: '',
        geoQuality: 'AUTO_WAYPOINT_READY',
        confidence: 0.95,
        latitude: 34.66791,
        longitude: 109.32667,
        lastSeenTime: 1760000000000
      },
      {
        eventId: 'MED-FIRE-002',
        fireLevel: 'MEDIUM',
        status: 'MISSION_CREATED',
        missionNo: 'MISSION-002',
        missionStatus: 'CREATED',
        geoQuality: 'DEM_MISSING',
        confidence: 0.72,
        lastSeenTime: 1759999900000
      }
    ],
    aiEvents: [
      {
        eventId: 'AI-001',
        riskLevel: 'HIGH',
        analysisChannel: 'thermal',
        visibleScore: 0.81,
        fusionScore: 0.92,
        reviewStatus: 'PENDING',
        sourceTs: 1760000005000
      }
    ],
    msdkDevices: [
      {
        aircraftSn: 'M4T-001',
        model: 'DJI Matrice 4T',
        online: true,
        mode: 'READY',
        batteryPercent: 62,
        gpsCount: 18,
        rtkCount: 12,
        height: 121.2
      }
    ],
    deliveryTargets: [
      {
        deviceSn: 'FC100-001',
        callsign: '0-122-0',
        deviceType: '0-122-0',
        deviceModelKey: '0-122-0',
        online: false,
        streamStatus: 'offline',
        taskStatus: 'WAITING',
        batteryPercent: 70,
        message: '等待投放平台状态'
      }
    ]
  })

  for (const key of ['activeFireEvents', 'highestFireLevel', 'aiEvents', 'aircraftOnline']) {
    assert.ok(summary.metrics.find(item => item.key === key).detailPopover, `${key} should expose detailPopover`)
  }

  const activeFireDetails = summary.metrics.find(item => item.key === 'activeFireEvents').detailPopover
  assert.equal(activeFireDetails.stats.find(item => item.key === 'total').value, '2')
  assert.equal(activeFireDetails.rows[0].title, 'HIGH · HIGH-FIRE-001')
  assert.match(activeFireDetails.rows[0].detail, /34\.66791, 109\.32667/)

  const highestDetails = summary.metrics.find(item => item.key === 'highestFireLevel').detailPopover
  assert.equal(highestDetails.stats.find(item => item.key === 'highestCount').value, '1')
  assert.equal(highestDetails.rows[0].title, 'HIGH · HIGH-FIRE-001')

  const aiDetails = summary.metrics.find(item => item.key === 'aiEvents').detailPopover
  assert.equal(aiDetails.stats.find(item => item.key === 'total').value, '1')
  assert.equal(aiDetails.rows[0].title, 'AI-001')
  assert.match(aiDetails.rows[0].detail, /thermal/)
  assert.match(aiDetails.rows[0].detail, /融合 92%/)

  const aircraftDetails = summary.metrics.find(item => item.key === 'aircraftOnline').detailPopover
  assert.equal(aircraftDetails.stats.find(item => item.key === 'online').value, '1/2')
  assert.match(aircraftDetails.rows.map(item => item.title).join(' / '), /DJI Matrice 4T/)
  assert.match(aircraftDetails.rows.map(item => item.title).join(' / '), /DJI Flycart100/)
  assert.doesNotMatch(JSON.stringify(aircraftDetails), /0-122-0/)
})

test('keeps backend interface urls out of highlighted summary popovers', () => {
  const summary = buildCockpitSummary({
    fireEvents: [{ eventId: 'fire-1', fireLevel: 'HIGH', status: 'NEW' }],
    aiEvents: [{ eventId: 'ai-1', riskLevel: 'HIGH' }],
    msdkDevices: [{ aircraftSn: 'M4T-001', model: 'DJI Matrice 4T', online: true }]
  })

  const popoverText = ['activeFireEvents', 'highestFireLevel', 'aiEvents', 'aircraftOnline']
    .map(key => summary.metrics.find(item => item.key === key).detailPopover)
    .map(popover => JSON.stringify({
      title: popover.title,
      subtitle: popover.subtitle,
      statusLabel: popover.statusLabel
    }))
    .join('\n')

  assert.doesNotMatch(popoverText, /\/api\//)
  assert.doesNotMatch(popoverText, /\/manage\/api/)
  assert.doesNotMatch(popoverText, /\{taskId\}/)
})

test('sorts fire events by risk level before recency', () => {
  const sorted = sortFireEvents([
    { eventId: 'low-new', fireLevel: 'LOW', lastSeenTime: 3000 },
    { eventId: 'high-old', fireLevel: 'HIGH', lastSeenTime: 1000 },
    { eventId: 'medium', fireLevel: 'MEDIUM', lastSeenTime: 2000 }
  ])

  assert.deepEqual(sorted.map(item => item.eventId), ['high-old', 'medium', 'low-new'])
})

test('uses explicit unavailable states instead of invented system health data', () => {
  const summary = buildCockpitSummary({
    fireEventsError: 'fire-events-unavailable',
    aiEventsError: 'ai-events-unavailable',
    dualStreamError: 'dual-stream-unavailable'
  })

  assert.equal(summary.metrics.find(item => item.key === 'activeFireEvents').note, 'fire-events-unavailable')
  assert.equal(summary.metrics.find(item => item.key === 'geoQuality').value, '--')
  assert.match(summary.dataGaps.find(item => item.key === 'serviceHealth').note, /没有统一 health 汇总接口/)
  assert.match(summary.dataGaps.find(item => item.key === 'zlmHealth').note, /没有专用 ZLM health 接口/)
})
