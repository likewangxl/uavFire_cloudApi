import { message } from 'ant-design-vue'
import request, { IPage, IWorkspaceResponse, IListWorkspaceResponse } from '/@/api/http/request'
import { TaskType, TaskStatus, OutOfControlAction } from '/@/types/task'
import { WaylineType } from '/@/types/wayline'
import type {
  CreatePlannedWaylineBody,
  PlannedWaypoint,
  PlannedWaylineRecord,
  PreparePlannedWaylineTaskBody,
  PublishPlannedWaylineResult,
  UpdatePlannedWaylineBody,
} from '/@/types/wayline'

const HTTP_PREFIX = '/wayline/api/v1'
const DEFAULT_PLANNED_WAYLINE_HEIGHT = 30
const DEFAULT_PLANNED_WAYLINE_SPEED = 5

function finiteNumber (value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : null
}

function positiveNumber (value: unknown, fallback: number): number {
  const numberValue = finiteNumber(value)
  return numberValue !== null && numberValue > 0 ? numberValue : fallback
}

function assertPlannedWaypoint (waypoint: PlannedWaypoint, index: number): PlannedWaypoint {
  const gcjLng = finiteNumber(waypoint?.gcjLng)
  const gcjLat = finiteNumber(waypoint?.gcjLat)
  const wgsLng = finiteNumber(waypoint?.wgsLng)
  const wgsLat = finiteNumber(waypoint?.wgsLat)
  if (gcjLng === null || gcjLat === null || wgsLng === null || wgsLat === null) {
    message.error(`航点坐标缺失：第 ${index + 1} 个航点，请清空后重新布点。`)
    throw new Error(`planned waypoint[${index}] coordinates required`)
  }
  return {
    order: index + 1,
    gcjLng,
    gcjLat,
    wgsLng,
    wgsLat,
    height: positiveNumber(waypoint.height, DEFAULT_PLANNED_WAYLINE_HEIGHT),
    // L1 航点级覆写与动作必须透传，否则保存即丢失（后端 PlannedWaypointDTO 全字段支持）
    speed: waypoint.speed ?? undefined,
    gimbalPitch: waypoint.gimbalPitch ?? undefined,
    gimbalYaw: waypoint.gimbalYaw ?? undefined,
    headingMode: waypoint.headingMode ?? undefined,
    headingAngle: waypoint.headingAngle ?? undefined,
    poiLng: waypoint.poiLng ?? undefined,
    poiLat: waypoint.poiLat ?? undefined,
    poiAlt: waypoint.poiAlt ?? undefined,
    turnMode: waypoint.turnMode ?? undefined,
    turnDamping: waypoint.turnDamping ?? undefined,
    actions: Array.isArray(waypoint.actions) && waypoint.actions.length > 0 ? waypoint.actions : undefined,
  }
}

function validatePlannedWaylineBody<T extends CreatePlannedWaylineBody | UpdatePlannedWaylineBody> (body: T): T {
  if (!body?.name || !Array.isArray(body.waypoints) || body.waypoints.length === 0) {
    message.error('规划航线参数不完整，请确认航线名称和航点。')
    throw new Error('planned wayline payload incomplete')
  }
  const aircraftModelKey = body.aircraftModelKey?.trim()
  if (!aircraftModelKey) {
    message.error('无法读取当前飞行器机型，请重新连接设备后再保存航线。')
    throw new Error('planned wayline aircraft model is required')
  }
  if (['M300', 'M350'].includes(aircraftModelKey)) {
    const supportedPayloads = ['H20', 'H20T', 'H30', 'H30T']
    if (!body.payloadModelKey || !supportedPayloads.includes(body.payloadModelKey) ||
      ![0, 1, 2].includes(Number(body.payloadPositionIndex))) {
      message.error('M300/M350 航线必须选择 H20/H20T/H30/H30T 负载和云台安装位。')
      throw new Error('M300/M350 payload model and position are required')
    }
  }
  if (body.routeKind === 'area' && (!Array.isArray(body.areaPolygon) || body.areaPolygon.length < 3)) {
    message.error('面状航线缺少测区边界，请重新圈定测区后保存。')
    throw new Error('planned area polygon requires at least 3 vertices')
  }
  return {
    ...body,
    aircraftModelKey,
    defaultHeight: positiveNumber(body.defaultHeight, DEFAULT_PLANNED_WAYLINE_HEIGHT),
    maxSpeed: positiveNumber(body.maxSpeed, DEFAULT_PLANNED_WAYLINE_SPEED),
    areaPolygon: Array.isArray(body.areaPolygon)
      ? body.areaPolygon.map((vertex, index) => {
        const gcjLng = finiteNumber(vertex?.gcjLng)
        const gcjLat = finiteNumber(vertex?.gcjLat)
        const wgsLng = finiteNumber(vertex?.wgsLng)
        const wgsLat = finiteNumber(vertex?.wgsLat)
        if (gcjLng === null || gcjLat === null || wgsLng === null || wgsLat === null) {
          throw new Error(`planned area vertex[${index}] coordinates required`)
        }
        return { gcjLng, gcjLat, wgsLng, wgsLat }
      })
      : undefined,
    waypoints: body.waypoints.map((waypoint, index) => assertPlannedWaypoint(waypoint, index)),
  }
}

function normalizePlannedWaypointResponse (record: any): PlannedWaypoint {
  return {
    order: record?.order,
    gcjLng: record?.gcjLng ?? record?.gcj_lng,
    gcjLat: record?.gcjLat ?? record?.gcj_lat,
    wgsLng: record?.wgsLng ?? record?.wgs_lng,
    wgsLat: record?.wgsLat ?? record?.wgs_lat,
    height: record?.height,
    speed: record?.speed ?? undefined,
    gimbalPitch: record?.gimbalPitch ?? record?.gimbal_pitch ?? undefined,
    gimbalYaw: record?.gimbalYaw ?? record?.gimbal_yaw ?? undefined,
    headingMode: record?.headingMode ?? record?.heading_mode ?? undefined,
    headingAngle: record?.headingAngle ?? record?.heading_angle ?? undefined,
    poiLng: record?.poiLng ?? record?.poi_lng ?? undefined,
    poiLat: record?.poiLat ?? record?.poi_lat ?? undefined,
    poiAlt: record?.poiAlt ?? record?.poi_alt ?? undefined,
    turnMode: record?.turnMode ?? record?.turn_mode ?? undefined,
    turnDamping: record?.turnDamping ?? record?.turn_damping ?? undefined,
    actions: Array.isArray(record?.actions) && record.actions.length > 0
      ? record.actions.map(normalizeWaypointActionResponse)
      : undefined,
  }
}

function normalizeWaypointActionResponse (action: any): any {
  return {
    actionId: action?.actionId ?? action?.action_id ?? undefined,
    actionTrigger: action?.actionTrigger ?? action?.action_trigger ?? undefined,
    actionTriggerParam: action?.actionTriggerParam ?? action?.action_trigger_param ?? undefined,
    actuatorFunc: action?.actuatorFunc ?? action?.actuator_func ?? undefined,
    params: action?.params ?? undefined,
  }
}

function normalizePlannedWaylineResponse (record: any): PlannedWaylineRecord {
  return {
    plannedWaylineId: record?.plannedWaylineId ?? record?.planned_wayline_id,
    workspaceId: record?.workspaceId ?? record?.workspace_id,
    name: record?.name,
    aircraftModelKey: record?.aircraftModelKey ?? record?.aircraft_model_key,
    payloadModelKey: record?.payloadModelKey ?? record?.payload_model_key,
    payloadPositionIndex: record?.payloadPositionIndex ?? record?.payload_position_index,
    gatewaySn: record?.gatewaySn ?? record?.gateway_sn,
    aircraftSn: record?.aircraftSn ?? record?.aircraft_sn,
    defaultHeight: record?.defaultHeight ?? record?.default_height,
    maxSpeed: record?.maxSpeed ?? record?.max_speed,
    routeKind: record?.routeKind ?? record?.route_kind ?? 'waypoint',
    areaPolygon: Array.isArray(record?.areaPolygon ?? record?.area_polygon)
      ? (record.areaPolygon ?? record.area_polygon).map((vertex: any) => ({
          gcjLng: vertex?.gcjLng ?? vertex?.gcj_lng,
          gcjLat: vertex?.gcjLat ?? vertex?.gcj_lat,
          wgsLng: vertex?.wgsLng ?? vertex?.wgs_lng,
          wgsLat: vertex?.wgsLat ?? vertex?.wgs_lat,
        }))
      : [],
    areaCameraKey: record?.areaCameraKey ?? record?.area_camera_key,
    areaFrontOverlap: record?.areaFrontOverlap ?? record?.area_front_overlap,
    areaSideOverlap: record?.areaSideOverlap ?? record?.area_side_overlap,
    areaHeadingDeg: record?.areaHeadingDeg ?? record?.area_heading_deg,
    finishAction: record?.finishAction ?? record?.finish_action,
    exitOnRcLost: record?.exitOnRcLost ?? record?.exit_on_rc_lost,
    rcLostAction: record?.rcLostAction ?? record?.rc_lost_action,
    takeoffSecurityHeight: record?.takeoffSecurityHeight ?? record?.takeoff_security_height,
    globalTransitionalSpeed: record?.globalTransitionalSpeed ?? record?.global_transitional_speed,
    rthAltitude: record?.rthAltitude ?? record?.rth_altitude,
    waypoints: Array.isArray(record?.waypoints) ? record.waypoints.map(normalizePlannedWaypointResponse) : [],
    status: record?.status,
    publishedWaylineId: record?.publishedWaylineId ?? record?.published_wayline_id,
    kmzUrl: record?.kmzUrl ?? record?.kmz_url,
    kmzMd5: record?.kmzMd5 ?? record?.kmz_md5,
    kmzObjectKey: record?.kmzObjectKey ?? record?.kmz_object_key,
    fileGeneratedTime: record?.fileGeneratedTime ?? record?.file_generated_time,
    flightId: record?.flightId ?? record?.flight_id,
    dockSn: record?.dockSn ?? record?.dock_sn,
    droneSn: record?.droneSn ?? record?.drone_sn,
    taskStatus: record?.taskStatus ?? record?.task_status,
    taskStatusReason: record?.taskStatusReason ?? record?.task_status_reason,
    taskProgress: record?.taskProgress ?? record?.task_progress,
    waylineMissionState: record?.waylineMissionState ?? record?.wayline_mission_state,
    currentWaypointIndex: record?.currentWaypointIndex ?? record?.current_waypoint_index,
    totalWaypoints: record?.totalWaypoints ?? record?.total_waypoints,
    mediaCount: record?.mediaCount ?? record?.media_count,
    breakPointJson: record?.breakPointJson ?? record?.break_point_json,
    lastProgressTime: record?.lastProgressTime ?? record?.last_progress_time,
    aircraftLng: record?.aircraftLng ?? record?.aircraft_lng,
    aircraftLat: record?.aircraftLat ?? record?.aircraft_lat,
    aircraftGcjLng: record?.aircraftGcjLng ?? record?.aircraft_gcj_lng,
    aircraftGcjLat: record?.aircraftGcjLat ?? record?.aircraft_gcj_lat,
    aircraftHeight: record?.aircraftHeight ?? record?.aircraft_height,
    aircraftUpdatedAt: record?.aircraftUpdatedAt ?? record?.aircraft_updated_at,
    preparedTime: record?.preparedTime ?? record?.prepared_time,
    executedTime: record?.executedTime ?? record?.executed_time,
    creator: record?.creator,
    publisher: record?.publisher,
    publishTime: record?.publishTime ?? record?.publish_time,
    createTime: record?.createTime ?? record?.create_time,
    updateTime: record?.updateTime ?? record?.update_time,
  }
}

function normalizePlannedWaylineResult (result: IWorkspaceResponse<any>): IWorkspaceResponse<PlannedWaylineRecord> {
  return {
    ...result,
    data: result.data ? normalizePlannedWaylineResponse(result.data) : result.data,
  }
}

// Get Wayline Files
export const getWaylineFiles = async function (wid: string, body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/${wid}/waylines?order_by=${body.order_by}&page=${body.page}&page_size=${body.page_size}`
  const result = await request.get(url)
  return result.data
}

// Download Wayline File
export const downloadWaylineFile = async function (workspaceId: string, waylineId: string): Promise<any> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/waylines/${waylineId}/url`
  const result = await request.get(url, { responseType: 'blob' })
  if (result.data.type === 'application/json') {
    const reader = new FileReader()
    reader.onload = function (e) {
      const text = reader.result as string
      const result = JSON.parse(text)
      message.error(result.message)
    }
    reader.readAsText(result.data, 'utf-8')
  } else {
    return result.data
  }
}

// Delete Wayline File
export const deleteWaylineFile = async function (workspaceId: string, waylineId: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/waylines/${waylineId}`
  const result = await request.delete(url)
  return result.data
}

export const getPlannedWaylines = async function (workspaceId: string, page: IPage): Promise<IListWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines?page=${page.page}&page_size=${page.page_size}`
  const result = await request.get(url)
  if (result.data?.data?.list) {
    result.data.data.list = result.data.data.list.map(normalizePlannedWaylineResponse)
  }
  return result.data
}

export const getPlannedWayline = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}`
  const result = await request.get(url)
  return normalizePlannedWaylineResult(result.data)
}

export const createPlannedWayline = async function (workspaceId: string, body: CreatePlannedWaylineBody): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines`
  const validatedBody = validatePlannedWaylineBody(body)
  const result = await request.post(url, validatedBody)
  return normalizePlannedWaylineResult(result.data)
}

export const updatePlannedWayline = async function (
  workspaceId: string,
  plannedWaylineId: string,
  body: UpdatePlannedWaylineBody
): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}`
  const validatedBody = validatePlannedWaylineBody(body)
  const result = await request.put(url, validatedBody)
  return normalizePlannedWaylineResult(result.data)
}

export const deletePlannedWayline = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<{}>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}`
  const result = await request.delete(url)
  return result.data
}

export const importPlannedWaylineKmzFile = async function (workspaceId: string, file: FormData): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/import-kmz`
  const result = await request.post(url, file, {
    headers: {
      'Content-Type': 'multipart/form-data',
    }
  })
  return normalizePlannedWaylineResult(result.data)
}

export const publishPlannedWayline = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PublishPlannedWaylineResult>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/publish`
  const result = await request.post(url)
  return result.data
}

export const generatePlannedWaylineFile = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/generate-file`
  const result = await request.post(url)
  return normalizePlannedWaylineResult(result.data)
}

export const preparePlannedWaylineTask = async function (
  workspaceId: string,
  plannedWaylineId: string,
  body: PreparePlannedWaylineTaskBody
): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/prepare`
  const result = await request.post(url, body)
  return normalizePlannedWaylineResult(result.data)
}

export const executePlannedWaylineTask = async function (
  workspaceId: string,
  plannedWaylineId: string,
  body?: PreparePlannedWaylineTaskBody
): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/execute`
  const result = await request.post(url, body || {})
  return normalizePlannedWaylineResult(result.data)
}

export const cancelPlannedWaylineTask = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/cancel`
  const result = await request.post(url)
  return normalizePlannedWaylineResult(result.data)
}

// L2 实时控制 (按 entity.dockSn 自动路由到 dock 或 agent path,前端无感知)
export const pausePlannedWaylineTask = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/pause`
  const result = await request.post(url)
  return normalizePlannedWaylineResult(result.data)
}

export const recoveryPlannedWaylineTask = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/recovery`
  const result = await request.post(url)
  return normalizePlannedWaylineResult(result.data)
}

export const stopPlannedWaylineTask = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/stop`
  const result = await request.post(url)
  return normalizePlannedWaylineResult(result.data)
}

export const queryPlannedWaylineBreakpoint = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/query-breakpoint`
  const result = await request.post(url)
  return normalizePlannedWaylineResult(result.data)
}

export interface CreatePlan {
  name: string,
  file_id: string,
  dock_sn: string,
  task_type: TaskType, // 任务类型
  wayline_type: WaylineType, // 航线类型
  task_days: number[] // 执行任务的日期（秒）
  task_periods: number[][] // 执行任务的时间点（秒）
  rth_altitude: number // 相对机场返航高度 20 - 500
  out_of_control_action: OutOfControlAction // 失控动作
  min_battery_capacity?: number, // The minimum battery capacity of aircraft.
  min_storage_capacity?: number, // The minimum storage capacity of dock and aircraft.
}

// Create Wayline Job
export const createPlan = async function (workspaceId: string, plan: CreatePlan): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/flight-tasks`
  const result = await request.post(url, plan)
  return result.data
}

export interface Task {
  job_id: string,
  job_name: string,
  task_type: TaskType, // 任务类型
  file_id: string, // 航线文件id
  file_name: string, // 航线名称
  wayline_type: WaylineType, // 航线类型
  dock_sn: string,
  dock_name: string,
  workspace_id: string,
  username: string,
  begin_time: string,
  end_time: string,
  execute_time: string,
  completed_time: string,
  status: TaskStatus, // 任务状态
  progress: number, // 执行进度
  code: number, // 错误码
  rth_altitude: number // 相对机场返航高度 20 - 500
  out_of_control_action: OutOfControlAction // 失控动作
  media_count: number // 媒体数量
  uploading:boolean // 是否正在上传媒体
  uploaded_count: number // 已上传媒体数量
}

// Get Wayline Jobs
export const getWaylineJobs = async function (workspaceId: string, page: IPage): Promise<IListWorkspaceResponse<Task>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/jobs?page=${page.page}&page_size=${page.page_size}`
  const result = await request.get(url)
  return result.data
}

export interface DeleteTaskParams {
  job_id: string
}

//  删除机场任务
export async function deleteTask (workspaceId: string, params: DeleteTaskParams): Promise<IWorkspaceResponse<{}>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/jobs`
  const result = await request.delete(url, {
    params: params
  })
  return result.data
}

export enum UpdateTaskStatus {
  Suspend = 0, // 暂停
  Resume = 1, // 恢复
}
export interface UpdateTaskStatusBody {
  job_id: string
  status: UpdateTaskStatus
}

// 更新机场任务状态
export async function updateTaskStatus (workspaceId: string, body: UpdateTaskStatusBody): Promise<IWorkspaceResponse<{}>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/jobs/${body.job_id}`
  const result = await request.put(url, {
    status: body.status
  })
  return result.data
}

// Upload Wayline file
export const importKmzFile = async function (workspaceId: string, file: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/waylines/file/upload`
  const result = await request.post(url, file, {
    headers: {
      'Content-Type': 'multipart/form-data',
    }
  })
  return result.data
}

// 媒体立即上传
export const uploadMediaFileNow = async function (workspaceId: string, jobId: string): Promise<IWorkspaceResponse<{}>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/jobs/${jobId}/media-highest`
  const result = await request.post(url)
  return result.data
}
