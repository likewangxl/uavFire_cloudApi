const UNKNOWN_LABEL = '未知状态'

const DETECTION_STATUS_LABELS = Object.freeze({
  DISARMED: '未启用识别',
  ARMING: '识别准备中',
  SCANNING: '巡检识别中',
  VISUAL_CONFIRMING: '可见光复核中',
  VISUAL_CONFIRMED: '已确认火情',
  HOLD_REQUESTED: '正在请求悬停',
  HOVER_VERIFYING: '悬停复核中',
  TARGET_ALIGNING: '正在对准目标',
  LASER_MEASURING: '激光测距中',
  RESULT_DURABLE: '定位结果已可靠保存',
  RESUME_REQUESTED: '正在请求恢复航线',
  MISSION_RESUMED: '已恢复航线巡检',
  MANUAL_HOLD: '人工接管保持'
})

const LOCATION_STATUS_LABELS = Object.freeze({
  LASER_LOCATING: '激光定位中',
  PRECISE: '激光精确定位',
  DEGRADED_OSD: '飞机位置降级定位'
})

const FLIGHT_STATUS_LABELS = Object.freeze({
  HOLD_REQUESTED: '正在请求悬停',
  HOVERING: '飞机已悬停',
  TARGET_ALIGNING: '飞机正在对准目标',
  LASER_MEASURING: '飞机保持测距',
  RESUME_REQUESTED: '正在请求恢复航线',
  MISSION_RESUMED: '飞机已恢复航线',
  SCANNING: '飞机正在巡检',
  MANUAL_HOLD: '飞机由人工保持'
})

const GEO_METHOD_LABELS = Object.freeze({
  LASER_RANGEFINDER: '激光测距定位',
  AIRCRAFT_OBSERVATION: '飞机观测位置'
})

const DETECTION_KIND_LABELS = Object.freeze({
  FIRE: '明火',
  SMOKE: '烟雾'
})

const EVENT_STATUS_LABELS = Object.freeze({
  NEW: '待处置',
  CANDIDATE: '候选火情',
  LOW_CONFIDENCE: '待确认',
  MISSION_CREATED: '已创建任务',
  CONFIRMED: '已确认',
  REJECTED: '已排除',
  IGNORED: '已忽略',
  ARCHIVED: '已归档'
})

const MISSION_STATUS_LABELS = Object.freeze({
  PENDING: '待创建',
  CREATED: '已创建',
  PREPARED: '已准备',
  PUBLISHING: '准备下发',
  READY: '待执行',
  WAITING: '等待中',
  WAITING_REVIEW: '待审批',
  APPROVED: '已审批',
  BOUND: '已绑定',
  ROUTE_GENERATED: '航线已生成',
  ROUTE_EXPORTED: '航线已导出',
  SENT_TO_DELIVERY: '已发送投放任务',
  ACCEPTED_BY_PILOT: '飞手已接收',
  IN_PROGRESS: '执行中',
  PAYLOAD_RELEASE_PENDING: '待投放',
  PAYLOAD_RELEASED: '已投放',
  RETURNING: '返航中',
  REVIEWING: '复盘中',
  RUNNING: '执行中',
  EXECUTING: '执行中',
  DELIVERING: '投放执行中',
  PAUSED: '已暂停',
  FINISHED: '已完成',
  COMPLETED: '已完成',
  STOPPED: '已停止',
  CANCELED: '已取消',
  CANCELLED: '已取消',
  FAILED: '执行失败',
  MANUAL_TAKEOVER: '人工接管',
  PAYLOAD_RELEASE_FAILED: '投放失败',
  RETURN_FAILED: '返航失败',
  ARCHIVED: '已归档',
  IDLE: '待命',
  LIVE: '直播中',
  ONLINE: '在线',
  OFFLINE: '离线',
  CONNECTED: '已连接',
  DISCONNECTED: '未连接'
})

const EVENT_SOURCE_LABELS = Object.freeze({
  AGENT_VISIBLE: 'Agent 可见光识别',
  M4T: 'M4T 机载识别',
  DRONE_VISIBLE: '无人机可见光识别',
  DRONE_THERMAL: '无人机红外识别',
  MANUAL: '人工上报'
})

const GEO_QUALITY_LABELS = Object.freeze({
  ...LOCATION_STATUS_LABELS,
  AUTO_WAYPOINT_READY: '旧链路航点就绪',
  READY: '旧链路定位就绪',
  OK: '旧链路定位可用',
  LASER_FAILED: '激光定位失败',
  DEM_MISSING: '缺少高程数据',
  RTK_NOT_FIXED: 'RTK 未固定',
  GEO_SNAPSHOT_INCOMPLETE: '定位快照不完整',
  LOW_ACCURACY: '定位精度不足'
})

export const formatDetectionStatus = value => formatKnown(DETECTION_STATUS_LABELS, value)
export const formatLocationStatus = value => formatKnown(LOCATION_STATUS_LABELS, value)
export const formatFlightStatus = value => formatKnown(FLIGHT_STATUS_LABELS, value)
export const formatGeoMethod = value => formatKnown(GEO_METHOD_LABELS, value)
export const formatDetectionKind = value => formatKnown(DETECTION_KIND_LABELS, value)
export const formatEventStatus = value => formatKnown(EVENT_STATUS_LABELS, value)
export const formatMissionStatus = value => formatKnown(MISSION_STATUS_LABELS, value)
export const formatEventSource = value => formatKnown(EVENT_SOURCE_LABELS, value)
export const formatGeoQuality = value => formatKnown(GEO_QUALITY_LABELS, value)

export function formatLocationExplanation (event = {}) {
  const location = codeOf(event.locationStatus ?? event.location_status ?? event.geoQuality ?? event.geo_quality)
  const kind = codeOf(event.detectionKind ?? event.detection_kind)
  if (location === 'DEGRADED_OSD') return '飞机观测位置，非火点精确位置'
  if (location === 'PRECISE' && kind === 'SMOKE') return '烟雾观测定位点，可能不是实际起火源'
  if (location === 'PRECISE' && kind === 'FIRE') return '激光测距火点精确位置'
  if (location === 'LASER_LOCATING') return '正在进行激光精确定位'
  return UNKNOWN_LABEL
}

export function isPreciseLaserFireLocation (event = {}) {
  const kind = codeOf(event.detectionKind ?? event.detection_kind)
  const location = codeOf(event.locationStatus ?? event.location_status ?? event.geoQuality ?? event.geo_quality)
  const method = codeOf(event.geoMethod ?? event.geo_method)
  const point = firePoint(event)
  return kind === 'FIRE' && location === 'PRECISE' && method === 'LASER_RANGEFINDER' && point !== null
}

export const isRouteReadyFireEvent = isPreciseLaserFireLocation
export const isPreciseFireMarkerAllowed = isPreciseLaserFireLocation
export const isAutomaticRouteAllowed = isPreciseLaserFireLocation

export function normalizeFireEventUpdate (update = {}) {
  return {
    ...update,
    eventId: update.eventId ?? update.event_id,
    notificationVersion: toVersion(update.notificationVersion ?? update.notification_version),
    detectionKind: update.detectionKind ?? update.detection_kind,
    detectionStatus: update.detectionStatus ?? update.detection_status ?? update.state,
    state: update.state ?? update.detectionStatus ?? update.detection_status,
    locationStatus: update.locationStatus ?? update.location_status,
    flightStatus: update.flightStatus ?? update.flight_status,
    geoMethod: update.geoMethod ?? update.geo_method,
    lat: update.lat ?? update.fireLat ?? update.fire_lat,
    lng: update.lng ?? update.fireLng ?? update.fire_lng,
    alt: update.alt ?? update.fireAlt ?? update.fire_alt,
    aircraftLat: update.aircraftLat ?? update.aircraft_lat,
    aircraftLng: update.aircraftLng ?? update.aircraft_lng,
    aircraftAlt: update.aircraftAlt ?? update.aircraft_alt
  }
}

export function reconcileFireEventUpdate (events = [], update = {}) {
  const normalized = normalizeFireEventUpdate(update)
  const eventId = normalized.eventId == null ? '' : String(normalized.eventId).trim()
  const version = normalized.notificationVersion
  const currentEvents = Array.isArray(events) ? events : []
  if (!eventId || version < 1) return { events: currentEvents, event: null, accepted: false, replaced: false }

  const index = currentEvents.findIndex(event => String(event?.eventId ?? event?.event_id ?? '') === eventId)
  if (index >= 0) {
    const current = currentEvents[index]
    const currentVersion = toVersion(current?.notificationVersion ?? current?.notification_version)
    if (version <= currentVersion) return { events: currentEvents, event: current, accepted: false, replaced: true }
    const merged = { ...current, ...normalized, eventId, notificationVersion: version }
    const next = currentEvents.slice()
    next[index] = merged
    return { events: next, event: merged, accepted: true, replaced: true }
  }

  const inserted = { ...normalized, eventId, notificationVersion: version }
  return { events: [inserted, ...currentEvents], event: inserted, accepted: true, replaced: false }
}

export function fireEventNotificationKey (eventOrId) {
  const eventId = typeof eventOrId === 'object' && eventOrId !== null
    ? eventOrId.eventId ?? eventOrId.event_id
    : eventOrId
  return `fire-event:${String(eventId ?? 'unknown')}`
}

function formatKnown (mapping, value) {
  return mapping[codeOf(value)] || UNKNOWN_LABEL
}

function codeOf (value) {
  return value == null ? '' : String(value).trim().toUpperCase()
}

function toVersion (value) {
  const version = Number(value)
  return Number.isInteger(version) && version > 0 ? version : 0
}

function firePoint (event) {
  const lat = Number(event.lat ?? event.latitude ?? event.fireLat ?? event.fire_lat)
  const lng = Number(event.lng ?? event.longitude ?? event.fireLng ?? event.fire_lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null
  if (Math.abs(lat) > 90 || Math.abs(lng) > 180 || (lat === 0 && lng === 0)) return null
  return { lat, lng }
}
