import test from 'node:test'
import assert from 'node:assert/strict'
import {
  pointInPolygon,
  pointInCircle,
  checkWaylineCompliance,
  circleToRing,
  summarizeViolations,
} from '../../../../components/wayline-planner/flight-area-compliance.mjs'

// 一个 ~22N、114E 附近的小方块禁飞/作业区（边长约 220m）
const SQUARE = [
  [113.999, 21.999],
  [114.001, 21.999],
  [114.001, 22.001],
  [113.999, 22.001],
]

test('pointInPolygon: 闭合与未闭合环结果一致', () => {
  assert.equal(pointInPolygon(114.0, 22.0, SQUARE), true)
  assert.equal(pointInPolygon(114.0, 22.0, [...SQUARE, SQUARE[0]]), true)
  assert.equal(pointInPolygon(114.01, 22.0, SQUARE), false)
})

test('pointInCircle: 半径内外判定（球面距离）', () => {
  assert.equal(pointInCircle(114.0, 22.0, [114.0, 22.0], 100), true)
  // ~1km 外，超出 100m 半径
  assert.equal(pointInCircle(114.01, 22.0, [114.0, 22.0], 100), false)
})

test('航点落入 NFZ 圆 → wp-in-nfz', () => {
  const zones = [{ id: 'z1', name: '禁飞A', type: 'nfz', kind: 'circle', center: [114.0, 22.0], radius: 100 }]
  const points = [{ lng: 114.0, lat: 22.0 }, { lng: 114.01, lat: 22.0 }]
  const r = checkWaylineCompliance(points, zones, false)
  assert.equal(r.hasViolation, true)
  assert.ok(r.violations.some(v => v.kind === 'wp-in-nfz' && v.index === 0))
  assert.deepEqual(r.badWaypoints, [0])
})

test('航段穿越 NFZ 多边形（两端点都在区外）→ seg-cross-nfz', () => {
  const zones = [{ id: 'z2', name: '禁飞B', type: 'nfz', kind: 'polygon', ring: SQUARE }]
  const points = [{ lng: 113.99, lat: 22.0 }, { lng: 114.01, lat: 22.0 }]
  const r = checkWaylineCompliance(points, zones, false)
  assert.equal(r.badWaypoints.length, 0) // 端点都在区外
  assert.ok(r.violations.some(v => v.kind === 'seg-cross-nfz' && v.from === 0 && v.to === 1))
  assert.deepEqual(r.badSegments, [[0, 1]])
})

test('DFENCE 作业区：区内放行、区外报 wp-out-dfence', () => {
  const zones = [{ id: 'd1', name: '作业区', type: 'dfence', kind: 'polygon', ring: SQUARE }]
  assert.equal(checkWaylineCompliance([{ lng: 114.0, lat: 22.0 }], zones, false).hasViolation, false)
  const out = checkWaylineCompliance([{ lng: 114.01, lat: 22.0 }], zones, false)
  assert.ok(out.violations.some(v => v.kind === 'wp-out-dfence' && v.index === 0))
})

test('巡逻闭合：仅闭合段穿越 NFZ 时，closed 才判违规', () => {
  const zones = [{ id: 'z3', name: '禁飞C', type: 'nfz', kind: 'polygon', ring: SQUARE }]
  // 开放段都不穿越；仅 末点→首点 的水平闭合段穿过方块
  const points = [
    { lng: 114.01, lat: 22.0 },
    { lng: 113.99, lat: 22.5 },
    { lng: 113.99, lat: 22.0 },
  ]
  assert.equal(checkWaylineCompliance(points, zones, false).violations.filter(v => v.kind === 'seg-cross-nfz').length, 0)
  const closed = checkWaylineCompliance(points, zones, true)
  assert.equal(closed.violations.filter(v => v.kind === 'seg-cross-nfz').length, 1)
  assert.deepEqual(closed.badSegments, [[2, 0]])
})

test('无飞行区时永不违规', () => {
  const r = checkWaylineCompliance([{ lng: 114.0, lat: 22.0 }], [], false)
  assert.equal(r.hasViolation, false)
})

test('circleToRing: 闭合环且点数为 steps+1', () => {
  const ring = circleToRing([114.0, 22.0], 100, 16)
  assert.equal(ring.length, 17)
  assert.deepEqual(ring[0], ring[ring.length - 1])
})

test('summarizeViolations: 三类违规文案', () => {
  const result = {
    violations: [
      { kind: 'wp-in-nfz' }, { kind: 'wp-in-nfz' },
      { kind: 'seg-cross-nfz' },
      { kind: 'wp-out-dfence' },
    ],
  }
  assert.deepEqual(summarizeViolations(result), ['2 个航点位于禁飞区', '1 段航线穿越禁飞区', '1 个航点超出作业区'])
})
