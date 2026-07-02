import client from './client'
import type { ApiResult } from '/@/types/fire/api'

export const payloadApi = {
  markPending: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<null>>(`/api/fire/missions/${no}/payload/mark-pending`, body),

  confirmRelease: (no: string, body: {
    operatorId: string;
    confirmedArrival: boolean;
    confirmedNoPeopleRisk: boolean;
    confirmedWindOk: boolean;
    confirmedPayloadReady: boolean;
    confirmedRelease: boolean;
    confirmationToken: string;
    remoteHookRemark?: string;
    checklistTimestamps: Record<string, number>;
  }) =>
    client.post<ApiResult<null>>(`/api/fire/missions/${no}/payload/confirm-release`, body),

  markFailed: (no: string, body: { operatorId: string; reason?: string }) =>
    client.post<ApiResult<null>>(`/api/fire/missions/${no}/payload/mark-failed`, body),

  retryRelease: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<null>>(`/api/fire/missions/${no}/payload/retry-release`, body),
}
