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
