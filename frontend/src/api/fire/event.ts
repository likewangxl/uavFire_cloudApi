import client from './client'
import type { ApiResult } from '/@/types/fire/api'
import type { FireEventDTO, FireEventHistoryDTO } from '/@/types/fire/event'

export interface FireEventCreateRequest {
  eventId: string;
  deviceSn: string;
  source: string;
  confidence: number;
  lat: number;
  lng: number;
}

export interface FireEventCreateResponse {
  fireEventId: number;
  missionNo: string | null;
  eventId: string;
  missionCreated: boolean;
  status: string;
  created: boolean;
  merged: boolean;
  notificationRequired: boolean;
  notificationReason: string | null;
}

export const eventApi = {
  list: (params?: { workspaceId?: string; page?: number; size?: number }) =>
    client.get<ApiResult<FireEventDTO[]>>('/api/fire/events', { params }),

  get: (eventId: string) =>
    client.get<ApiResult<FireEventDTO>>(`/api/fire/events/${eventId}`),

  history: (eventId: string, limit = 100) =>
    client.get<ApiResult<FireEventHistoryDTO[]>>(`/api/fire/events/${eventId}/history`, {
      params: { limit },
    }),

  createMock: (body: FireEventCreateRequest) =>
    client.post<ApiResult<FireEventCreateResponse>>('/api/fire/events', body),
}
