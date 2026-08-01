import {
  formatGeoQuality,
  formatLocationExplanation,
  isRouteReadyFireEvent
} from './fire-event-status.mjs'

export function formatFireLocation (event, digits = 5, missing = '位置未返回') {
  const quality = normalizeGeoQuality(event)
  const location = String(event?.locationStatus || event?.location_status || quality).toUpperCase()
  if (location === 'DEGRADED_OSD') return formatLocationExplanation(event)
  if (location === 'LASER_LOCATING') return '正在精确定位'
  if (quality === 'LASER_FAILED') return '激光定位失败'
  const lat = Number(event?.lat ?? event?.latitude)
  const lng = Number(event?.lng ?? event?.longitude)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return missing
  if (location !== 'PRECISE') return formatGeoQuality(quality || location)
  const coordinate = `${lat.toFixed(digits)}, ${lng.toFixed(digits)}`
  return String(event?.detectionKind || event?.detection_kind || '').toUpperCase() === 'SMOKE'
    ? `${coordinate} · ${formatLocationExplanation(event)}`
    : coordinate
}

export function isUsableFireLocation (event) {
  const location = String(event?.locationStatus || event?.location_status || normalizeGeoQuality(event)).toUpperCase()
  const method = String(event?.geoMethod || event?.geo_method || '').toUpperCase()
  if (location !== 'PRECISE' || method !== 'LASER_RANGEFINDER') return false
  const lat = Number(event?.lat ?? event?.latitude)
  const lng = Number(event?.lng ?? event?.longitude)
  return Number.isFinite(lat) && Number.isFinite(lng) &&
    Math.abs(lat) <= 90 && Math.abs(lng) <= 180 &&
    !(lat === 0 && lng === 0)
}

export function isRouteReadyFireLocation (event) {
  return isRouteReadyFireEvent(event)
}

export function normalizeGeoQuality (event) {
  return String(event?.geoQuality || '').toUpperCase()
}
