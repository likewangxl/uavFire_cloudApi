import { Firmware, FirmwareQueryParam, FirmwareUploadParam } from '/@/types/device-firmware'
import request, { CommonListResponse, IListWorkspaceResponse, IPage, IWorkspaceResponse } from '/@/api/http/request'
import { Device } from '/@/types/device'
import { normalizeDualStreamGroup } from '/@/api/dual-stream-normalizer.mjs'

const HTTP_PREFIX = '/manage/api/v1'

// login
export interface LoginBody {
 username: string,
 password: string,
 flag: number,
 captcha: string,
 captcha_token: string,
}
export interface BindBody {
  device_sn: string,
  user_id: string,
  workspace_id: string,
  domain?: string
}
export interface HmsQueryBody {
  sns: string[],
  children_sn: string,
  device_sn: string,
  language: string,
  level: number | string,
  begin_time: number,
  end_time: number,
  message: string,
  domain: number,
}

export const login = async function (body: LoginBody): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/login`
  const result = await request.post(url, body)
  return result.data
}

// Refresh Token
export const refreshToken = async function (body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/token/refresh`
  const result = await request.post(url, body)
  return result.data
}

// Get Platform Info
export const getPlatformInfo = async function (): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/current`
  const result = await request.get(url)
  return result.data
}

// Get User Info
export const getUserInfo = async function (): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/users/current`
  const result = await request.get(url)
  return result.data
}

// Get Device Topo
export const getDeviceTopo = async function (workspace_id: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${workspace_id}/devices`
  const result = await request.get(url)
  return result.data
}

// Get Livestream Capacity
export const getLiveCapacity = async function (body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/live/capacity`
  const result = await request.get(url, body)
  return result.data
}

// Start Livestream
export const startLivestream = async function (body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/live/streams/start`
  const result = await request.post(url, body)
  return result.data
}

// Stop Livestream
export const stopLivestream = async function (body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/live/streams/stop`
  const result = await request.post(url, body)
  return result.data
}
// Update Quality
export const setLivestreamQuality = async function (body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/live/streams/update`
  const result = await request.post(url, body)
  return result.data
}

export interface DualStreamGroup {
  droneSn: string
  connectionState?: string
  sessionState?: string
  liveStatus?: string
  currentMode?: string
  statusMessage?: string
  visibleState?: string
  thermalState?: string
  statusReason?: string
  playbackStatus?: string
  visiblePlayUrl?: string
  thermalPlayUrl?: string
  lastCommandAction?: string
  lastCommandStatus?: string
  visibleSupported?: boolean
  thermalSupported?: boolean
  thermalCenterTemperatureC?: number
  fireEventOutboxPendingCount?: number
  fireEventOutboxOldestPendingAt?: number
  fireEventOutboxLastError?: string
}

export interface DualStreamEvent {
  taskId?: string
  droneSn?: string
  sourceTs?: number
  visibleScore?: number
  thermalScore?: number
  fusionScore?: number
  riskLevel?: string
  analysisChannel?: string
  reviewStatus?: string
}

const pickEventField = (event: any, camelKey: string, snakeKey: string) => {
  if (event == null) return undefined
  return event[camelKey] ?? event[snakeKey]
}

const normalizeDualStreamEvent = (event: any): DualStreamEvent => ({
  taskId: pickEventField(event, 'taskId', 'task_id'),
  droneSn: pickEventField(event, 'droneSn', 'drone_sn'),
  sourceTs: pickEventField(event, 'sourceTs', 'source_ts'),
  visibleScore: pickEventField(event, 'visibleScore', 'visible_score'),
  thermalScore: pickEventField(event, 'thermalScore', 'thermal_score'),
  fusionScore: pickEventField(event, 'fusionScore', 'fusion_score'),
  riskLevel: pickEventField(event, 'riskLevel', 'risk_level'),
  analysisChannel: pickEventField(event, 'analysisChannel', 'analysis_channel'),
  reviewStatus: pickEventField(event, 'reviewStatus', 'review_status')
})

export const getDualStreamGroup = async function (droneSn: string): Promise<IWorkspaceResponse<DualStreamGroup>> {
  const url = `${HTTP_PREFIX}/dual-stream/groups/${droneSn}`
  const result = await request.get(url)
  return {
    ...result.data,
    data: normalizeDualStreamGroup(result.data?.data)
  }
}

export const requestDualStreamFocus = async function (
  droneSn: string,
  action: 'focus-visible' | 'focus-thermal'
): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/dual-stream/groups/${droneSn}/focus`
  const result = await request.post(url, { action })
  return result.data
}

export const requestDualStreamStart = async function (droneSn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/dual-stream/groups/${droneSn}/start`
  const result = await request.post(url)
  return result.data
}

// Cloud API 直播（路线 A）：让 Pilot 2 自己推 RTMP 到 ZLM
// video_id 格式：<deviceSn>/<cameraIndex>/<videoIndex>（来自 /live/capacity 返回的 cameras_list）
export const requestPilotLiveStart = async function (videoId: string): Promise<IWorkspaceResponse<{ url: string }>> {
  const url = `${HTTP_PREFIX}/live/streams/start`
  const result = await request.post(url, {
    url_type: 1,
    video_id: videoId,
    video_quality: 2,
    video_type: 'wide',
  })
  return result.data
}

export const getDualStreamTaskEvents = async function (taskId: string): Promise<IWorkspaceResponse<DualStreamEvent[]>> {
  const url = `${HTTP_PREFIX}/dual-stream/tasks/${taskId}/events`
  const result = await request.get(url)
  return {
    ...result.data,
    data: Array.isArray(result.data?.data)
      ? result.data.data.map(normalizeDualStreamEvent)
      : []
  }
}

// 手动触发火情识别 (#4): cockpit / 手动按钮调用
export const requestFireDetectionStart = async function (droneSn: string, videoId?: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/fire-detection/start`
  const body: Record<string, string> = { drone_sn: droneSn }
  if (videoId) body.video_id = videoId
  const result = await request.post(url, body)
  return result.data
}

export const requestFireDetectionStop = async function (droneSn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/fire-detection/stop`
  const result = await request.post(url, { drone_sn: droneSn })
  return result.data
}

// 查询某架飞机当前是否处于火情监测中（后端 activityTracker 真相源）。
// 用于驾驶舱按钮同步航线自动启停的状态。
export const getFireDetectionStatus = async function (droneSn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/fire-detection/status?drone_sn=${encodeURIComponent(droneSn)}`
  const result = await request.get(url)
  return result.data
}

export const getAllUsersInfo = async function (wid: string, body: IPage): Promise<CommonListResponse<any>> {
  const url = `${HTTP_PREFIX}/users/${wid}/users?&page=${body.page}&page_size=${body.page_size}`
  const result = await request.get(url)
  return result.data
}

export const updateUserInfo = async function (wid: string, user_id: string, body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/users/${wid}/users/${user_id}`
  const result = await request.put(url, body)
  return result.data
}

export const bindDevice = async function (body: BindBody): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${body.device_sn}/binding`
  const result = await request.post(url, body)
  return result.data
}

export const unbindDevice = async function (device_sn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${device_sn}/unbinding`
  const result = await request.delete(url)
  return result.data
}

export const getDeviceBySn = async function (workspace_id: string, device_sn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${workspace_id}/devices/${device_sn}`
  const result = await request.get(url)
  return result.data
}

/**
 * 获取绑定设备信息
 * @param workspace_id
 * @param body
 * @param domain
 * @returns
 */
export const getBindingDevices = async function (workspace_id: string, body: IPage, domain: number): Promise<IListWorkspaceResponse<Device>> {
  const url = `${HTTP_PREFIX}/devices/${workspace_id}/devices/bound?&page=${body.page}&page_size=${body.page_size}&domain=${domain}`
  const result = await request.get(url)
  return result.data
}

export const updateDevice = async function (body: {}, workspace_id: string, device_sn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${workspace_id}/devices/${device_sn}`
  const result = await request.put(url, body)
  return result.data
}

export const getUnreadDeviceHms = async function (workspace_id: string, device_sn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${workspace_id}/devices/hms/${device_sn}`
  const result = await request.get(url)
  return result.data
}

export const updateDeviceHms = async function (workspace_id: string, device_sn: string): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/devices/${workspace_id}/devices/hms/${device_sn}`
  const result = await request.put(url)
  return result.data
}

export const getDeviceHms = async function (body: HmsQueryBody, workspace_id: string, pagination: IPage): Promise<IListWorkspaceResponse<any>> {
  let url = `${HTTP_PREFIX}/devices/${workspace_id}/devices/hms?page=${pagination.page}&page_size=${pagination.page_size}` +
    `&level=${body.level ?? ''}&begin_time=${body.begin_time ?? ''}&end_time=${body.end_time ?? ''}&message=${body.message ?? ''}&language=${body.language}`
  body.sns.forEach((sn: string) => {
    if (sn !== '') {
      url = url.concat(`&device_sn=${sn}`)
    }
  })
  const result = await request.get(url)
  return result.data
}

export const changeLivestreamLens = async function (body: {}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/live/streams/switch`
  const result = await request.post(url, body)
  return result.data
}

export const getFirmwares = async function (workspace_id: string, page: IPage, body: FirmwareQueryParam): Promise<IListWorkspaceResponse<Firmware>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspace_id}/firmwares?page=${page.page}&page_size=${page.page_size}` +
    `&device_name=${body.device_name}&product_version=${body.product_version}&status=${body.firmware_status ?? ''}`
  const result = await request.get(url)
  return result.data
}

export const importFirmareFile = async function (workspaceId: string, param: FormData): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/firmwares/file/upload`
  const result = await request.post(url, param)
  return result.data
}

export const changeFirmareStatus = async function (workspaceId: string, firmwareId: string, param: {status: boolean}): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/workspaces/${workspaceId}/firmwares/${firmwareId}`
  const result = await request.put(url, param)
  return result.data
}
