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

export interface FireEventActionBody {
  operatorId: string;
  reason?: string;
}

export interface FireEventDecisionResult {
  fireEvent: FireEventDTO;
  incident?: any;
  reusedIncident: boolean;
  draftMissionCreated: boolean;
  draftMissionNo?: string | null;
  recommendedRecheck: boolean;
  recheckReason?: string | null;
}

export interface FireEventRecheckResultBody {
  operatorId: string;
  maxTemp: number;
  hotAreaM2: number;
  flameVisible: boolean;
  suggestion: 'RESOLVED' | 'CONTINUE_RESPONSE' | string;
  remark?: string;
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

  confirm: (eventId: string | number, body: FireEventActionBody) =>
    client.post<ApiResult<FireEventDecisionResult>>(`/api/fire/events/${eventId}/confirm`, body),

  reject: (eventId: string | number, body: FireEventActionBody) =>
    client.post<ApiResult<FireEventDecisionResult>>(`/api/fire/events/${eventId}/reject`, body),

  recheckResult: (eventId: string | number, body: FireEventRecheckResultBody) =>
    client.post<ApiResult<any>>(`/api/fire/events/${eventId}/recheck-result`, body),
}
