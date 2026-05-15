import client from './client'
import type { ApiResult } from '/@/types/fire/api'

export interface DeliveryDeviceDTO {
  sn: string;
  name: string | null;
  status: string | null;
}

export interface DeliveryDeviceProperties {
  sn: string;
  batteryPercent: number | null;
  windSpeed: number | null;
  location: { lat: number; lng: number } | null;
}

export interface DeliveryTaskRef {
  taskId: string;
  status: string;
}

export interface DeliveryTaskStatus {
  taskId: string;
  status: string;
  phase: string | null;
  progressPercent: number | null;
  message: string | null;
  updateTime: number | null;
}

export const deliveryApi = {
  listDevices: (workspaceId?: string) =>
    client.get<ApiResult<DeliveryDeviceDTO[]>>('/api/fire/delivery/devices', {
      params: workspaceId ? { workspaceId } : undefined,
    }),

  deviceProps: (sn: string) =>
    client.get<ApiResult<DeliveryDeviceProperties>>(`/api/fire/delivery/devices/${sn}/properties`),

  createTask: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<DeliveryTaskRef>>(`/api/fire/missions/${no}/delivery/create-task`, body),

  startTask: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<null>>(`/api/fire/missions/${no}/delivery/start-task`, body),

  status: (no: string) =>
    client.get<ApiResult<DeliveryTaskStatus>>(`/api/fire/missions/${no}/delivery/status`),
}
