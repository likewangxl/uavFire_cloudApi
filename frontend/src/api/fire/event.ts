import client from './client'
import type { ApiResult } from '/@/types/fire/api'
import type { FireEventDTO } from '/@/types/fire/event'

export interface FireEventCreateRequest {
  eventId: string;
  deviceSn: string;
  source: string;
  confidence: number;
  lat: number;
  lng: number;
}

export interface FireEventCreateResponse {
  missionNo: string;
  eventId: string;
}

export const eventApi = {
  list: (params?: { workspaceId?: string; page?: number; size?: number }) =>
    client.get<ApiResult<FireEventDTO[]>>('/api/fire/events', { params }),

  get: (eventId: string) =>
    client.get<ApiResult<FireEventDTO>>(`/api/fire/events/${eventId}`),

  createMock: (body: FireEventCreateRequest) =>
    client.post<ApiResult<FireEventCreateResponse>>('/api/fire/events', body),
}
