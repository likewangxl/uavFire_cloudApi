import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CAMERA_PRESETS,
  getCameraPreset,
  cameraFootprint,
  lineSpacingFromOverlap,
  shotIntervalFromOverlap,
  generateAreaCoverage,
  looksLikeAreaSweep,
  convexHull,
} from '../area-utils.mjs'

// ~200m 见方多边形（西安附近）
const SQUARE = [
  { gcjLng: 109.0000, gcjLat: 34.0000 },
  { gcjLng: 109.0020, gcjLat: 34.0000 },
  { gcjLng: 109.0020, gcjLat: 34.0018 },
  { gcjLng: 109.0000, gcjLat: 34.0018 },
]

test('相机预设：默认回退 + 足迹随高度线性', () => {
  assert.equal(getCameraPreset('不存在').key, CAMERA_PRESETS[0].key)
  const cam = getCameraPreset('M30T')
  const fp30 = cameraFootprint(cam, 30)
  const fp60 = cameraFootprint(cam, 60)
  assert.ok(Math.abs(fp60.widthM - fp30.widthM * 2) < 1e-6)
  assert.ok(fp30.widthM > 0 && fp30.heightM > 0)
})

test('重叠率→间距：重叠越高间距越小，高重叠不为负', () => {
  const cam = getCameraPreset('M30T')
  const s50 = lineSpacingFromOverlap(cam, 60, 50)
  const s80 = lineSpacingFromOverlap(cam, 60, 80)
  assert.ok(s80 < s50)
  assert.ok(lineSpacingFromOverlap(cam, 60, 95) >= 0)
  assert.ok(shotIntervalFromOverlap(cam, 60, 80) > 0)
})

test('弓字形生成：方形测区产出成对端点，航向旋转后点数变化', () => {
  const cam = getCameraPreset('M30T')
  const spacing = lineSpacingFromOverlap(cam, 60, 70)
  const pts = generateAreaCoverage(SQUARE, { lineSpacingM: spacing, headingDeg: 0 })
  assert.ok(pts.length >= 4 && pts.length % 2 === 0, '端点应成对')
  pts.forEach(p => {
    assert.ok(Number.isFinite(p.gcjLng) && Number.isFinite(p.gcjLat))
  })
  const pts90 = generateAreaCoverage(SQUARE, { lineSpacingM: spacing, headingDeg: 90 })
  assert.ok(pts90.length >= 4)
})

test('弓字形生成：非法输入返回空', () => {
  assert.deepEqual(generateAreaCoverage([{ gcjLng: 1, gcjLat: 1 }], { lineSpacingM: 10 }), [])
  assert.deepEqual(generateAreaCoverage(SQUARE, { lineSpacingM: 0 }), [])
  assert.deepEqual(generateAreaCoverage(SQUARE, { lineSpacingM: -5 }), [])
})

test('面状指纹：生成的弓字形被识别为面状扫描', () => {
  const cam = getCameraPreset('M30T')
  const spacing = lineSpacingFromOverlap(cam, 60, 70)
  const pts = generateAreaCoverage(SQUARE, { lineSpacingM: spacing, headingDeg: 0 })
    .map(p => ({ gcjLng: p.gcjLng, gcjLat: p.gcjLat, height: 30 }))
  assert.ok(pts.length >= 12)
  assert.equal(looksLikeAreaSweep(pts), true)
})

test('面状指纹：普通稀疏航点 / 带动作的航线不误判', () => {
  // 稀疏路径（点少、近似直线）
  const sparse = [
    { gcjLng: 109.000, gcjLat: 34.000 },
    { gcjLng: 109.005, gcjLat: 34.001 },
    { gcjLng: 109.010, gcjLat: 34.002 },
  ]
  assert.equal(looksLikeAreaSweep(sparse), false)
  // 稠密但带动作 → 不是自动扫描
  const cam = getCameraPreset('M30T')
  const spacing = lineSpacingFromOverlap(cam, 60, 70)
  const withAction = generateAreaCoverage(SQUARE, { lineSpacingM: spacing, headingDeg: 0 })
    .map((p, i) => ({ gcjLng: p.gcjLng, gcjLat: p.gcjLat, actions: i === 0 ? [{ actuatorFunc: 'takePhoto' }] : undefined }))
  assert.equal(looksLikeAreaSweep(withAction), false)
})

test('凸包：方形测区扫描端点重建出 4 角边界', () => {
  const cam = getCameraPreset('M30T')
  const spacing = lineSpacingFromOverlap(cam, 60, 70)
  const pts = generateAreaCoverage(SQUARE, { lineSpacingM: spacing, headingDeg: 0 })
  const hull = convexHull(pts)
  assert.ok(hull.length >= 3)
  // 凸包应落在测区经纬度范围内（端点在边界上）
  const lngs = SQUARE.map(p => p.gcjLng)
  const lats = SQUARE.map(p => p.gcjLat)
  const eps = 1e-4
  hull.forEach(h => {
    assert.ok(h.gcjLng >= Math.min(...lngs) - eps && h.gcjLng <= Math.max(...lngs) + eps)
    assert.ok(h.gcjLat >= Math.min(...lats) - eps && h.gcjLat <= Math.max(...lats) + eps)
  })
})

test('凸包：少于 3 点原样返回', () => {
  assert.equal(convexHull([{ gcjLng: 1, gcjLat: 1 }]).length, 1)
  assert.equal(convexHull([]).length, 0)
})

test('弓字形生成：相邻行端点顺序反向（蛇形而非来回穿越）', () => {
  const pts = generateAreaCoverage(SQUARE, { lineSpacingM: 40, headingDeg: 0 })
  // 第 1 条线终点 与 第 2 条线起点 应靠近（蛇形折返），而非回到第 1 条线起点
  assert.ok(pts.length >= 4)
  const l1end = pts[1]
  const l2start = pts[2]
  const d = Math.hypot(l1end.gcjLng - l2start.gcjLng, l1end.gcjLat - l2start.gcjLat)
  const l1start = pts[0]
  const dCross = Math.hypot(l1start.gcjLng - l2start.gcjLng, l1start.gcjLat - l2start.gcjLat)
  assert.ok(d < dCross, '蛇形：第2行起点应靠近第1行终点')
})
