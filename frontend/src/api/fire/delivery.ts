import client from './client'
import type { ApiResult } from '/@/types/fire/api'

export interface DeliveryDeviceDTO {
  deviceSn: string;
  deviceType: string | null;
  online: string | null;
  bindStatus: string | null;
}

export interface DeliveryDeviceProperties {
  deviceSn: string;
  onlineStatus: boolean | null;
  batteryPercent: number | null;
  rtkStatus: string | null;
  latitude: number | null;
  longitude: number | null;
  altitude: number | null;
  aircraftMode: number | null;
  flying: boolean | null;
  horizontalSpeed: number | null;
  verticalSpeed: number | null;
  homeDistance: number | null;
  windSpeed: number | null;
  osdTimestamp: number | null;
}

export interface DeliveryTaskRef {
  taskId: string;
  status: string;
  accepted?: boolean | null;
  apiCode?: number | null;
  apiMessage?: string | null;
  displayMessage?: string | null;
  deviceSn?: string | null;
  missionId?: string | null;
  taskName?: string | null;
  reason?: string | null;
  updateTime?: number | null;
}

export interface DeliveryTaskStatus {
  taskId: string;
  status: string;
  phase: string | null;
  progressPercent: number | null;
  message: string | null;
  updateTime: number | null;
  accepted?: boolean | null;
  apiCode?: number | null;
  apiMessage?: string | null;
  displayMessage?: string | null;
  deviceSn?: string | null;
  missionId?: string | null;
  taskName?: string | null;
  reason?: string | null;
  taskCode?: number | null;
  startTime?: number | null;
  endTime?: number | null;
  estimateTime?: number | null;
}

export interface DeliveryTaskOperationResult {
  operation: string;
  accepted: boolean | null;
  apiCode: number | null;
  apiMessage: string | null;
  displayMessage: string | null;
  taskId: string | null;
  status: string | null;
  deviceSn: string | null;
  missionId: string | null;
  taskName: string | null;
  reason: string | null;
  updateTime: number | null;
}

export interface DeliveryWaylineDTO {
  waylineId: string;
  waylineType: string | null;
  name: string | null;
  distance: number | null;
  duration: number | null;
  flyToWaylineMode: string | null;
  finishAction: string | null;
  rcLostAction: string | null;
  turnMode: string | null;
  fingerprint: string | null;
  createTime: number | null;
  updateTime: number | null;
}

export interface DeliveryCommandRef {
  code?: number | null;
  bid?: string | null;
  gatewaySn?: string | null;
  deviceSn?: string | null;
  deviceCmdMethod?: string | null;
  status?: string | null;
  deviceCmdData?: Record<string, unknown> | null;
  createTime?: number | null;
  updateTime?: number | null;
}

export interface DeliveryCommandStatus {
  deviceSn: string;
  services?: Record<string, unknown> | null;
}

export interface DeliveryCreateTaskBody {
  operatorId: string;
  taskName?: string;
  remark?: string;
  notifies?: string[];
}

export interface DeliveryCommandBody {
  operatorId: string;
  data?: Record<string, unknown>;
}

export interface DeliveryCreateWaylineTaskBody {
  deviceSn: string;
  waylineId: string;
  taskName?: string;
  operatorId?: string;
  remark?: string;
}

export const deliveryApi = {
  listDevices: (workspaceId?: string) =>
    client.get<ApiResult<DeliveryDeviceDTO[]>>('/api/fire/delivery/devices', {
      params: workspaceId ? { workspaceId } : undefined,
    }),

  deviceProps: (sn: string) =>
    client.get<ApiResult<DeliveryDeviceProperties>>(`/api/fire/delivery/devices/${sn}/properties`),

  createTask: (no: string, body: DeliveryCreateTaskBody) =>
    client.post<ApiResult<DeliveryTaskRef>>(`/api/fire/missions/${no}/delivery/create-task`, body),

  startTask: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<DeliveryTaskOperationResult>>(`/api/fire/missions/${no}/delivery/start-task`, body),

  status: (no: string) =>
    client.get<ApiResult<DeliveryTaskStatus>>(`/api/fire/missions/${no}/delivery/status`),

  emergencyStop: (no: string, body: DeliveryCommandBody) =>
    client.post<ApiResult<DeliveryCommandRef>>(`/api/fire/missions/${no}/delivery/emergency-stop`, body),

  returnHome: (no: string, body: DeliveryCommandBody) =>
    client.post<ApiResult<DeliveryCommandRef>>(`/api/fire/missions/${no}/delivery/return-home`, body),

  land: (no: string, body: DeliveryCommandBody) =>
    client.post<ApiResult<DeliveryCommandRef>>(`/api/fire/missions/${no}/delivery/land`, body),

  commandStatus: (no: string) =>
    client.get<ApiResult<DeliveryCommandStatus>>(`/api/fire/missions/${no}/delivery/commands/status`),

  importCreateWaylineTask: (body: FormData) =>
    client.post<ApiResult<DeliveryTaskRef>>('/api/fire/delivery/wayline-tasks/import-create', body),

  listWaylines: (page?: number, pageSize?: number, key?: string) =>
    client.get<ApiResult<DeliveryWaylineDTO[]>>('/api/fire/delivery/waylines', {
      params: { page: page || 1, pageSize: pageSize || 20, ...(key ? { key } : {}) },
    }),

  createWaylineTask: (body: DeliveryCreateWaylineTaskBody) =>
    client.post<ApiResult<DeliveryTaskRef>>('/api/fire/delivery/wayline-tasks/create-from-wayline', body),

  startWaylineTask: (taskId: string, deviceSn?: string) =>
    client.post<ApiResult<DeliveryTaskOperationResult>>(`/api/fire/delivery/wayline-tasks/${taskId}/start`, {}, {
      params: deviceSn ? { deviceSn } : undefined,
    }),

  waylineTaskStatus: (taskId: string) =>
    client.get<ApiResult<DeliveryTaskStatus>>(`/api/fire/delivery/wayline-tasks/${taskId}/status`),

  sendDeviceCommand: (deviceSn: string, method: string, body: DeliveryCommandBody) =>
    client.post<ApiResult<DeliveryCommandRef>>(`/api/fire/delivery/devices/${deviceSn}/commands/${method}`, body),

  deviceCommandStatus: (deviceSn: string) =>
    client.get<ApiResult<DeliveryCommandStatus>>(`/api/fire/delivery/devices/${deviceSn}/commands/status`),
}
