// 航线类型
export enum WaylineType {
  NormalWaypointWayline = 0, // 普通航点航线
  AccurateReshootingWayline = 1 // 精准复拍航线
}

export interface WaylineFile {
  id: string,
  name: string,
  drone_model_key: any,
  payload_model_keys: string[],
  template_types: WaylineType[],
  update_time: number,
  user_name: string,
}

// L1: 航点动作 (取自 docs/WAYLINE_L1_L2_CONTRACT.md 2.2)
export type WaypointActuatorFunc =
  | 'takePhoto'
  | 'startRecord'
  | 'stopRecord'
  | 'gimbalRotate'
  | 'hover'
  | 'focus'
  | 'rotateYaw'

export type WaypointActionTrigger =
  | 'reachPoint'
  | 'betweenAdjacentPoints'
  | 'multipleTiming'

export interface WaypointAction {
  actionId?: number
  actionTrigger?: WaypointActionTrigger
  actionTriggerParam?: number
  actuatorFunc: WaypointActuatorFunc
  params?: Record<string, string | number | boolean>
}

export type WaypointHeadingMode =
  | 'followWayline'
  | 'smoothTransition'
  | 'fixed'
  | 'towardPOI'

export type WaypointTurnMode =
  | 'coordinateTurn'
  | 'toPointAndStopWithDiscontinuityCurvature'
  | 'toPointAndStopWithContinuityCurvature'
  | 'toPointAndPassWithContinuityCurvature'
  | 'toPointAndPassWithContinuityCurvatureAndCustomDamping'

export interface PlannedWaypoint {
  order: number
  gcjLng: number
  gcjLat: number
  wgsLng: number
  wgsLat: number
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

export enum PlannedWaylineStatus {
  DRAFT = 'draft',
  FILE_GENERATED = 'file_generated',
  PUBLISHING = 'publishing',
  READY = 'ready',
  PREPARED = 'prepared',
  EXECUTING = 'executing',
  PAUSED = 'paused',
  BROKEN = 'broken',
  STOPPED = 'stopped',
  COMPLETED = 'completed',
  FINISHED = 'finished',
  FAILED = 'failed',
  CANCELED = 'canceled',
}

export interface PlannedWaylineRecord {
  plannedWaylineId: string
  workspaceId: string
  name: string
  aircraftModelKey: string
  gatewaySn: string
  aircraftSn: string
  defaultHeight: number
  maxSpeed: number
  // L1 mission 配置
  finishAction?: string
  exitOnRcLost?: string
  rcLostAction?: string
  takeoffSecurityHeight?: number
  globalTransitionalSpeed?: number
  rthAltitude?: number
  waypoints: PlannedWaypoint[]
  status: PlannedWaylineStatus | string
  publishedWaylineId?: string
  kmzUrl?: string
  kmzMd5?: string
  kmzObjectKey?: string
  fileGeneratedTime?: number
  flightId?: string
  dockSn?: string
  droneSn?: string
  taskStatus?: PlannedWaylineStatus | string
  taskStatusReason?: string
  taskProgress?: number
  // L2 实时任务进度
  waylineMissionState?: number
  currentWaypointIndex?: number
  totalWaypoints?: number
  mediaCount?: number
  breakPointJson?: string
  lastProgressTime?: number
  preparedTime?: number
  executedTime?: number
  creator: string
  publisher?: string
  publishTime?: number
  createTime: number
  updateTime: number
}

export interface PreparePlannedWaylineTaskBody {
  dockSn?: string
  droneSn?: string
  executeTime?: number
  beginTime?: number
  endTime?: number
  taskType?: string
  minBattery?: number
  simulate?: boolean
  simulateLat?: number
  simulateLng?: number
}

interface PlannedWaylineBodyShared {
  name: string
  aircraftModelKey: string
  gatewaySn: string
  aircraftSn: string
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

export type CreatePlannedWaylineBody = PlannedWaylineBodyShared
export type UpdatePlannedWaylineBody = PlannedWaylineBodyShared

export interface PublishPlannedWaylineResult {
  plannedWaylineId: string
  publishedWaylineId: string
  publishedWaylineName: string
  publisher?: string
  publishTime?: number
}
