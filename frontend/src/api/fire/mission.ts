import client from './client'
import type { ApiResult } from '/@/types/fire/api'
import type { FireMissionDTO, MissionLogDTO } from '/@/types/fire/mission'

export interface MissionApproveBody {
  operatorId: string;
  aircraftSn?: string;
  payloadId?: string;
  waterLoadLiters?: number;
  takeoffLat: number;
  takeoffLng: number;
  takeoffAlt?: number;
  windSpeed: number;
  windDirectionDeg: number;
  remark?: string;
}

export const missionApi = {
  list: (params?: { workspaceId?: string; status?: string; page?: number; size?: number }) =>
    client.get<ApiResult<FireMissionDTO[]>>('/api/fire/missions', { params }),

  detail: (no: string) =>
    client.get<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}`),

  approve: (no: string, body: MissionApproveBody) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/approve`, body),

  reject: (no: string, body: { operatorId: string; reason: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/reject`, body),

  cancel: (no: string, body: { operatorId: string; reason?: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/cancel`, body),

  archive: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/archive`, body),

  forceFail: (no: string, body: { operatorId: string; reason?: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/force-fail`, body),

  takeover: (no: string, body: { operatorId: string; reason?: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/takeover`, body),

  resolveTakeover: (no: string, body: { operatorId: string; result: 'OK' | 'FAILED'; remark?: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/resolve-takeover`, body),

  markReturning: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/mark-returning`, body),

  markReturnCompleted: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/mark-return-completed`, body),

  markReturnFailed: (no: string, body: { operatorId: string; reason?: string }) =>
    client.post<ApiResult<FireMissionDTO>>(`/api/fire/missions/${no}/mark-return-failed`, body),

  logs: (no: string, limit = 200) =>
    client.get<ApiResult<MissionLogDTO[]>>(`/api/fire/missions/${no}/logs`, { params: { limit } }),
}
