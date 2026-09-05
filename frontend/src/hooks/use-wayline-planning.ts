// Click-to-fly wayline planning for M4T + RC Plus 2 + DRC.
//
// See WORK_RECORD.md section 8 for background. This module exposes a single
// app-wide reactive store plus helpers to:
//   1) collect waypoints from map clicks (GCJ02 lng/lat from AMap, plus WGS84
//      converted copies for the DJI API),
//   2) dispatch them sequentially via postFlyToPoint, advancing on either a
//      FlyToPointProgress error or a client-side OSD distance-to-target check,
//   3) be driven from two sibling components (wayline.vue as the control
//      surface and GMap.vue as the map-click source / visualisation).

import { computed, reactive, watch } from 'vue'
import { message } from 'ant-design-vue'
import EventBus from '/@/event-bus/'
import { postFlyToPoint, deleteFlyToPoint } from '/@/api/drone-control/drone'
import { EBizCode, ELocalStorageKey } from '/@/types'
import { FlyToPointMessage } from '/@/types/drone-control'
import type {
  CreatePlannedWaylineBody,
  PlannedWaypoint as PlannedWaypointBody,
  PlannedWaylineRecord,
  UpdatePlannedWaylineBody,
  WaypointAction,
  WaypointActuatorFunc,
  WaypointHeadingMode,
  WaypointTurnMode,
} from '/@/types/wayline'
import { gcj02towgs84, wgs84togcj02 } from '/@/vendors/coordtransform'
import rootStore from '/@/store'
import { uuidv4 } from '/@/utils/uuid'
// @ts-ignore .mjs 纯计算模块（node 测试可直跑）
import { generateAreaCoverage, getCameraPreset, lineSpacingFromOverlap } from '/@/components/wayline-planner/area-utils.mjs'
// @ts-ignore .mjs 纯计算策略（node 测试可直跑）
import { plannedWaylineTrackState, shouldAcceptFlightPosition } from '/@/components/wayline-planner/flight-track-policy.mjs'

export interface PlannedWaypoint {
  id: string
  // GCJ02 is used for AMap rendering and distance calculations.
  gcjLng: number
  gcjLat: number
  // WGS84 is what we send to DJI.
  wgsLng: number
  wgsLat: number
  // Target height relative to takeoff point (m).
  height: number
  // ---- L1 per-航点定制 (全部可选,缺省走全局) ----
  speed?: number
  gimbalPitch?: number
  gimbalYaw?: number
  headingMode?: WaypointHeadingMode
  headingAngle?: number
  poiLng?: number
  poiLat?: number
  poiAlt?: number
  turnMode?: WaypointTurnMode
  turnDamping?: number
  actions?: WaypointAction[]
}

export interface FlightPosition {
  aircraftSn: string
  gcjLng: number
  gcjLat: number
  wgsLng?: number
  wgsLat?: number
  height?: number
  updatedAt?: number
  currentWaypointIndex?: number
  totalWaypoints?: number
  source?: 'cloud-osd' | 'msdk-agent' | 'planned-record' | 'unknown'
}

export enum PlanningExecState {
  IDLE = 'idle',
  DISPATCHING = 'dispatching',
  TRAVELLING = 'travelling',
  ARRIVED = 'arrived',
  STOPPED = 'stopped',
  ERROR = 'error',
}

const DEFAULT_REACH_RADIUS_M = 3
const DEFAULT_REACH_STABLE_MS = 1500
const DEFAULT_HEIGHT_M = 30
const DEFAULT_MAX_SPEED = 5
const DEFAULT_AIRCRAFT_MODEL_KEY = 'M30T'
// M300 适配分支的面状航线以 Pilot 样例中的 Zenmuse H20T 参数为默认值。
// 其他载荷仍可在面状航线参数面板中显式选择。
const DEFAULT_AREA_CAMERA_KEY = 'H20T'
const DEFAULT_WAYPOINT_TURN_MODE: WaypointTurnMode = 'toPointAndStopWithDiscontinuityCurvature'
const WAYPOINT_EXECUTION_TIMEOUT_MS = 90_000
const MIN_WAYPOINT_SPACING_M = 16
const PLANNING_DRAFT_VERSION = 1

const state = reactive({
  active: false,
  executing: false,
  execState: PlanningExecState.IDLE,
  waypoints: [] as PlannedWaypoint[],
  previewWaypoints: [] as PlannedWaypoint[],
  previewTitle: '',
  previewMaxSpeed: undefined as number | undefined,
  // 航线类型：waypoint=逐点布点；patrol=闭合回路（保存时末点接回首点）；
  // area=面状（先画多边形 areaPolygon，再按相机重叠率生成弓字形航点）。
  routeKind: 'waypoint' as 'waypoint' | 'patrol' | 'area',
  areaPolygon: [] as Array<{ gcjLng: number; gcjLat: number }>,
  areaParams: {
    cameraKey: DEFAULT_AREA_CAMERA_KEY,
    frontOverlap: 80,
    sideOverlap: 70,
    headingDeg: 0,
  },
  selectedWaypointId: '',
  currentIndex: -1,
  gatewaySn: '',
  aircraftSn: '',
  editingPlannedWaylineId: '',
  aircraftModelKey: '',
  defaultHeight: DEFAULT_HEIGHT_M,
  maxSpeed: DEFAULT_MAX_SPEED,
  // L1 mission 配置 (undefined = 用后端默认 goHome/goContinue/goBack/20/5)
  finishAction: undefined as string | undefined,
  exitOnRcLost: undefined as string | undefined,
  rcLostAction: undefined as string | undefined,
  takeoffSecurityHeight: undefined as number | undefined,
  globalTransitionalSpeed: undefined as number | undefined,
  rthAltitude: undefined as number | undefined,
  statusText: '',
  lastError: '',
  flightPosition: null as FlightPosition | null,
  // 当前地图应跟踪的飞机 SN。页面上 MSDK 监测轮询与 FC100 轮询会同时把各自飞机的
  // 位置写进单一 flightPosition 槽，导致飞机在两架之间来回跳。以此为唯一闸门：
  // 只接受跟踪目标那架的位置写入。由“正在执行任务的飞机”/“用户显式选择”设定。
  trackedAircraftSn: '',
  // 轨迹按任务隔离；revision 用于通知地图立即清掉上一任务的折线。
  flightTrackSessionId: '',
  flightTrackPlannedWaylineId: '',
  flightTrackRevision: 0,
  flightTrackRecording: false,
  // 自增令牌：每次需要把地图居中到飞机时 +1。GMap 监听它并执行一次性居中
  // （进入航线页面时由 wayline.vue 触发），不做持续跟随。
  recenterAircraftToken: 0,
})

let wsSubscribed = false
let osdWatcherStop: (() => void) | null = null
let reachStableSince = 0
let activeExecutionId = 0
let waypointTimeoutId: number | null = null

interface PersistedPlanningDraft {
  version: number
  gatewaySn: string
  aircraftSn: string
  aircraftModelKey?: string
  defaultHeight: number
  maxSpeed: number
  finishAction?: string
  exitOnRcLost?: string
  rcLostAction?: string
  takeoffSecurityHeight?: number
  globalTransitionalSpeed?: number
  rthAltitude?: number
  routeKind?: 'waypoint' | 'patrol' | 'area'
  areaPolygon?: Array<{ gcjLng: number; gcjLat: number }>
  areaParams?: {
    cameraKey: string
    frontOverlap: number
    sideOverlap: number
    headingDeg: number
  }
  waypoints: PlannedWaypoint[]
}

function buildPersistedDraft (): PersistedPlanningDraft {
  return {
    version: PLANNING_DRAFT_VERSION,
    gatewaySn: state.gatewaySn,
    aircraftSn: state.aircraftSn,
    aircraftModelKey: state.aircraftModelKey,
    defaultHeight: state.defaultHeight,
    maxSpeed: state.maxSpeed,
    finishAction: state.finishAction,
    exitOnRcLost: state.exitOnRcLost,
    rcLostAction: state.rcLostAction,
    takeoffSecurityHeight: state.takeoffSecurityHeight,
    globalTransitionalSpeed: state.globalTransitionalSpeed,
    rthAltitude: state.rthAltitude,
    routeKind: state.routeKind,
    areaPolygon: state.areaPolygon.map(vertex => ({ ...vertex })),
    areaParams: { ...state.areaParams },
    waypoints: state.waypoints.map(wp => ({ ...wp })),
  }
}

function persistDraft () {
  if (typeof window === 'undefined') return
  try {
    const hasDraft = state.gatewaySn || state.aircraftSn || state.aircraftModelKey || state.waypoints.length > 0
    if (!hasDraft) {
      window.localStorage.removeItem(ELocalStorageKey.PlannedWaylineDraft)
      return
    }
    window.localStorage.setItem(ELocalStorageKey.PlannedWaylineDraft, JSON.stringify(buildPersistedDraft()))
  } catch (e) {
    // Ignore storage failures. The planner still works without persistence.
  }
}

function restoreDraft () {
  if (typeof window === 'undefined') return
  try {
    const raw = window.localStorage.getItem(ELocalStorageKey.PlannedWaylineDraft)
    if (!raw) return
    const parsed = JSON.parse(raw) as Partial<PersistedPlanningDraft>
    if (parsed.version !== PLANNING_DRAFT_VERSION) return

    state.gatewaySn = typeof parsed.gatewaySn === 'string' ? parsed.gatewaySn : ''
    state.aircraftSn = typeof parsed.aircraftSn === 'string' ? parsed.aircraftSn : ''
    state.aircraftModelKey = typeof parsed.aircraftModelKey === 'string' ? parsed.aircraftModelKey : ''
    state.editingPlannedWaylineId = ''
    state.defaultHeight = Number.isFinite(Number(parsed.defaultHeight)) ? Number(parsed.defaultHeight) : DEFAULT_HEIGHT_M
    state.maxSpeed = Number.isFinite(Number(parsed.maxSpeed)) ? Number(parsed.maxSpeed) : DEFAULT_MAX_SPEED
    state.finishAction = typeof parsed.finishAction === 'string' ? parsed.finishAction : undefined
    state.exitOnRcLost = typeof parsed.exitOnRcLost === 'string' ? parsed.exitOnRcLost : undefined
    state.rcLostAction = typeof parsed.rcLostAction === 'string' ? parsed.rcLostAction : undefined
    state.takeoffSecurityHeight = Number.isFinite(Number(parsed.takeoffSecurityHeight)) ? Number(parsed.takeoffSecurityHeight) : undefined
    state.globalTransitionalSpeed = Number.isFinite(Number(parsed.globalTransitionalSpeed)) ? Number(parsed.globalTransitionalSpeed) : undefined
    state.rthAltitude = Number.isFinite(Number(parsed.rthAltitude)) ? Number(parsed.rthAltitude) : undefined
    state.routeKind = parsed.routeKind === 'patrol' || parsed.routeKind === 'area' ? parsed.routeKind : 'waypoint'
    state.areaPolygon = Array.isArray(parsed.areaPolygon)
      ? parsed.areaPolygon
        .map(vertex => ({ gcjLng: Number(vertex.gcjLng), gcjLat: Number(vertex.gcjLat) }))
        .filter(vertex => Number.isFinite(vertex.gcjLng) && Number.isFinite(vertex.gcjLat))
      : []
    if (parsed.areaParams) {
      state.areaParams.cameraKey = typeof parsed.areaParams.cameraKey === 'string' ? parsed.areaParams.cameraKey : DEFAULT_AREA_CAMERA_KEY
      state.areaParams.frontOverlap = Number.isFinite(Number(parsed.areaParams.frontOverlap)) ? Number(parsed.areaParams.frontOverlap) : 80
      state.areaParams.sideOverlap = Number.isFinite(Number(parsed.areaParams.sideOverlap)) ? Number(parsed.areaParams.sideOverlap) : 70
      state.areaParams.headingDeg = Number.isFinite(Number(parsed.areaParams.headingDeg)) ? Number(parsed.areaParams.headingDeg) : 0
    }
    const draftWaypoints = Array.isArray(parsed.waypoints) ? parsed.waypoints : []
    const restoredWaypoints = draftWaypoints.map(wp => normalizePlannedWaypoint(wp as PlannedWaypoint))
    state.waypoints = restoredWaypoints
    state.active = false
    state.executing = false
    state.execState = PlanningExecState.IDLE
    state.currentIndex = -1
    state.statusText = restoredWaypoints.length > 0 ? `Restored ${restoredWaypoints.length} waypoint(s) from the last session.` : ''
    state.lastError = ''
  } catch (e) {
    window.localStorage.removeItem(ELocalStorageKey.PlannedWaylineDraft)
  }
}

function clearWaypointTimeout () {
  if (waypointTimeoutId !== null) {
    window.clearTimeout(waypointTimeoutId)
    waypointTimeoutId = null
  }
}

function resetExecutionInternal () {
  if (osdWatcherStop) {
    osdWatcherStop()
    osdWatcherStop = null
  }
  clearWaypointTimeout()
  reachStableSince = 0
  state.executing = false
  state.currentIndex = -1
}

function distanceMeters (lng1: number, lat1: number, lng2: number, lat2: number): number {
  const earthRadiusM = 6378137
  const rad = Math.PI / 180
  const dLat = (lat2 - lat1) * rad
  const dLng = (lng2 - lng1) * rad
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2
  return 2 * earthRadiusM * Math.asin(Math.min(1, Math.sqrt(a)))
}

function routeWaypoints () {
  return state.previewWaypoints.length > 0 ? state.previewWaypoints : state.waypoints
}

export const planningRouteStats = computed(() => {
  const waypoints = routeWaypoints()
  let totalDistanceM = 0
  for (let i = 1; i < waypoints.length; i++) {
    const prev = waypoints[i - 1]
    const current = waypoints[i]
    totalDistanceM += distanceMeters(prev.gcjLng, prev.gcjLat, current.gcjLng, current.gcjLat)
  }
  const speed = normalizePositiveNumber(state.maxSpeed, DEFAULT_MAX_SPEED)
  return {
    totalDistanceM,
    estimatedSeconds: speed > 0 ? totalDistanceM / speed : 0,
  }
})

function finiteNumber (value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : null
}

function normalizePositiveNumber (value: unknown, fallback: number): number {
  const numberValue = finiteNumber(value)
  return numberValue !== null && numberValue > 0 ? numberValue : fallback
}

function normalizePlannedWaypoint (wp: PlannedWaypoint): PlannedWaypoint {
  const raw = wp as PlannedWaypoint & {
    lng?: number | string
    lat?: number | string
    longitude?: number | string
    latitude?: number | string
  }
  let gcjLng = finiteNumber(raw.gcjLng ?? raw.lng ?? raw.longitude)
  let gcjLat = finiteNumber(raw.gcjLat ?? raw.lat ?? raw.latitude)
  let wgsLng = finiteNumber(raw.wgsLng)
  let wgsLat = finiteNumber(raw.wgsLat)

  if ((!Number.isFinite(gcjLng) || !Number.isFinite(gcjLat)) && Number.isFinite(wgsLng) && Number.isFinite(wgsLat)) {
    const [convertedGcjLng, convertedGcjLat] = wgs84togcj02(wgsLng, wgsLat) as [number, number]
    gcjLng = finiteNumber(convertedGcjLng)
    gcjLat = finiteNumber(convertedGcjLat)
  }

  if ((!Number.isFinite(wgsLng) || !Number.isFinite(wgsLat)) && Number.isFinite(gcjLng) && Number.isFinite(gcjLat)) {
    const [convertedWgsLng, convertedWgsLat] = gcj02towgs84(gcjLng, gcjLat) as [number, number]
    wgsLng = finiteNumber(convertedWgsLng)
    wgsLat = finiteNumber(convertedWgsLat)
  }

  if (!Number.isFinite(gcjLng) || !Number.isFinite(gcjLat) || !Number.isFinite(wgsLng) || !Number.isFinite(wgsLat)) {
    throw new Error('Invalid waypoint coordinates.')
  }

  return {
    id: typeof raw.id === 'string' && raw.id ? raw.id : uuidv4(),
    gcjLng: gcjLng as number,
    gcjLat: gcjLat as number,
    wgsLng: wgsLng as number,
    wgsLat: wgsLat as number,
    height: normalizePositiveNumber(raw.height, normalizePositiveNumber(state.defaultHeight, DEFAULT_HEIGHT_M)),
    speed: Number.isFinite(Number(raw.speed)) ? Number(raw.speed) : undefined,
    gimbalPitch: Number.isFinite(Number(raw.gimbalPitch)) ? Number(raw.gimbalPitch) : undefined,
    gimbalYaw: Number.isFinite(Number(raw.gimbalYaw)) ? Number(raw.gimbalYaw) : undefined,
    headingMode: typeof raw.headingMode === 'string' ? raw.headingMode as WaypointHeadingMode : undefined,
    headingAngle: Number.isFinite(Number(raw.headingAngle)) ? Number(raw.headingAngle) : undefined,
    poiLng: Number.isFinite(Number(raw.poiLng)) ? Number(raw.poiLng) : undefined,
    poiLat: Number.isFinite(Number(raw.poiLat)) ? Number(raw.poiLat) : undefined,
    poiAlt: Number.isFinite(Number(raw.poiAlt)) ? Number(raw.poiAlt) : undefined,
    turnMode: typeof raw.turnMode === 'string' ? raw.turnMode as WaypointTurnMode : undefined,
    turnDamping: Number.isFinite(Number(raw.turnDamping)) ? Number(raw.turnDamping) : undefined,
    actions: Array.isArray(raw.actions)
      ? raw.actions.map((a: any) => ({
        actionId: Number.isFinite(Number(a?.actionId)) ? Number(a.actionId) : undefined,
        actionTrigger: typeof a?.actionTrigger === 'string' ? a.actionTrigger : undefined,
        actionTriggerParam: Number.isFinite(Number(a?.actionTriggerParam)) ? Number(a.actionTriggerParam) : undefined,
        actuatorFunc: a?.actuatorFunc,
        params: a?.params && typeof a.params === 'object' ? { ...a.params } : undefined,
      }))
      : undefined,
  }
}

export function updateAircraftFlightPosition (position: FlightPosition | null) {
  // 单一跟踪闸门：已设定跟踪目标时，丢弃其它飞机的位置写入，避免地图在两架飞机
  // （如在飞的 M4T 与停在地面的 FC100）之间来回跳。null（清空）始终放行。
  if (position && state.trackedAircraftSn && position.aircraftSn !== state.trackedAircraftSn) {
    return
  }
  // 规划航线执行期间固定使用 Agent 设备状态。Cloud OSD 与 Agent 的上报频率、
  // 设备身份映射不同，混写同一个 marker 会让飞机在当前位置和起飞点之间跳变。
  if (position && state.flightTrackRecording && position.source === 'cloud-osd') {
    return
  }
  if (position && state.flightPosition && !shouldAcceptFlightPosition(state.flightPosition, position)) {
    // 迟到的低频 Agent/任务轮询不得把实时 OSD 坐标拉回去；航点进度仍可合并。
    if (position.aircraftSn === state.flightPosition.aircraftSn) {
      if (position.currentWaypointIndex != null) state.flightPosition.currentWaypointIndex = position.currentWaypointIndex
      if (position.totalWaypoints != null) state.flightPosition.totalWaypoints = position.totalWaypoints
    }
    return
  }
  if (position && state.flightPosition?.aircraftSn === position.aircraftSn) {
    // MSDK 设备状态提供最新坐标，但不带航点序号。保留同一任务最近一次进度，
    // 避免每次位置轮询都把 2/10 一类标签清空。
    state.flightPosition = {
      ...position,
      currentWaypointIndex: position.currentWaypointIndex ?? state.flightPosition.currentWaypointIndex,
      totalWaypoints: position.totalWaypoints ?? state.flightPosition.totalWaypoints,
    }
    return
  }
  state.flightPosition = position
}

export function updateFlightPositionProgress (
  aircraftSn: string,
  currentWaypointIndex?: number,
  totalWaypoints?: number,
) {
  if (!state.flightPosition || state.flightPosition.aircraftSn !== aircraftSn) return
  if (currentWaypointIndex != null) state.flightPosition.currentWaypointIndex = currentWaypointIndex
  if (totalWaypoints != null) state.flightPosition.totalWaypoints = totalWaypoints
}

function beginFlightTrackSession (sessionId: string, plannedWaylineId = '', allowSwitch = false): boolean {
  if (!sessionId) return false
  const ownedByAnotherPlannedWayline = !!state.flightTrackPlannedWaylineId &&
    !!plannedWaylineId &&
    state.flightTrackPlannedWaylineId !== plannedWaylineId
  if (ownedByAnotherPlannedWayline && !allowSwitch) return false
  const isSameActivePlannedWayline = state.flightTrackRecording &&
    !!plannedWaylineId &&
    state.flightTrackPlannedWaylineId === plannedWaylineId
  if (state.flightTrackSessionId !== sessionId && !isSameActivePlannedWayline) {
    state.flightTrackSessionId = sessionId
    state.flightTrackPlannedWaylineId = plannedWaylineId
    state.flightTrackRevision += 1
    state.flightPosition = null
  } else {
    // execute 初始响应没有 flightId 时先用 plannedWaylineId；轮询拿到真实
    // flightId 后只升级会话标识，不重置本次已经采集的点。
    state.flightTrackSessionId = sessionId
    if (plannedWaylineId) state.flightTrackPlannedWaylineId = plannedWaylineId
  }
  state.flightTrackRecording = true
  return true
}

function finishFlightTrackSession (sessionId?: string, plannedWaylineId = '') {
  const matchesPlannedWayline = !!plannedWaylineId &&
    state.flightTrackPlannedWaylineId === plannedWaylineId
  if (!sessionId || state.flightTrackSessionId === sessionId || matchesPlannedWayline) {
    state.flightTrackRecording = false
  }
}

export function syncPlannedWaylineTrackSession (
  record: PlannedWaylineRecord | null | undefined,
  allowSwitch = false,
): boolean {
  if (!record) return false
  const track = plannedWaylineTrackState(record)
  if (!track.sessionId) return false
  if (track.recording) {
    return beginFlightTrackSession(track.sessionId, record.plannedWaylineId, allowSwitch)
  } else if (track.terminal) {
    if (state.flightTrackPlannedWaylineId && state.flightTrackPlannedWaylineId !== record.plannedWaylineId) {
      return false
    }
    finishFlightTrackSession(track.sessionId, record.plannedWaylineId)
    return true
  }
  return false
}

// 设定地图跟踪的飞机（在飞的飞机或用户显式选择的飞机）。切换目标时清掉旧飞机的
// 残留位置，避免短暂显示上一架的点。
export function setTrackedAircraft (aircraftSn: string) {
  const sn = aircraftSn || ''
  if (state.trackedAircraftSn === sn) return
  state.trackedAircraftSn = sn
  if (sn && state.flightPosition && state.flightPosition.aircraftSn !== sn) {
    state.flightPosition = null
  }
}

// 请求把地图视图居中到当前飞机位置（一次性）。GMap 监听 recenterAircraftToken，
// 若此刻已有飞机位置则立即居中，否则等下一次飞机位置到达后居中一次。
export function requestAircraftRecenter () {
  state.recenterAircraftToken = (state.recenterAircraftToken || 0) + 1
}

export function setFlightPositionFromWgs (
  aircraftSn: string,
  wgsLng: unknown,
  wgsLat: unknown,
  options: {
    height?: unknown
    updatedAt?: unknown
    currentWaypointIndex?: number
    totalWaypoints?: number
    source?: FlightPosition['source']
  } = {},
) {
  const lng = finiteNumber(wgsLng)
  const lat = finiteNumber(wgsLat)
  if (!aircraftSn || lng === null || lat === null || lng === 0 || lat === 0) return
  const [gcjLngRaw, gcjLatRaw] = wgs84togcj02(lng, lat) as [number, number]
  const gcjLng = finiteNumber(gcjLngRaw)
  const gcjLat = finiteNumber(gcjLatRaw)
  if (gcjLng === null || gcjLat === null) return
  updateAircraftFlightPosition({
    aircraftSn,
    gcjLng,
    gcjLat,
    wgsLng: lng,
    wgsLat: lat,
    height: finiteNumber(options.height) ?? undefined,
    updatedAt: finiteNumber(options.updatedAt) ?? Date.now(),
    currentWaypointIndex: options.currentWaypointIndex,
    totalWaypoints: options.totalWaypoints,
    source: options.source || 'unknown',
  })
}

export function setFlightPositionFromGcj (
  aircraftSn: string,
  gcjLngValue: unknown,
  gcjLatValue: unknown,
  options: {
    wgsLng?: unknown
    wgsLat?: unknown
    height?: unknown
    updatedAt?: unknown
    currentWaypointIndex?: number
    totalWaypoints?: number
    source?: FlightPosition['source']
  } = {},
) {
  const gcjLng = finiteNumber(gcjLngValue)
  const gcjLat = finiteNumber(gcjLatValue)
  if (!aircraftSn || gcjLng === null || gcjLat === null || gcjLng === 0 || gcjLat === 0) return
  updateAircraftFlightPosition({
    aircraftSn,
    gcjLng,
    gcjLat,
    wgsLng: finiteNumber(options.wgsLng) ?? undefined,
    wgsLat: finiteNumber(options.wgsLat) ?? undefined,
    height: finiteNumber(options.height) ?? undefined,
    updatedAt: finiteNumber(options.updatedAt) ?? Date.now(),
    currentWaypointIndex: options.currentWaypointIndex,
    totalWaypoints: options.totalWaypoints,
    source: options.source || 'unknown',
  })
}

export function setFlightPositionFromRecord (record: PlannedWaylineRecord | null | undefined) {
  if (!record) return
  const aircraftSn = record.droneSn || record.aircraftSn || ''
  if (!aircraftSn) return

  const gcjLng = finiteNumber(record.aircraftGcjLng)
  const gcjLat = finiteNumber(record.aircraftGcjLat)
  const wgsLng = finiteNumber(record.aircraftLng)
  const wgsLat = finiteNumber(record.aircraftLat)
  if ((gcjLng === null || gcjLat === null) && wgsLng !== null && wgsLat !== null) {
    setFlightPositionFromWgs(aircraftSn, wgsLng, wgsLat, {
      height: record.aircraftHeight,
      updatedAt: finiteNumber(record.aircraftUpdatedAt) ?? record.lastProgressTime,
      currentWaypointIndex: record.currentWaypointIndex,
      totalWaypoints: record.totalWaypoints,
      source: 'planned-record',
    })
    return
  }
  if (gcjLng === null || gcjLat === null) return

  updateAircraftFlightPosition({
    aircraftSn,
    gcjLng,
    gcjLat,
    wgsLng: wgsLng ?? undefined,
    wgsLat: wgsLat ?? undefined,
    height: finiteNumber(record.aircraftHeight) ?? undefined,
    updatedAt: finiteNumber(record.aircraftUpdatedAt) ?? record.lastProgressTime,
    currentWaypointIndex: record.currentWaypointIndex,
    totalWaypoints: record.totalWaypoints,
    source: 'planned-record',
  })
}

function currentAircraftOsd () {
  return rootStore.state.deviceState.deviceInfo[state.aircraftSn]
}

function ensureWsSubscription () {
  if (wsSubscribed) return
  wsSubscribed = true
  EventBus.on('droneControlWs', (payload: any) => {
    if (!payload || !state.executing) return
    if (payload.biz_code !== EBizCode.FlyToPointProgress) return
    const data = payload.data as FlyToPointMessage
    if (data.sn !== state.aircraftSn) return
    if (data.result !== 0) {
      state.execState = PlanningExecState.ERROR
      state.lastError = `FlyToPoint error ${data.result}: ${data.message}`
      state.statusText = state.lastError
      message.error(state.lastError)
      abortExecution('error')
    }
  })
}

export function setTargetAircraft (gatewaySn: string, aircraftSn: string) {
  state.gatewaySn = gatewaySn
  state.aircraftSn = aircraftSn
  persistDraft()
}

export function setEditingPlannedWayline (id: string) {
  state.editingPlannedWaylineId = id
  persistDraft()
}

export function startPlanning (gatewaySn: string, aircraftSn: string) {
  if (state.executing) {
    message.warning('Stop execution before editing the waypoint list.')
    return
  }
  state.gatewaySn = gatewaySn
  state.aircraftSn = aircraftSn
  clearPlannedWaylinePreview()
  state.active = true
  state.statusText = 'Planning mode: click on the map to add waypoints.'
  persistDraft()
}

export function stopPlanning () {
  state.active = false
  if (!state.executing) {
    state.statusText = ''
  }
  persistDraft()
}

export function clearWaypoints () {
  if (state.executing) {
    message.warning('Stop execution before clearing waypoints.')
    return
  }
  state.waypoints = []
  state.selectedWaypointId = ''
  state.currentIndex = -1
  persistDraft()
}

export function clearPlannedWaylinePreview () {
  state.previewWaypoints = []
  state.previewTitle = ''
  state.previewMaxSpeed = undefined
}

export function setRouteKind (kind: 'waypoint' | 'patrol' | 'area') {
  state.routeKind = kind
  if (kind !== 'area') state.areaPolygon = []
}

/** 面状航线：地图点击落多边形顶点（与航点布点同一个点击入口，按 routeKind 分流）。 */
export function addAreaVertexGcj (gcjLng: number, gcjLat: number) {
  if (state.executing) return
  state.areaPolygon.push({ gcjLng, gcjLat })
  state.statusText = `面状测区：已落 ${state.areaPolygon.length} 个顶点（≥3 个后可生成航点）。`
  persistDraft()
}

export function removeLastAreaVertex () {
  state.areaPolygon.pop()
  persistDraft()
}

export function removeAreaVertex (index: number) {
  if (index >= 0 && index < state.areaPolygon.length) {
    state.areaPolygon.splice(index, 1)
    persistDraft()
  }
}

export function updateAreaVertex (index: number, gcjLng: number, gcjLat: number) {
  const v = state.areaPolygon[index]
  if (!v || !Number.isFinite(gcjLng) || !Number.isFinite(gcjLat)) return
  v.gcjLng = gcjLng
  v.gcjLat = gcjLat
  // 已生成过航点则跟随新形状重算，保持扫描线与测区一致
  if (state.waypoints.length > 0) generateAreaWaypoints()
  persistDraft()
}

export function clearAreaPolygon () {
  state.areaPolygon = []
  persistDraft()
}

/**
 * 由当前 areaPolygon + areaParams 生成弓字形覆盖航点，写入 state.waypoints。
 * 行间距由相机足迹 × 旁向重叠率反算。返回生成的航点数（0=失败）。
 */
export function generateAreaWaypoints (): number {
  if (state.executing) {
    message.warning('执行中不能重新生成航线。')
    return 0
  }
  if (state.areaPolygon.length < 3) {
    message.warning('请先在地图上点出至少 3 个顶点圈定测区。')
    return 0
  }
  const camera = getCameraPreset(state.areaParams.cameraKey)
  const height = normalizePositiveNumber(state.defaultHeight, DEFAULT_HEIGHT_M)
  const spacing = lineSpacingFromOverlap(camera, height, state.areaParams.sideOverlap)
  if (!(spacing > 0)) {
    message.warning('行间距为 0，请降低旁向重叠率或检查飞行高度。')
    return 0
  }
  const coords = generateAreaCoverage(state.areaPolygon, { lineSpacingM: spacing, headingDeg: state.areaParams.headingDeg })
  if (coords.length < 2) {
    message.warning('测区过小或参数不当，未生成有效航线。')
    return 0
  }
  state.waypoints = coords.map((c: { gcjLng: number; gcjLat: number }) => {
    const [wgsLng, wgsLat] = gcj02towgs84(c.gcjLng, c.gcjLat) as [number, number]
    return {
      id: uuidv4(),
      gcjLng: c.gcjLng,
      gcjLat: c.gcjLat,
      wgsLng,
      wgsLat,
      height,
    } as PlannedWaypoint
  })
  state.selectedWaypointId = ''
  state.currentIndex = -1
  state.statusText = `面状航线已生成 ${state.waypoints.length} 个航点（行间距 ${spacing.toFixed(0)}m）。`
  persistDraft()
  return state.waypoints.length
}

export function selectWaypoint (id: string) {
  state.selectedWaypointId = id
}

export function addWaypointGcj (gcjLng: number, gcjLat: number, height?: number): PlannedWaypoint | null {
  if (state.executing) {
    message.warning('Cannot add waypoints while executing.')
    return null
  }
  const [wgsLng, wgsLat] = gcj02towgs84(gcjLng, gcjLat) as [number, number]
  const prev = state.waypoints[state.waypoints.length - 1]
  if (prev && distanceMeters(prev.gcjLng, prev.gcjLat, gcjLng, gcjLat) < MIN_WAYPOINT_SPACING_M) {
    message.warning(`Waypoints must be at least ${MIN_WAYPOINT_SPACING_M} m apart (DJI firmware limit).`)
    return null
  }

  const wp: PlannedWaypoint = {
    id: uuidv4(),
    gcjLng,
    gcjLat,
    wgsLng,
    wgsLat,
    height: Number.isFinite(height) ? (height as number) : state.defaultHeight,
    turnMode: DEFAULT_WAYPOINT_TURN_MODE,
    turnDamping: 0,
  }
  state.waypoints.push(wp)
  state.selectedWaypointId = wp.id
  persistDraft()
  return wp
}

export function removeWaypoint (id: string) {
  if (state.executing) {
    message.warning('Cannot edit waypoints while executing.')
    return
  }
  const idx = state.waypoints.findIndex(w => w.id === id)
  if (idx >= 0) {
    state.waypoints.splice(idx, 1)
    if (state.selectedWaypointId === id) {
      state.selectedWaypointId = state.waypoints[Math.min(idx, state.waypoints.length - 1)]?.id || ''
    }
    persistDraft()
  }
}

/** 地图拖拽航点改位（GCJ02 输入，自动同步 WGS84） */
export function updateWaypointPositionGcj (id: string, gcjLng: number, gcjLat: number) {
  if (state.executing) {
    message.warning('Cannot edit waypoints while executing.')
    return
  }
  const wp = state.waypoints.find(w => w.id === id)
  if (!wp || !Number.isFinite(gcjLng) || !Number.isFinite(gcjLat)) return
  const [wgsLng, wgsLat] = gcj02towgs84(gcjLng, gcjLat) as [number, number]
  wp.gcjLng = gcjLng
  wp.gcjLat = gcjLat
  wp.wgsLng = wgsLng
  wp.wgsLat = wgsLat
  persistDraft()
}

/** 在 afterId 航点之后插入新航点（中点插点）。与前后相邻点均需满足最小间距。 */
export function insertWaypointAfterGcj (afterId: string, gcjLng: number, gcjLat: number): PlannedWaypoint | null {
  if (state.executing) {
    message.warning('Cannot edit waypoints while executing.')
    return null
  }
  const idx = state.waypoints.findIndex(w => w.id === afterId)
  if (idx < 0 || !Number.isFinite(gcjLng) || !Number.isFinite(gcjLat)) return null
  const prev = state.waypoints[idx]
  const next = state.waypoints[idx + 1]
  const tooClose = distanceMeters(prev.gcjLng, prev.gcjLat, gcjLng, gcjLat) < MIN_WAYPOINT_SPACING_M ||
    (next && distanceMeters(next.gcjLng, next.gcjLat, gcjLng, gcjLat) < MIN_WAYPOINT_SPACING_M)
  if (tooClose) {
    message.warning(`Waypoints must be at least ${MIN_WAYPOINT_SPACING_M} m apart (DJI firmware limit).`)
    return null
  }
  const [wgsLng, wgsLat] = gcj02towgs84(gcjLng, gcjLat) as [number, number]
  const wp: PlannedWaypoint = {
    id: uuidv4(),
    gcjLng,
    gcjLat,
    wgsLng,
    wgsLat,
    height: prev.height,
    turnMode: DEFAULT_WAYPOINT_TURN_MODE,
    turnDamping: 0,
  }
  state.waypoints.splice(idx + 1, 0, wp)
  state.selectedWaypointId = wp.id
  persistDraft()
  return wp
}

export function moveWaypoint (id: string, direction: 'up' | 'down') {
  if (state.executing) return
  const idx = state.waypoints.findIndex(w => w.id === id)
  if (idx < 0) return
  const swap = direction === 'up' ? idx - 1 : idx + 1
  if (swap < 0 || swap >= state.waypoints.length) return
  const tmp = state.waypoints[idx]
  state.waypoints[idx] = state.waypoints[swap]
  state.waypoints[swap] = tmp
  persistDraft()
}

export function updateWaypointHeight (id: string, height: number) {
  if (state.executing) return
  const wp = state.waypoints.find(w => w.id === id)
  if (wp && Number.isFinite(height)) {
    wp.height = height
    persistDraft()
  }
}

function buildPlannedWaypointBody (wp: PlannedWaypoint, idx: number): PlannedWaypointBody {
  return {
    order: idx + 1,
    gcjLng: wp.gcjLng,
    gcjLat: wp.gcjLat,
    wgsLng: wp.wgsLng,
    wgsLat: wp.wgsLat,
    height: wp.height,
    speed: wp.speed,
    gimbalPitch: wp.gimbalPitch,
    gimbalYaw: wp.gimbalYaw,
    headingMode: wp.headingMode,
    headingAngle: wp.headingAngle,
    poiLng: wp.poiLng,
    poiLat: wp.poiLat,
    poiAlt: wp.poiAlt,
    turnMode: wp.turnMode,
    turnDamping: wp.turnDamping,
    actions: wp.actions && wp.actions.length > 0 ? wp.actions : undefined,
  }
}

export function buildPlannedWaylineBody (name: string, aircraftModelKey?: string): CreatePlannedWaylineBody | UpdatePlannedWaylineBody {
  const modelKey = aircraftModelKey || state.aircraftModelKey || DEFAULT_AIRCRAFT_MODEL_KEY
  state.aircraftModelKey = modelKey
  state.defaultHeight = normalizePositiveNumber(state.defaultHeight, DEFAULT_HEIGHT_M)
  state.maxSpeed = normalizePositiveNumber(state.maxSpeed, DEFAULT_MAX_SPEED)
  state.waypoints = state.waypoints.map(wp => normalizePlannedWaypoint(wp))
  persistDraft()
  // 巡逻航线=闭合回路：末尾补一个回到首航点的点（仅写入下发体，不污染编辑中的 waypoints）
  const bodyWaypoints = state.waypoints.slice()
  if (state.routeKind === 'patrol' && bodyWaypoints.length >= 2) {
    const first = bodyWaypoints[0]
    bodyWaypoints.push({ ...first, id: uuidv4() })
  }
  return {
    name,
    aircraftModelKey: modelKey,
    gatewaySn: state.gatewaySn,
    aircraftSn: state.aircraftSn,
    defaultHeight: normalizePositiveNumber(state.defaultHeight, DEFAULT_HEIGHT_M),
    maxSpeed: normalizePositiveNumber(state.maxSpeed, DEFAULT_MAX_SPEED),
    routeKind: state.routeKind,
    areaPolygon: state.routeKind === 'area'
      ? state.areaPolygon.map(vertex => {
        const [wgsLng, wgsLat] = gcj02towgs84(vertex.gcjLng, vertex.gcjLat) as [number, number]
        return { gcjLng: vertex.gcjLng, gcjLat: vertex.gcjLat, wgsLng, wgsLat }
      })
      : undefined,
    areaCameraKey: state.routeKind === 'area' ? state.areaParams.cameraKey : undefined,
    areaFrontOverlap: state.routeKind === 'area' ? state.areaParams.frontOverlap : undefined,
    areaSideOverlap: state.routeKind === 'area' ? state.areaParams.sideOverlap : undefined,
    areaHeadingDeg: state.routeKind === 'area' ? state.areaParams.headingDeg : undefined,
    finishAction: state.finishAction,
    exitOnRcLost: state.exitOnRcLost ?? (state.routeKind === 'area' ? 'executeLostAction' : undefined),
    rcLostAction: state.rcLostAction,
    takeoffSecurityHeight: state.takeoffSecurityHeight ?? (state.routeKind === 'area' ? 60 : undefined),
    globalTransitionalSpeed: state.globalTransitionalSpeed ?? (state.routeKind === 'area' ? 15 : undefined),
    rthAltitude: state.rthAltitude,
    waypoints: bodyWaypoints.map((wp, idx) => buildPlannedWaypointBody(normalizePlannedWaypoint(wp), idx)),
  }
}

export function loadPlannedWayline (record: PlannedWaylineRecord) {
  resetExecutionInternal()
  clearPlannedWaylinePreview()
  state.active = false
  state.executing = false
  state.execState = PlanningExecState.IDLE
  state.currentIndex = -1
  state.routeKind = record.routeKind === 'patrol' || record.routeKind === 'area' ? record.routeKind : 'waypoint'
  state.areaPolygon = Array.isArray(record.areaPolygon)
    ? record.areaPolygon.map(vertex => {
      if (Number.isFinite(Number(vertex.gcjLng)) && Number.isFinite(Number(vertex.gcjLat))) {
        return { gcjLng: Number(vertex.gcjLng), gcjLat: Number(vertex.gcjLat) }
      }
      const [gcjLng, gcjLat] = wgs84togcj02(Number(vertex.wgsLng), Number(vertex.wgsLat)) as [number, number]
      return { gcjLng, gcjLat }
    })
    : []
  state.areaParams.cameraKey = record.areaCameraKey || DEFAULT_AREA_CAMERA_KEY
  state.areaParams.frontOverlap = Number.isFinite(Number(record.areaFrontOverlap)) ? Number(record.areaFrontOverlap) : 80
  state.areaParams.sideOverlap = Number.isFinite(Number(record.areaSideOverlap)) ? Number(record.areaSideOverlap) : 70
  state.areaParams.headingDeg = Number.isFinite(Number(record.areaHeadingDeg)) ? Number(record.areaHeadingDeg) : 0
  state.editingPlannedWaylineId = record.plannedWaylineId
  state.aircraftModelKey = record.aircraftModelKey
  state.gatewaySn = record.gatewaySn
  state.aircraftSn = record.aircraftSn
  state.defaultHeight = Number.isFinite(Number(record.defaultHeight)) ? Number(record.defaultHeight) : DEFAULT_HEIGHT_M
  state.maxSpeed = Number.isFinite(Number(record.maxSpeed)) ? Number(record.maxSpeed) : DEFAULT_MAX_SPEED
  state.finishAction = record.finishAction || undefined
  state.exitOnRcLost = record.exitOnRcLost || undefined
  state.rcLostAction = record.rcLostAction || undefined
  state.takeoffSecurityHeight = Number.isFinite(Number(record.takeoffSecurityHeight)) ? Number(record.takeoffSecurityHeight) : undefined
  state.globalTransitionalSpeed = Number.isFinite(Number(record.globalTransitionalSpeed)) ? Number(record.globalTransitionalSpeed) : undefined
  state.rthAltitude = Number.isFinite(Number(record.rthAltitude)) ? Number(record.rthAltitude) : undefined
  state.waypoints = record.waypoints.map(wp => ({
    id: uuidv4(),
    gcjLng: Number(wp.gcjLng),
    gcjLat: Number(wp.gcjLat),
    wgsLng: Number(wp.wgsLng),
    wgsLat: Number(wp.wgsLat),
    height: Number(wp.height),
    speed: Number.isFinite(Number(wp.speed)) ? Number(wp.speed) : undefined,
    gimbalPitch: Number.isFinite(Number(wp.gimbalPitch)) ? Number(wp.gimbalPitch) : undefined,
    gimbalYaw: Number.isFinite(Number(wp.gimbalYaw)) ? Number(wp.gimbalYaw) : undefined,
    headingMode: (wp as any).headingMode || undefined,
    headingAngle: Number.isFinite(Number(wp.headingAngle)) ? Number(wp.headingAngle) : undefined,
    poiLng: Number.isFinite(Number(wp.poiLng)) ? Number(wp.poiLng) : undefined,
    poiLat: Number.isFinite(Number(wp.poiLat)) ? Number(wp.poiLat) : undefined,
    poiAlt: Number.isFinite(Number(wp.poiAlt)) ? Number(wp.poiAlt) : undefined,
    turnMode: (wp as any).turnMode || undefined,
    turnDamping: Number.isFinite(Number(wp.turnDamping)) ? Number(wp.turnDamping) : undefined,
    actions: Array.isArray(wp.actions) ? wp.actions.map(a => ({ ...a, params: a.params ? { ...a.params } : undefined })) : undefined,
  }))
  state.statusText = `Loaded planned wayline "${record.name}" (${record.status}).`
  state.lastError = ''
  persistDraft()
}

export function previewPlannedWayline (record: PlannedWaylineRecord) {
  if (!record || !Array.isArray(record.waypoints)) return
  if (state.executing || state.active) {
    return
  }
  state.previewWaypoints = record.waypoints.map(wp => ({
    id: uuidv4(),
    gcjLng: Number(wp.gcjLng),
    gcjLat: Number(wp.gcjLat),
    wgsLng: Number(wp.wgsLng),
    wgsLat: Number(wp.wgsLat),
    height: Number(wp.height),
    speed: Number.isFinite(Number(wp.speed)) ? Number(wp.speed) : undefined,
    actions: Array.isArray(wp.actions) ? wp.actions.map(a => ({ ...a, params: a.params ? { ...a.params } : undefined })) : undefined,
  }))
  state.previewTitle = record.name || ''
  state.previewMaxSpeed = Number.isFinite(Number(record.maxSpeed)) ? Number(record.maxSpeed) : DEFAULT_MAX_SPEED
  state.statusText = record.name ? `预览规划航线“${record.name}”。` : '预览规划航线。'
}

export function resetPlanningDraft () {
  resetExecutionInternal()
  state.active = false
  state.executing = false
  state.execState = PlanningExecState.IDLE
  state.currentIndex = -1
  state.editingPlannedWaylineId = ''
  state.aircraftModelKey = ''
  state.gatewaySn = ''
  state.aircraftSn = ''
  state.waypoints = []
  state.routeKind = 'waypoint'
  state.areaPolygon = []
  state.areaParams.cameraKey = DEFAULT_AREA_CAMERA_KEY
  state.areaParams.frontOverlap = 80
  state.areaParams.sideOverlap = 70
  state.areaParams.headingDeg = 0
  clearPlannedWaylinePreview()
  state.defaultHeight = DEFAULT_HEIGHT_M
  state.maxSpeed = DEFAULT_MAX_SPEED
  state.finishAction = undefined
  state.exitOnRcLost = undefined
  state.rcLostAction = undefined
  state.takeoffSecurityHeight = undefined
  state.globalTransitionalSpeed = undefined
  state.rthAltitude = undefined
  state.statusText = ''
  state.lastError = ''
  state.flightPosition = null
  state.flightTrackSessionId = ''
  state.flightTrackPlannedWaylineId = ''
  state.flightTrackRecording = false
  state.flightTrackRevision += 1
  persistDraft()
}

// ---- L1 编辑 helpers (供 wayline.vue 调用) ----

export function updateWaypointField<K extends keyof PlannedWaypoint> (id: string, key: K, value: PlannedWaypoint[K]) {
  if (state.executing) return
  const wp = state.waypoints.find(w => w.id === id)
  if (!wp) return
  (wp as any)[key] = value
  persistDraft()
}

export function setMissionConfig (cfg: Partial<{
  finishAction: string
  exitOnRcLost: string
  rcLostAction: string
  takeoffSecurityHeight: number
  globalTransitionalSpeed: number
  rthAltitude: number
}>) {
  if (cfg.finishAction !== undefined) state.finishAction = cfg.finishAction || undefined
  if (cfg.exitOnRcLost !== undefined) state.exitOnRcLost = cfg.exitOnRcLost || undefined
  if (cfg.rcLostAction !== undefined) state.rcLostAction = cfg.rcLostAction || undefined
  if (cfg.takeoffSecurityHeight !== undefined) state.takeoffSecurityHeight = cfg.takeoffSecurityHeight
  if (cfg.globalTransitionalSpeed !== undefined) state.globalTransitionalSpeed = cfg.globalTransitionalSpeed
  if (cfg.rthAltitude !== undefined) state.rthAltitude = cfg.rthAltitude
  persistDraft()
}

export function addWaypointAction (id: string, actuatorFunc: WaypointActuatorFunc) {
  if (state.executing) return
  const wp = state.waypoints.find(w => w.id === id)
  if (!wp) return
  if (!wp.actions) wp.actions = []
  const defaults = defaultParamsFor(actuatorFunc)
  wp.actions.push({ actuatorFunc, actionTrigger: 'reachPoint', params: defaults })
  persistDraft()
}

export function removeWaypointAction (id: string, actionIdx: number) {
  if (state.executing) return
  const wp = state.waypoints.find(w => w.id === id)
  if (!wp || !wp.actions || actionIdx < 0 || actionIdx >= wp.actions.length) return
  wp.actions.splice(actionIdx, 1)
  if (wp.actions.length === 0) wp.actions = undefined
  persistDraft()
}

export function updateWaypointActionParam (id: string, actionIdx: number, paramKey: string, value: any) {
  if (state.executing) return
  const wp = state.waypoints.find(w => w.id === id)
  if (!wp || !wp.actions || actionIdx < 0 || actionIdx >= wp.actions.length) return
  const action = wp.actions[actionIdx]
  if (!action.params) action.params = {}
  action.params[paramKey] = value
  persistDraft()
}

function defaultParamsFor (actuatorFunc: WaypointActuatorFunc): Record<string, string | number | boolean> {
  switch (actuatorFunc) {
    case 'takePhoto':
      return { fileSuffix: '', payloadPositionIndex: 0 }
    case 'startRecord':
      return { fileSuffix: '', payloadPositionIndex: 0 }
    case 'stopRecord':
      return { payloadPositionIndex: 0 }
    case 'gimbalRotate':
      return {
        gimbalRotateMode: 'absoluteAngle',
        gimbalPitchRotateEnable: 1,
        gimbalPitchRotateAngle: 0,
        gimbalYawRotateEnable: 0,
        gimbalYawRotateAngle: 0,
        gimbalRotateTimeEnable: 0,
        gimbalRotateTime: 0,
        payloadPositionIndex: 0,
      }
    case 'hover':
      return { hoverTime: 3 }
    case 'focus':
      return { payloadPositionIndex: 0, isPointFocus: 1, focusX: 0.5, focusY: 0.5 }
    case 'rotateYaw':
      return { aircraftHeading: 0, aircraftPathMode: 'clockwise' }
    default:
      return {}
  }
}

function abortExecution (reason: 'error' | 'stopped' | 'done') {
  activeExecutionId += 1
  resetExecutionInternal()
  finishFlightTrackSession()
  if (reason === 'done') {
    state.execState = PlanningExecState.STOPPED
    state.statusText = 'Execution finished.'
  } else if (reason === 'stopped') {
    state.execState = PlanningExecState.STOPPED
    state.statusText = 'Execution stopped.'
  } else {
    state.execState = PlanningExecState.ERROR
    state.statusText = state.lastError || 'Execution aborted.'
  }
  persistDraft()
}

async function dispatchWaypoint (index: number, executionId: number) {
  if (executionId !== activeExecutionId || !state.executing) {
    return
  }

  const wp = state.waypoints[index]
  if (!wp) {
    abortExecution('done')
    return
  }

  state.currentIndex = index
  state.execState = PlanningExecState.DISPATCHING
  state.statusText = `Dispatching waypoint ${index + 1}/${state.waypoints.length}...`

  try {
    const res = await postFlyToPoint(state.gatewaySn, {
      max_speed: state.maxSpeed,
      points: [{
        latitude: wp.wgsLat,
        longitude: wp.wgsLng,
        height: wp.height,
      }],
    })
    if (executionId !== activeExecutionId || !state.executing) {
      return
    }
    if (res.code !== 0) {
      state.lastError = `fly_to_point backend code ${res.code}`
      state.statusText = state.lastError
      message.error(state.lastError)
      abortExecution('error')
      return
    }
  } catch (e: any) {
    if (executionId !== activeExecutionId || !state.executing) {
      return
    }
    state.lastError = e?.message || 'fly_to_point request failed.'
    state.statusText = state.lastError
    message.error(state.lastError)
    abortExecution('error')
    return
  }

  state.execState = PlanningExecState.TRAVELLING
  state.statusText = `Flying to waypoint ${index + 1}/${state.waypoints.length}...`
  clearWaypointTimeout()
  waypointTimeoutId = window.setTimeout(() => {
    if (executionId !== activeExecutionId || !state.executing || state.execState !== PlanningExecState.TRAVELLING) {
      return
    }
    state.lastError = `Timed out waiting to reach waypoint ${index + 1}/${state.waypoints.length}.`
    state.statusText = state.lastError
    message.error(state.lastError)
    abortExecution('error')
  }, WAYPOINT_EXECUTION_TIMEOUT_MS)

  startOsdReachWatcher(wp, executionId)
}

function startOsdReachWatcher (wp: PlannedWaypoint, executionId: number) {
  if (osdWatcherStop) {
    osdWatcherStop()
    osdWatcherStop = null
  }
  reachStableSince = 0

  const stop = watch(
    () => rootStore.state.deviceState.deviceInfo[state.aircraftSn],
    (osd) => {
      if (executionId !== activeExecutionId) return
      if (!state.executing || state.execState !== PlanningExecState.TRAVELLING) return
      if (!osd) return

      const lat = Number((osd as any).latitude)
      const lng = Number((osd as any).longitude)
      const heightRaw = (osd as any).height
      const height = typeof heightRaw === 'number' ? heightRaw : Number(heightRaw)
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(height)) return

      const horiz = distanceMeters(wp.wgsLng, wp.wgsLat, lng, lat)
      const vert = Math.abs(height - wp.height)
      const reached = horiz <= DEFAULT_REACH_RADIUS_M && vert <= 3
      if (!reached) {
        reachStableSince = 0
        return
      }

      if (reachStableSince === 0) {
        reachStableSince = Date.now()
        return
      }

      if (Date.now() - reachStableSince < DEFAULT_REACH_STABLE_MS) {
        return
      }

      state.execState = PlanningExecState.ARRIVED
      state.statusText = `Reached waypoint ${state.currentIndex + 1}/${state.waypoints.length}.`
      clearWaypointTimeout()
      if (osdWatcherStop) {
        osdWatcherStop()
        osdWatcherStop = null
      }
      reachStableSince = 0

      const nextIdx = state.currentIndex + 1
      if (nextIdx >= state.waypoints.length) {
        abortExecution('done')
      } else {
        dispatchWaypoint(nextIdx, executionId)
      }
    },
    { deep: true }
  )

  osdWatcherStop = stop
}

export async function startExecution () {
  if (state.executing) {
    message.warning('Execution already in progress.')
    return
  }
  if (!state.gatewaySn || !state.aircraftSn) {
    message.warning('Select a target aircraft first.')
    return
  }
  if (state.waypoints.length === 0) {
    message.warning('Add at least one waypoint.')
    return
  }

  const osd = currentAircraftOsd()
  if (!osd) {
    message.warning('Aircraft OSD is not available. Confirm the aircraft is online and DRC is connected.')
    return
  }

  const height = Number((osd as any).height)
  if (!Number.isFinite(height) || height < 15) {
    message.warning('Aircraft must be airborne (height >= 15 m). Use tsa "Official Takeoff" first.')
    return
  }

  ensureWsSubscription()
  activeExecutionId += 1
  const executionId = activeExecutionId
  beginFlightTrackSession(`manual:${executionId}`, '', true)
  state.executing = true
  state.active = false
  state.lastError = ''
  state.execState = PlanningExecState.IDLE
  persistDraft()
  await dispatchWaypoint(0, executionId)
}

export async function stopExecution () {
  if (!state.executing) {
    if (state.gatewaySn) {
      try {
        await deleteFlyToPoint(state.gatewaySn)
      } catch (e) {
        // Ignore stale cleanup failures when not actively executing.
      }
    }
    return
  }

  activeExecutionId += 1
  try {
    if (state.gatewaySn) {
      await deleteFlyToPoint(state.gatewaySn)
    }
  } catch (e: any) {
    message.warning(`Stop fly_to_point backend rejected: ${e?.message || e}`)
  }
  abortExecution('stopped')
}

restoreDraft()

export function usePlanningState () {
  return {
    state,
    isPlanningActive: computed(() => state.active),
    isExecuting: computed(() => state.executing),
    waypoints: computed(() => state.waypoints),
    currentIndex: computed(() => state.currentIndex),
    statusText: computed(() => state.statusText),
  }
}

export function getPlanningStateRaw () {
  return state
}
