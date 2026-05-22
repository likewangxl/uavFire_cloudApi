import request, { IWorkspaceResponse } from '/@/api/http/request'

const HTTP_PREFIX = '/manage/api/v1'

export interface MsdkDeviceState {
  gatewaySn: string
  aircraftSn: string
  online: boolean
  connectionState: string
  model?: string
  mode?: string
  latitude?: number
  longitude?: number
  height?: number
  elevation?: number
  homeDistance?: number
  horizontalSpeed?: number
  verticalSpeed?: number
  windSpeed?: number
  batteryPercent?: number
  gpsCount?: number
  rtkCount?: number
  positionFixed?: boolean
  updatedAt?: number
  capabilities?: Record<string, boolean>
}

const pick = (source: any, camelKey: string, snakeKey: string) => {
  if (!source || typeof source !== 'object') {
    return undefined
  }
  return source[camelKey] !== undefined ? source[camelKey] : source[snakeKey]
}

export function normalizeMsdkDeviceState (device: any): MsdkDeviceState {
  return {
    gatewaySn: pick(device, 'gatewaySn', 'gateway_sn') || '',
    aircraftSn: pick(device, 'aircraftSn', 'aircraft_sn') || '',
    online: Boolean(pick(device, 'online', 'online')),
    connectionState: pick(device, 'connectionState', 'connection_state') || '',
    model: pick(device, 'model', 'model'),
    mode: pick(device, 'mode', 'mode'),
    latitude: pick(device, 'latitude', 'latitude'),
    longitude: pick(device, 'longitude', 'longitude'),
    height: pick(device, 'height', 'height'),
    elevation: pick(device, 'elevation', 'elevation'),
    homeDistance: pick(device, 'homeDistance', 'home_distance'),
    horizontalSpeed: pick(device, 'horizontalSpeed', 'horizontal_speed'),
    verticalSpeed: pick(device, 'verticalSpeed', 'vertical_speed'),
    windSpeed: pick(device, 'windSpeed', 'wind_speed'),
    batteryPercent: pick(device, 'batteryPercent', 'battery_percent'),
    gpsCount: pick(device, 'gpsCount', 'gps_count'),
    rtkCount: pick(device, 'rtkCount', 'rtk_count'),
    positionFixed: pick(device, 'positionFixed', 'position_fixed'),
    updatedAt: pick(device, 'updatedAt', 'updated_at'),
    capabilities: pick(device, 'capabilities', 'capabilities') || {},
  }
}

export async function listMsdkDevices (): Promise<IWorkspaceResponse<MsdkDeviceState[]>> {
  const result = await request.get(`${HTTP_PREFIX}/msdk/devices`)
  const response = result.data
  return {
    ...response,
    data: Array.isArray(response?.data)
      ? response.data.map(normalizeMsdkDeviceState)
      : [],
  }
}

export async function sendMsdkCommand (
  aircraftSn: string,
  command: string,
  params: Record<string, unknown> = {}
): Promise<IWorkspaceResponse<any>> {
  const result = await request.post(`${HTTP_PREFIX}/msdk/devices/${aircraftSn}/commands`, {
    command,
    params
  })
  return result.data
}
