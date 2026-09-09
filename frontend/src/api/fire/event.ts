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
  list: (params?: { workspaceId?: string; status?: string; limit?: number; page?: number; size?: number }, silent = false) =>
    client.get<ApiResult<FireEventDTO[]>>('/api/fire/events', {
      ...(silent ? { suppressErrorToast: true } : {}),
      params: { workspaceId: params?.workspaceId, status: params?.status, limit: params?.limit ?? params?.size ?? 200 },
      // Spring request parameters remain camelCase even though JSON bodies use snake_case.
      paramsSerializer: values => new URLSearchParams(Object.entries(values).filter(([, value]) => value != null).map(([key, value]) => [key === 'workspace_id' ? 'workspaceId' : key, String(value)])).toString(),
    }),

  get: (eventId: string) =>
    client.get<ApiResult<FireEventDTO>>(`/api/fire/events/${encodeURIComponent(String(eventId))}`),

  history: (eventId: string, limit = 100) =>
    client.get<ApiResult<FireEventHistoryDTO[]>>(`/api/fire/events/${encodeURIComponent(String(eventId))}/history`, {
      params: { limit },
    }),

  createMock: (body: FireEventCreateRequest) =>
    client.post<ApiResult<FireEventCreateResponse>>('/api/fire/events', body),

  confirm: (eventId: string | number, body: FireEventActionBody) =>
    client.post<ApiResult<FireEventDecisionResult>>(`/api/fire/events/${encodeURIComponent(String(eventId))}/confirm`, body),

  reject: (eventId: string | number, body: FireEventActionBody) =>
    client.post<ApiResult<FireEventDecisionResult>>(`/api/fire/events/${encodeURIComponent(String(eventId))}/reject`, body),

  recheckResult: (eventId: string | number, body: FireEventRecheckResultBody) =>
    client.post<ApiResult<any>>(`/api/fire/events/${encodeURIComponent(String(eventId))}/recheck-result`, body),
}
