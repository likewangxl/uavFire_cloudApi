import request, { IWorkspaceResponse } from '/@/api/http/request'

const HTTP_PREFIX = '/manage/api/v1'

export interface MsdkDeviceState {
  gatewaySn: string
  aircraftSn: string
  online: boolean
  connectionState: string
  deviceName?: string
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
  aircraftModelKey?: string
  controllerModelKey?: string
  payloads?: Array<{
    payloadModelKey: string
    payloadPositionIndex: number
    visibleSupported?: boolean
    thermalSupported?: boolean
    laserSupported?: boolean
    tapZoomSupported?: boolean
  }>
  selectedPayloadPositionIndex?: number
  laserSupported?: boolean
  fireClosedLoopReady?: boolean
  blockingReasons?: string[]
}

const pick = (source: any, camelKey: string, snakeKey: string) => {
  if (!source || typeof source !== 'object') {
    return undefined
  }
  return source[camelKey] !== undefined ? source[camelKey] : source[snakeKey]
}

const pickText = (source: any, keys: string[]) => {
  if (!source || typeof source !== 'object') {
    return undefined
  }
  for (const key of keys) {
    const value = source[key]
    if (typeof value === 'string' && value.trim()) {
      return value.trim()
    }
  }
  return undefined
}

export function normalizeMsdkDeviceState (device: any): MsdkDeviceState {
  return {
    gatewaySn: pick(device, 'gatewaySn', 'gateway_sn') || '',
    aircraftSn: pick(device, 'aircraftSn', 'aircraft_sn') || '',
    online: Boolean(pick(device, 'online', 'online')),
    connectionState: pick(device, 'connectionState', 'connection_state') || '',
    deviceName: pickText(device, ['deviceName', 'device_name', 'productName', 'product_name', 'name']),
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
    aircraftModelKey: pick(device, 'aircraftModelKey', 'aircraft_model_key'),
    controllerModelKey: pick(device, 'controllerModelKey', 'controller_model_key'),
    payloads: pick(device, 'payloads', 'payloads') || [],
    selectedPayloadPositionIndex: pick(device, 'selectedPayloadPositionIndex', 'selected_payload_position_index'),
    laserSupported: Boolean(pick(device, 'laserSupported', 'laser_supported')),
    fireClosedLoopReady: Boolean(pick(device, 'fireClosedLoopReady', 'fire_closed_loop_ready')),
    blockingReasons: pick(device, 'blockingReasons', 'blocking_reasons') || [],
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

export async function getMsdkCommand (
  aircraftSn: string,
  commandId: string
): Promise<IWorkspaceResponse<any>> {
  const result = await request.get(`${HTTP_PREFIX}/msdk/devices/${aircraftSn}/commands/${commandId}`)
  return result.data
}
