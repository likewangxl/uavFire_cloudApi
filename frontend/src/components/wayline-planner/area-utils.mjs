// 面状航线（弓字形/牛耕式覆盖）纯计算工具。
// .mjs：Vue 组件与 node 测试共用，禁止引浏览器/Vue 依赖。
// 思路：把多边形投到以质心为原点的局部米制平面 → 按航向角把扫描线转成水平 →
//       逐条水平线与多边形求交得到内部线段 → 蛇形串联端点 → 转回经纬度。
// 坐标统一用 GCJ02 lng/lat（与航点一致）；高程/速度由调用方补。

const METERS_PER_DEG_LAT = 111320

// 机载相机预设（广角，用于地面足迹反算）。传感器尺寸/焦距为近似官方值，仅用于
// 旁向行间距与拍照间隔估算，非测绘级精度。默认 M30T 广角。
export const CAMERA_PRESETS = [
  { key: 'M30T', label: 'M30T 广角', sensorWidthMm: 6.4, sensorHeightMm: 4.8, focalMm: 4.5, imageWidthPx: 4000, imageHeightPx: 3000 },
  { key: 'M3T', label: 'M3T 广角', sensorWidthMm: 6.4, sensorHeightMm: 4.8, focalMm: 4.4, imageWidthPx: 4000, imageHeightPx: 3000 },
  { key: 'H20T', label: 'H20T 广角', sensorWidthMm: 6.17, sensorHeightMm: 4.55, focalMm: 4.5, imageWidthPx: 4056, imageHeightPx: 3040 },
]

export function getCameraPreset (key) {
  return CAMERA_PRESETS.find(c => c.key === key) || CAMERA_PRESETS[0]
}

/** 给定相机与飞行高度，地面足迹（米）。width=横向（垂直航线），height=航向。 */
export function cameraFootprint (camera, heightM) {
  const cam = camera || CAMERA_PRESETS[0]
  const h = Number(heightM) > 0 ? Number(heightM) : 0
  return {
    widthM: (cam.sensorWidthMm / cam.focalMm) * h,
    heightM: (cam.sensorHeightMm / cam.focalMm) * h,
  }
}

/** 旁向重叠率(%) → 相邻航线行间距（米）。 */
export function lineSpacingFromOverlap (camera, heightM, sideOverlapPct) {
  const fp = cameraFootprint(camera, heightM)
  const keep = 1 - clampPct(sideOverlapPct) / 100
  return Math.max(0, fp.widthM * keep)
}

/** 航向重叠率(%) → 沿航线拍照间隔（米）。 */
export function shotIntervalFromOverlap (camera, heightM, frontOverlapPct) {
  const fp = cameraFootprint(camera, heightM)
  const keep = 1 - clampPct(frontOverlapPct) / 100
  return Math.max(0, fp.heightM * keep)
}

function clampPct (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.min(95, Math.max(0, n))
}

function haversineM (lng1, lat1, lng2, lat2) {
  const R = 6378137.0
  const rad = Math.PI / 180
  const dLat = (lat2 - lat1) * rad
  const dLng = (lng2 - lng1) * rad
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

/**
 * 几何指纹判断一条航线是否为自动生成的面状扫描航线（后端不存航线类型，预览时据此识别）。
 * 特征：航点稠密(>=minCount) + 无动作/无速度覆写(纯扫描) + 弓字形往返
 *      （路径总长 ÷ 包围盒对角线 >= minFillRatio，普通路径该比值接近 1）。
 */
export function looksLikeAreaSweep (waypoints, { minCount = 12, minFillRatio = 3 } = {}) {
  if (!Array.isArray(waypoints) || waypoints.length < minCount) return false
  for (const wp of waypoints) {
    if ((wp.actions && wp.actions.length > 0) || (wp.speed != null && wp.speed !== '')) return false
  }
  let minLng = Infinity; let maxLng = -Infinity; let minLat = Infinity; let maxLat = -Infinity
  let pathLen = 0
  for (let i = 0; i < waypoints.length; i++) {
    const w = waypoints[i]
    if (w.gcjLng < minLng) minLng = w.gcjLng
    if (w.gcjLng > maxLng) maxLng = w.gcjLng
    if (w.gcjLat < minLat) minLat = w.gcjLat
    if (w.gcjLat > maxLat) maxLat = w.gcjLat
    if (i > 0) pathLen += haversineM(waypoints[i - 1].gcjLng, waypoints[i - 1].gcjLat, w.gcjLng, w.gcjLat)
  }
  const diag = haversineM(minLng, minLat, maxLng, maxLat)
  if (!(diag > 0)) return false
  return pathLen / diag >= minFillRatio
}

/**
 * 凸包（Andrew monotone chain）。面状航线的扫描端点落在测区边界上，预览未保存多边形时
 * 用航点凸包近似重建测区边界（凸测区精确，凹测区为外接近似）。返回 {gcjLng,gcjLat}[]。
 */
export function convexHull (points) {
  const pts = (points || [])
    .map(p => [p.gcjLng, p.gcjLat])
    .filter(p => Number.isFinite(p[0]) && Number.isFinite(p[1]))
  if (pts.length < 3) return pts.map(p => ({ gcjLng: p[0], gcjLat: p[1] }))
  pts.sort((a, b) => a[0] - b[0] || a[1] - b[1])
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
  const lower = []
  for (const p of pts) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop()
    lower.push(p)
  }
  const upper = []
  for (let i = pts.length - 1; i >= 0; i--) {
    const p = pts[i]
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop()
    upper.push(p)
  }
  lower.pop()
  upper.pop()
  return lower.concat(upper).map(p => ({ gcjLng: p[0], gcjLat: p[1] }))
}

function toRad (deg) { return (deg * Math.PI) / 180 }

function rotate (x, y, rad) {
  const c = Math.cos(rad)
  const s = Math.sin(rad)
  return { x: x * c - y * s, y: x * s + y * c }
}

/**
 * 生成弓字形覆盖航点。
 * @param {{gcjLng:number,gcjLat:number}[]} polygon 多边形顶点（>=3）
 * @param {{lineSpacingM:number, headingDeg:number}} opts 行间距(米)、航向角(度，正北顺时针)
 * @returns {{gcjLng:number,gcjLat:number}[]} 蛇形串联的航线端点（GCJ02），不足条件返回 []
 */
export function generateAreaCoverage (polygon, opts = {}) {
  if (!Array.isArray(polygon) || polygon.length < 3) return []
  const spacing = Number(opts.lineSpacingM)
  if (!Number.isFinite(spacing) || spacing <= 0) return []
  const headingDeg = Number.isFinite(Number(opts.headingDeg)) ? Number(opts.headingDeg) : 0

  // 局部米制平面原点取质心
  let lng0 = 0; let lat0 = 0
  polygon.forEach(p => { lng0 += p.gcjLng; lat0 += p.gcjLat })
  lng0 /= polygon.length; lat0 /= polygon.length
  const mPerDegLng = METERS_PER_DEG_LAT * Math.cos(toRad(lat0))

  const toLocal = (p) => ({ x: (p.gcjLng - lng0) * mPerDegLng, y: (p.gcjLat - lat0) * METERS_PER_DEG_LAT })
  const toLngLat = (x, y) => ({ gcjLng: lng0 + x / mPerDegLng, gcjLat: lat0 + y / METERS_PER_DEG_LAT })

  // 航向向量（正北顺时针 headingDeg）与 +x(东) 轴的 CCW 夹角 = 90° - heading。
  // 把多边形旋转 -alpha，使航向对齐 +x 轴 → 扫描线水平（等 y），沿 y 方向按行间距推进。
  const alpha = toRad(90 - headingDeg)
  const local = polygon.map(toLocal).map(p => rotate(p.x, p.y, -alpha))

  let minY = Infinity; let maxY = -Infinity
  local.forEach(p => { if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y })
  if (!(maxY > minY)) return []

  const n = local.length
  const lines = []
  // 半行间距内缩起步，覆盖到边缘
  for (let lineIdx = 0, y = minY + spacing / 2; y < maxY; y += spacing, lineIdx++) {
    const xs = []
    for (let i = 0; i < n; i++) {
      const a = local[i]
      const b = local[(i + 1) % n]
      const ay = a.y; const by = b.y
      // 半开区间避免顶点重复计数
      if ((ay <= y && by > y) || (by <= y && ay > y)) {
        const t = (y - ay) / (by - ay)
        xs.push(a.x + t * (b.x - a.x))
      }
    }
    if (xs.length < 2) continue
    xs.sort((p, q) => p - q)
    // 成对取内部线段；奇数行反向 → 蛇形
    const segs = []
    for (let k = 0; k + 1 < xs.length; k += 2) {
      segs.push([xs[k], xs[k + 1]])
    }
    if (lineIdx % 2 === 1) {
      segs.reverse()
      segs.forEach(s => s.reverse())
    }
    segs.forEach(([x0, x1]) => { lines.push({ x: x0, y }, { x: x1, y }) })
  }

  // 旋回原朝向并转经纬度
  return lines.map(p => {
    const r = rotate(p.x, p.y, alpha)
    return toLngLat(r.x, r.y)
  })
}
