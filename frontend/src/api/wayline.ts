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
const DEFAULT_PLANNED_WAYLINE_MODEL = 'M30T'
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
  }
}

function validatePlannedWaylineBody<T extends CreatePlannedWaylineBody | UpdatePlannedWaylineBody> (body: T): T {
  if (!body?.name || !Array.isArray(body.waypoints) || body.waypoints.length === 0) {
    message.error('规划航线参数不完整，请确认航线名称和航点。')
    throw new Error('planned wayline payload incomplete')
  }
  return {
    ...body,
    aircraftModelKey: body.aircraftModelKey || DEFAULT_PLANNED_WAYLINE_MODEL,
    defaultHeight: positiveNumber(body.defaultHeight, DEFAULT_PLANNED_WAYLINE_HEIGHT),
    maxSpeed: positiveNumber(body.maxSpeed, DEFAULT_PLANNED_WAYLINE_SPEED),
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
  }
}

function normalizePlannedWaylineResponse (record: any): PlannedWaylineRecord {
  return {
    plannedWaylineId: record?.plannedWaylineId ?? record?.planned_wayline_id,
    workspaceId: record?.workspaceId ?? record?.workspace_id,
    name: record?.name,
    aircraftModelKey: record?.aircraftModelKey ?? record?.aircraft_model_key,
    gatewaySn: record?.gatewaySn ?? record?.gateway_sn,
    aircraftSn: record?.aircraftSn ?? record?.aircraft_sn,
    defaultHeight: record?.defaultHeight ?? record?.default_height,
    maxSpeed: record?.maxSpeed ?? record?.max_speed,
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

export const executePlannedWaylineTask = async function (workspaceId: string, plannedWaylineId: string): Promise<IWorkspaceResponse<PlannedWaylineRecord>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/planned-waylines/${plannedWaylineId}/execute`
  const result = await request.post(url)
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
