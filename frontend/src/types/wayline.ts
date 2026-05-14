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

export interface PlannedWaypoint {
  order: number
  gcjLng: number
  gcjLat: number
  wgsLng: number
  wgsLat: number
  height: number
}

export enum PlannedWaylineStatus {
  DRAFT = 'draft',
  FILE_GENERATED = 'file_generated',
  PUBLISHING = 'publishing',
  PREPARED = 'prepared',
  EXECUTING = 'executing',
  COMPLETED = 'completed',
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
  preparedTime?: number
  executedTime?: number
  creator: string
  publisher?: string
  publishTime?: number
  createTime: number
  updateTime: number
}

export interface PreparePlannedWaylineTaskBody {
  dockSn: string
  droneSn?: string
  executeTime?: number
  taskType?: string
}

export interface CreatePlannedWaylineBody {
  name: string
  aircraftModelKey: string
  gatewaySn: string
  aircraftSn: string
  defaultHeight: number
  maxSpeed: number
  waypoints: PlannedWaypoint[]
}

export interface UpdatePlannedWaylineBody {
  name: string
  aircraftModelKey: string
  gatewaySn: string
  aircraftSn: string
  defaultHeight: number
  maxSpeed: number
  waypoints: PlannedWaypoint[]
}

export interface PublishPlannedWaylineResult {
  plannedWaylineId: string
  publishedWaylineId: string
  publishedWaylineName: string
  publisher?: string
  publishTime?: number
}
