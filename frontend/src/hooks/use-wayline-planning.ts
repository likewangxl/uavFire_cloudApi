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

    const draftWaypoints = Array.isArray(parsed.waypoints) ? parsed.waypoints : []
    const restoredWaypoints = draftWaypoints.filter((wp): wp is PlannedWaypoint => {
      return typeof wp?.id === 'string' &&
        Number.isFinite(Number(wp.gcjLng)) &&
        Number.isFinite(Number(wp.gcjLat)) &&
        Number.isFinite(Number(wp.wgsLng)) &&
        Number.isFinite(Number(wp.wgsLat)) &&
        Number.isFinite(Number(wp.height))
    }).map(wp => ({
      id: wp.id,
      gcjLng: Number(wp.gcjLng),
      gcjLat: Number(wp.gcjLat),
      wgsLng: Number(wp.wgsLng),
      wgsLat: Number(wp.wgsLat),
      height: Number(wp.height),
    }))

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
  state.currentIndex = -1
  persistDraft()
}

export function clearPlannedWaylinePreview () {
  state.previewWaypoints = []
  state.previewTitle = ''
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
  }
  state.waypoints.push(wp)
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
    persistDraft()
  }
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
  return {
    name,
    aircraftModelKey: modelKey,
    gatewaySn: state.gatewaySn,
    aircraftSn: state.aircraftSn,
    defaultHeight: normalizePositiveNumber(state.defaultHeight, DEFAULT_HEIGHT_M),
    maxSpeed: normalizePositiveNumber(state.maxSpeed, DEFAULT_MAX_SPEED),
    finishAction: state.finishAction,
    exitOnRcLost: state.exitOnRcLost,
    rcLostAction: state.rcLostAction,
    takeoffSecurityHeight: state.takeoffSecurityHeight,
    globalTransitionalSpeed: state.globalTransitionalSpeed,
    rthAltitude: state.rthAltitude,
    waypoints: state.waypoints.map((wp, idx) => buildPlannedWaypointBody(normalizePlannedWaypoint(wp), idx)),
  }
}

export function loadPlannedWayline (record: PlannedWaylineRecord) {
  resetExecutionInternal()
  clearPlannedWaylinePreview()
  state.active = false
  state.executing = false
  state.execState = PlanningExecState.IDLE
  state.currentIndex = -1
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
  }))
  state.previewTitle = record.name || ''
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
