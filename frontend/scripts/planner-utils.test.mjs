import test from 'node:test'
import assert from 'node:assert/strict'
import {
  haversineMeters,
  computeRouteStats,
  sampleRoutePoints,
  buildSimulationTimeline,
  positionAtTime,
} from '../src/components/wayline-planner/planner-utils.mjs'

// 赤道上经度差 0.001° ≈ 111.32m
const P = (lng, lat, extra = {}) => ({ gcjLng: lng, gcjLat: lat, wgsLng: lng, wgsLat: lat, height: 30, ...extra })

test('haversineMeters: 赤道 0.001° 经度差约 111.3m', () => {
  const d = haversineMeters(0, 0, 0.001, 0)
  assert.ok(Math.abs(d - 111.32) < 0.5, `got ${d}`)
})

test('computeRouteStats: 距离/时长/航点数（速度覆写+悬停）', () => {
  const wps = [
    P(0, 0),
    P(0.001, 0, { speed: 2, actions: [{ actuatorFunc: 'hover', params: { hoverTime: 10 } }] }),
    P(0.002, 0),
  ]
  const s = computeRouteStats(wps, 5)
  assert.equal(s.count, 3)
  assert.ok(Math.abs(s.distanceM - 222.64) < 1, `distance ${s.distanceM}`)
  // 段速度约定: 段速度 = 该段终点航点的 speed 覆写 || 全局默认
  // 段1 终点 wp2 speed=2 → 111.32/2=55.66s；段2 终点 wp3 无覆写 → 111.32/5=22.26s；悬停 10s
  assert.ok(Math.abs(s.durationS - (55.66 + 22.26 + 10)) < 1, `duration ${s.durationS}`)
})

test('computeRouteStats: 空/单航点', () => {
  assert.deepEqual(computeRouteStats([], 5), { distanceM: 0, durationS: 0, count: 0 })
  const s = computeRouteStats([P(0, 0)], 5)
  assert.equal(s.count, 1)
  assert.equal(s.distanceM, 0)
})

test('sampleRoutePoints: 步长自适应保证 ≤maxPoints，含两端航点', () => {
  // 22.26km 直线，minStep 30m → 742 点会超 500 → 自适应步长 ≥ 44.5m
  const wps = [P(0, 0), P(0.2, 0)]
  const samples = sampleRoutePoints(wps, { maxPoints: 500, minStepM: 30 })
  assert.ok(samples.length <= 500, `len ${samples.length}`)
  assert.ok(samples.length > 400)
  assert.equal(samples[0].distM, 0)
  const last = samples[samples.length - 1]
  assert.ok(Math.abs(last.wgsLng - 0.2) < 1e-9)
  // 单调递增里程
  for (let i = 1; i < samples.length; i++) assert.ok(samples[i].distM > samples[i - 1].distM)
})

test('sampleRoutePoints: 短航线用 minStep', () => {
  const wps = [P(0, 0), P(0.001, 0)] // 111m → 30m 步长 ≈ 5 点
  const samples = sampleRoutePoints(wps, { maxPoints: 500, minStepM: 30 })
  assert.ok(samples.length >= 4 && samples.length <= 6, `len ${samples.length}`)
})

test('buildSimulationTimeline + positionAtTime: 匀速插值与悬停停留', () => {
  const wps = [
    P(0, 0),
    P(0.001, 0, { speed: 2, actions: [{ actuatorFunc: 'hover', params: { hoverTime: 10 } }] }),
    P(0.002, 0),
  ]
  const tl = buildSimulationTimeline(wps, 5)
  // 总时长 = 55.66 + 10 + 22.26
  assert.ok(Math.abs(tl.totalS - 87.93) < 1, `total ${tl.totalS}`)
  // t=0 在起点
  let pos = positionAtTime(tl, 0)
  assert.ok(Math.abs(pos.gcjLng - 0) < 1e-9)
  // 段1 中点时刻 ≈ 27.83s → 应在 lng≈0.0005
  pos = positionAtTime(tl, 27.83)
  assert.ok(Math.abs(pos.gcjLng - 0.0005) < 1e-4, `lng ${pos.gcjLng}`)
  // 悬停期间停在 wp2
  pos = positionAtTime(tl, 60)
  assert.ok(Math.abs(pos.gcjLng - 0.001) < 1e-9)
  // 超出总时长停在终点
  pos = positionAtTime(tl, 9999)
  assert.ok(Math.abs(pos.gcjLng - 0.002) < 1e-9)
})
