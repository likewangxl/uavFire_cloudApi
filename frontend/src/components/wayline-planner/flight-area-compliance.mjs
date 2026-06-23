// 飞行区合规判定纯计算工具（.mjs：Vue 组件与 node 测试共用，禁止引浏览器/Vue 依赖）。
// 坐标统一 WGS84 lng/lat（飞行区与航点渲染同坐标系，判定免坐标转换）。
// 判定规则（与 DJI 机端约束一致）：
//   - NFZ（禁飞区）：航点落入 / 航段穿越 = 违规；
//   - DFENCE（作业区）：若存在启用的作业区，所有航点必须落在某个作业区内，否则违规。
import { haversineMeters } from './planner-utils.mjs'

const DEG = Math.PI / 180
const METERS_PER_DEG_LAT = 111320

// 点在多边形内（射线法）。ring=[[lng,lat],...]，自动兼容首尾是否闭合。
export function pointInPolygon (lng, lat, ring) {
  let inside = false
  const n = ring.length
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = ring[i][0]; const yi = ring[i][1]
    const xj = ring[j][0]; const yj = ring[j][1]
    const intersect = ((yi > lat) !== (yj > lat)) &&
      (lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi)
    if (intersect) inside = !inside
  }
  return inside
}

// 点在圆内：球面距离 ≤ 半径（米）。center=[lng,lat]
export function pointInCircle (lng, lat, center, radiusM) {
  return haversineMeters(lng, lat, center[0], center[1]) <= radiusM
}

export function pointInZone (lng, lat, zone) {
  return zone.kind === 'circle'
    ? pointInCircle(lng, lat, zone.center, zone.radius)
    : pointInPolygon(lng, lat, zone.ring)
}

// 两线段是否相交（方向叉积法，端点接触不计）。
function segIntersect (p1, p2, p3, p4) {
  const cross = (a, b, c) => (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
  const d1 = cross(p3, p4, p1)
  const d2 = cross(p3, p4, p2)
  const d3 = cross(p1, p2, p3)
  const d4 = cross(p1, p2, p4)
  return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
    ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
}

// 航段是否穿越多边形：任一多边形边与航段相交。端点落入由航点判定覆盖。
function segCrossesPolygon (a, b, ring) {
  const n = ring.length
  for (let i = 0, j = n - 1; i < n; j = i++) {
    if (segIntersect(a, b, ring[j], ring[i])) return true
  }
  return false
}

// 航段是否穿越圆：圆心到航段最短距离 ≤ 半径。以圆心为原点投到局部米制平面近似。
function segCrossesCircle (a, b, center, radiusM) {
  if (pointInCircle(a[0], a[1], center, radiusM) || pointInCircle(b[0], b[1], center, radiusM)) return true
  const mPerLng = METERS_PER_DEG_LAT * Math.cos(center[1] * DEG)
  const toXY = (p) => [(p[0] - center[0]) * mPerLng, (p[1] - center[1]) * METERS_PER_DEG_LAT]
  const A = toXY(a); const B = toXY(b)
  const dx = B[0] - A[0]; const dy = B[1] - A[1]
  const len2 = dx * dx + dy * dy
  let t = len2 > 0 ? -(A[0] * dx + A[1] * dy) / len2 : 0
  t = Math.max(0, Math.min(1, t))
  const cx = A[0] + t * dx; const cy = A[1] + t * dy
  return Math.sqrt(cx * cx + cy * cy) <= radiusM
}

function segCrossesZone (a, b, zone) {
  return zone.kind === 'circle'
    ? segCrossesCircle(a, b, zone.center, zone.radius)
    : segCrossesPolygon(a, b, zone.ring)
}

// 主判定。points=[{lng,lat}]，zones=归一化飞行区，closed=航线是否闭合（巡逻末点接首点）。
export function checkWaylineCompliance (points, zones, closed = false) {
  const nfz = zones.filter(z => z.type === 'nfz')
  const dfence = zones.filter(z => z.type === 'dfence')
  const violations = []
  const badWaypoints = new Set()
  const badSegments = []

  points.forEach((p, i) => {
    for (const z of nfz) {
      if (pointInZone(p.lng, p.lat, z)) {
        violations.push({ kind: 'wp-in-nfz', index: i, zoneId: z.id, zoneName: z.name })
        badWaypoints.add(i)
      }
    }
    if (dfence.length && !dfence.some(z => pointInZone(p.lng, p.lat, z))) {
      violations.push({ kind: 'wp-out-dfence', index: i })
      badWaypoints.add(i)
    }
  })

  const segCount = closed ? points.length : Math.max(0, points.length - 1)
  for (let i = 0; i < segCount; i++) {
    const j = (i + 1) % points.length
    const A = [points[i].lng, points[i].lat]
    const B = [points[j].lng, points[j].lat]
    for (const z of nfz) {
      if (segCrossesZone(A, B, z)) {
        violations.push({ kind: 'seg-cross-nfz', from: i, to: j, zoneId: z.id, zoneName: z.name })
        badSegments.push([i, j])
        break
      }
    }
  }

  return {
    violations,
    hasViolation: violations.length > 0,
    badWaypoints: [...badWaypoints],
    badSegments,
  }
}

// 圆 → 近似多边形环（渲染用）。返回闭合的 [[lng,lat],...]。
export function circleToRing (center, radiusM, steps = 64) {
  const mPerLng = METERS_PER_DEG_LAT * Math.cos(center[1] * DEG)
  const ring = []
  for (let i = 0; i <= steps; i++) {
    const ang = (i / steps) * 2 * Math.PI
    ring.push([
      center[0] + (Math.cos(ang) * radiusM) / mPerLng,
      center[1] + (Math.sin(ang) * radiusM) / METERS_PER_DEG_LAT,
    ])
  }
  return ring
}

// 违规摘要文字（给告警条/确认弹窗）。
export function summarizeViolations (result) {
  const msgs = []
  const inNfz = result.violations.filter(v => v.kind === 'wp-in-nfz').length
  const crossNfz = result.violations.filter(v => v.kind === 'seg-cross-nfz').length
  const outFence = result.violations.filter(v => v.kind === 'wp-out-dfence').length
  if (inNfz) msgs.push(`${inNfz} 个航点位于禁飞区`)
  if (crossNfz) msgs.push(`${crossNfz} 段航线穿越禁飞区`)
  if (outFence) msgs.push(`${outFence} 个航点超出作业区`)
  return msgs
}
