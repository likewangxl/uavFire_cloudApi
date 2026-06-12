// 航线规划纯计算工具（.mjs：Vue 组件与 node 测试共用，禁止引浏览器/Vue 依赖）。
// 距离全部用球面 haversine；坐标用传入对象自带的字段（统计用 GCJ02，采样输出 WGS84 供 DEM 查询）。

const EARTH_RADIUS_M = 6378137.0

export function haversineMeters (lng1, lat1, lng2, lat2) {
  const rad = Math.PI / 180
  const dLat = (lat2 - lat1) * rad
  const dLng = (lng2 - lng1) * rad
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
}

function hoverSeconds (wp) {
  if (!Array.isArray(wp.actions)) return 0
  return wp.actions.reduce((sum, a) => {
    if (a && a.actuatorFunc === 'hover') {
      const t = Number(a.params && a.params.hoverTime)
      return sum + (Number.isFinite(t) && t > 0 ? t : 0)
    }
    return sum
  }, 0)
}

/** 段速度约定：取该段终点航点的 speed 覆写，否则全局默认速度。 */
function segmentSpeed (toWp, defaultSpeed) {
  const v = Number(toWp && toWp.speed)
  return Number.isFinite(v) && v > 0 ? v : defaultSpeed
}

export function computeRouteStats (waypoints, defaultSpeed) {
  if (!Array.isArray(waypoints) || waypoints.length === 0) {
    return { distanceM: 0, durationS: 0, count: 0 }
  }
  let distanceM = 0
  let durationS = hoverSeconds(waypoints[0])
  for (let i = 1; i < waypoints.length; i++) {
    const a = waypoints[i - 1]
    const b = waypoints[i]
    const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
    distanceM += d
    durationS += d / segmentSpeed(b, defaultSpeed) + hoverSeconds(b)
  }
  return { distanceM, durationS, count: waypoints.length }
}

/**
 * 沿航线等步长采样（WGS84 输出，供 DEM 批量查询）。
 * 步长 = max(minStepM, 总长/(maxPoints-1))；超预算时稀疏中间点，航点本身必须保留。
 */
export function sampleRoutePoints (waypoints, { maxPoints = 500, minStepM = 30 } = {}) {
  if (!Array.isArray(waypoints) || waypoints.length === 0) return []
  const segs = []
  let total = 0
  for (let i = 1; i < waypoints.length; i++) {
    const a = waypoints[i - 1]
    const b = waypoints[i]
    const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
    segs.push({ a, b, d })
    total += d
  }
  const step = Math.max(minStepM, total / Math.max(1, maxPoints - 1))
  const out = [{ wgsLng: waypoints[0].wgsLng, wgsLat: waypoints[0].wgsLat, distM: 0, waypointIndex: 0 }]
  let walked = 0
  segs.forEach((seg, segIdx) => {
    if (seg.d <= 0) return
    let offset = step - ((walked % step) || step)
    if (offset <= 0) offset = step
    for (let s = offset; s < seg.d; s += step) {
      const t = s / seg.d
      out.push({
        wgsLng: seg.a.wgsLng + (seg.b.wgsLng - seg.a.wgsLng) * t,
        wgsLat: seg.a.wgsLat + (seg.b.wgsLat - seg.a.wgsLat) * t,
        distM: walked + s,
        waypointIndex: -1,
      })
    }
    walked += seg.d
    out.push({ wgsLng: seg.b.wgsLng, wgsLat: seg.b.wgsLat, distM: walked, waypointIndex: segIdx + 1 })
  })
  if (out.length <= maxPoints) return out
  // 超预算时只稀疏中间采样点，航点本身（waypointIndex>=0，含首尾）必须保留
  const anchors = out.filter(p => p.waypointIndex >= 0)
  const interior = out.filter(p => p.waypointIndex < 0)
  const budget = Math.max(0, maxPoints - anchors.length)
  const thinned = []
  if (budget > 0 && interior.length > 0) {
    const stride = interior.length / budget
    for (let i = 0; i < budget; i++) thinned.push(interior[Math.floor(i * stride)])
  }
  return [...anchors, ...thinned].sort((a, b) => a.distM - b.distM)
}

/** 预演时间轴：travel 段（匀速插值）与 hover 段（原地停留）交替。 */
export function buildSimulationTimeline (waypoints, defaultSpeed) {
  const segments = []
  let t = 0
  if (Array.isArray(waypoints) && waypoints.length > 0) {
    const h0 = hoverSeconds(waypoints[0])
    if (h0 > 0) {
      segments.push({ kind: 'hover', from: waypoints[0], to: waypoints[0], startS: t, endS: t + h0 })
      t += h0
    }
    for (let i = 1; i < waypoints.length; i++) {
      const a = waypoints[i - 1]
      const b = waypoints[i]
      const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
      const travelS = d / segmentSpeed(b, defaultSpeed)
      segments.push({ kind: 'travel', from: a, to: b, startS: t, endS: t + travelS })
      t += travelS
      const h = hoverSeconds(b)
      if (h > 0) {
        segments.push({ kind: 'hover', from: b, to: b, startS: t, endS: t + h })
        t += h
      }
    }
  }
  return { segments, totalS: t, waypoints }
}

export function positionAtTime (timeline, timeS) {
  const { segments, waypoints } = timeline
  if (!segments.length) {
    const wp = waypoints && waypoints[0]
    return wp ? { gcjLng: wp.gcjLng, gcjLat: wp.gcjLat, height: wp.height } : null
  }
  if (timeS <= 0) {
    const wp = segments[0].from
    return { gcjLng: wp.gcjLng, gcjLat: wp.gcjLat, height: wp.height }
  }
  for (const seg of segments) {
    if (timeS <= seg.endS) {
      if (seg.kind === 'hover') {
        return { gcjLng: seg.to.gcjLng, gcjLat: seg.to.gcjLat, height: seg.to.height }
      }
      const t = (timeS - seg.startS) / (seg.endS - seg.startS)
      return {
        gcjLng: seg.from.gcjLng + (seg.to.gcjLng - seg.from.gcjLng) * t,
        gcjLat: seg.from.gcjLat + (seg.to.gcjLat - seg.from.gcjLat) * t,
        height: seg.from.height + (seg.to.height - seg.from.height) * t,
      }
    }
  }
  const last = segments[segments.length - 1].to
  return { gcjLng: last.gcjLng, gcjLat: last.gcjLat, height: last.height }
}
