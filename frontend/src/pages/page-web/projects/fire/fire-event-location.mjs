const UNUSABLE_LASER_QUALITY = new Set(['LASER_LOCATING', 'LASER_FAILED'])
const ROUTE_READY_QUALITY = new Set(['AUTO_WAYPOINT_READY', 'READY', 'OK', 'PRECISE'])

export function formatFireLocation (event, digits = 5, missing = '位置未返回') {
  const quality = normalizeGeoQuality(event)
  if (quality === 'LASER_LOCATING') return '正在精确定位'
  if (quality === 'LASER_FAILED') return '激光定位失败'
  const lat = Number(event?.lat ?? event?.latitude)
  const lng = Number(event?.lng ?? event?.longitude)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return missing
  return `${lat.toFixed(digits)}, ${lng.toFixed(digits)}`
}

export function isUsableFireLocation (event) {
  if (UNUSABLE_LASER_QUALITY.has(normalizeGeoQuality(event))) return false
  const lat = Number(event?.lat ?? event?.latitude)
  const lng = Number(event?.lng ?? event?.longitude)
  return Number.isFinite(lat) && Number.isFinite(lng) &&
    Math.abs(lat) <= 90 && Math.abs(lng) <= 180 &&
    !(lat === 0 && lng === 0)
}

export function isRouteReadyFireLocation (event) {
  return ROUTE_READY_QUALITY.has(normalizeGeoQuality(event))
}

export function normalizeGeoQuality (event) {
  return String(event?.geoQuality || '').toUpperCase()
}
