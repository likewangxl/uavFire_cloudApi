const LEVEL_ORDER = { HIGH: 3, MEDIUM: 2, LOW: 1 }
const LEVEL_STYLE = {
  HIGH: { tone: 'danger', color: '#ff6172', priority: 30 },
  MEDIUM: { tone: 'warning', color: '#ffd866', priority: 20 },
  LOW: { tone: 'safe', color: '#42e29d', priority: 10 },
  UNKNOWN: { tone: 'default', color: '#45ddff', priority: 0 }
}

export function buildSituationLayers ({
  fireEvents = [],
  missionWaypoints = {},
  msdkDevices = [],
  deliveryTargets = []
} = {}) {
  const fireMarkers = []
  const errorCircles = []
  const routeLines = []
  const aircraftMarkers = []
  const unlocatedEvents = []
  const boundsPoints = []
  const seenRoutes = new Set()

  for (const event of fireEvents) {
    const point = eventPoint(event)
    const qualityOk = isLocatedFireEvent(event)
    if (!point || !qualityOk) {
      unlocatedEvents.push({
        eventId: event?.eventId || event?.id || '--',
        fireLevel: normalizeLevel(event?.fireLevel),
        reason: point ? `定位质量 ${event?.geoQuality || '未知'}` : '缺少经纬度',
        missionNo: event?.missionNo || null
      })
      continue
    }

    const level = normalizeLevel(event?.fireLevel)
    const style = LEVEL_STYLE[level]
    fireMarkers.push({
      id: `fire:${event?.eventId || event?.id || fireMarkers.length + 1}`,
      eventId: event?.eventId || event?.id || '--',
      coordinates: point,
      level,
      tone: style.tone,
      color: style.color,
      priority: style.priority,
      popup: {
        title: `${level} · ${event?.eventId || event?.id || '火情'}`,
        detail: [
          `置信度 ${formatConfidence(event?.confidence)}`,
          `定位 ${event?.geoQuality || '未知'}`,
          event?.missionNo ? `任务 ${event.missionNo}` : '未关联任务',
          `更新时间 ${formatTime(event?.lastSeenTime || event?.eventTimestamp || event?.createTime)}`
        ].join(' · ')
      }
    })
    boundsPoints.push(point)

    const radius = Number(event?.geoErrorRadiusM)
    if (Number.isFinite(radius) && radius > 0) {
      errorCircles.push({
        id: `error:${event?.eventId || event?.id || errorCircles.length + 1}`,
        eventId: event?.eventId || event?.id || '--',
        center: point,
        radiusM: radius,
        color: style.color,
        estimated: true,
        label: '定位误差圈'
      })
    }

    const missionNo = event?.missionNo
    if (missionNo && !seenRoutes.has(missionNo)) {
      const route = buildRouteLine(missionNo, missionWaypoints[missionNo], event, style)
      if (route) {
        routeLines.push(route)
        route.coordinates.forEach(point => boundsPoints.push(point))
        seenRoutes.add(missionNo)
      }
    }
  }

  for (const device of msdkDevices) {
    const point = numberPoint(device?.longitude, device?.latitude)
    if (!point) continue
    aircraftMarkers.push(buildAircraftMarker({
      id: `aircraft:monitor:${device?.aircraftSn || aircraftMarkers.length + 1}`,
      role: '火情监测',
      name: device?.model || device?.deviceName || '监测飞机',
      sn: device?.aircraftSn,
      online: Boolean(device?.online),
      point,
      batteryPercent: device?.batteryPercent,
      height: device?.height,
      speed: device?.horizontalSpeed,
      status: device?.mode || device?.connectionState || (device?.online ? '在线' : '离线')
    }))
    boundsPoints.push(point)
  }

  for (const target of deliveryTargets) {
    const point = numberPoint(target?.longitude, target?.latitude)
    if (!point) continue
    aircraftMarkers.push(buildAircraftMarker({
      id: `aircraft:delivery:${target?.deviceSn || aircraftMarkers.length + 1}`,
      role: 'FC100 投放',
      name: target?.model || target?.displayName || target?.callsign || 'DJI Flycart100',
      sn: target?.deviceSn,
      online: Boolean(target?.online),
      point,
      batteryPercent: target?.batteryPercent,
      height: target?.altitude,
      speed: target?.horizontalSpeed,
      status: target?.taskStatus || target?.streamStatus || (target?.online ? '在线' : '离线')
    }))
    boundsPoints.push(point)
  }

  fireMarkers.sort((a, b) => b.priority - a.priority)

  return {
    fireMarkers,
    errorCircles,
    routeLines,
    aircraftMarkers,
    bounds: buildBounds(boundsPoints),
    unlocatedEvents
  }
}

export async function loadTelluxModule (moduleUrl, fallbackImport) {
  if (!moduleUrl && typeof fallbackImport !== 'function') return { status: 'unconfigured', module: null }
  try {
    const mod = moduleUrl
      ? await import(/* @vite-ignore */ moduleUrl)
      : await fallbackImport()
    return { status: 'loaded', module: mod }
  } catch (error) {
    return { status: 'error', module: null, error: error?.message || String(error) }
  }
}

function buildRouteLine (missionNo, waypoints, event, style) {
  const coordinates = Array.isArray(waypoints)
    ? waypoints
      .slice()
      .sort((a, b) => Number(a?.waypointIndex ?? 0) - Number(b?.waypointIndex ?? 0))
      .map(wp => numberPoint(wp?.lng, wp?.lat))
      .filter(Boolean)
    : []
  if (coordinates.length < 2) return null
  return {
    id: `route:${missionNo}`,
    missionNo,
    eventId: event?.eventId || event?.id || '--',
    coordinates,
    color: style.color,
    popup: {
      title: `任务航线 · ${missionNo}`,
      detail: `${coordinates.length} 个航点 · 火情 ${event?.eventId || '--'} · ${event?.missionStatus || event?.status || '状态未知'}`
    }
  }
}

function buildAircraftMarker ({
  id,
  role,
  name,
  sn,
  online,
  point,
  batteryPercent,
  height,
  speed,
  status
}) {
  return {
    id,
    role,
    name,
    sn: sn || '--',
    coordinates: point,
    online,
    tone: online ? 'safe' : 'danger',
    color: online ? '#42e29d' : '#ff6172',
    popup: {
      title: `${name} · ${role}`,
      detail: [
        `SN ${sn || '--'}`,
        `状态 ${status || '--'}`,
        `电量 ${formatPercent(batteryPercent)}`,
        `高度 ${formatNumber(height, 1)}m`,
        `速度 ${formatNumber(speed, 1)}m/s`
      ].join(' · ')
    }
  }
}

function isLocatedFireEvent (event) {
  const quality = String(event?.geoQuality || '').toUpperCase()
  if (!quality) return Boolean(eventPoint(event))
  return isRouteReadyFireLocation(event)
}

function eventPoint (event) {
  return numberPoint(event?.lng ?? event?.longitude, event?.lat ?? event?.latitude)
}

function numberPoint (lng, lat) {
  const x = Number(lng)
  const y = Number(lat)
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null
  if (Math.abs(x) > 180 || Math.abs(y) > 90) return null
  if (x === 0 && y === 0) return null
  return [x, y]
}

function buildBounds (points) {
  if (!points.length) return null
  return points.reduce((acc, point) => ({
    west: Math.min(acc.west, point[0]),
    south: Math.min(acc.south, point[1]),
    east: Math.max(acc.east, point[0]),
    north: Math.max(acc.north, point[1])
  }), {
    west: points[0][0],
    south: points[0][1],
    east: points[0][0],
    north: points[0][1]
  })
}

function normalizeLevel (level) {
  const value = String(level || '').toUpperCase()
  return LEVEL_ORDER[value] ? value : 'UNKNOWN'
}

function formatConfidence (value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '--'
  return n <= 1 ? n.toFixed(2) : `${n.toFixed(0)}%`
}

function formatPercent (value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '--'
  return `${n.toFixed(0)}%`
}

function formatNumber (value, digits = 0) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '--'
  return n.toFixed(digits)
}

function formatTime (value) {
  const raw = Number(value)
  const date = Number.isFinite(raw) ? new Date(raw) : new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
}
import { isRouteReadyFireLocation } from './fire/fire-event-location.mjs'
