import client from './client'
import { operationIncidentMockApi } from './mock'
import type { ApiResult } from '/@/types/operation/api'
import type {
  AssignOperationResourceParam,
  CreateOperationIncidentParam,
  ListOperationIncidentsParams,
  OperationActionParam,
  OperationAssignmentDTO,
  OperationIncidentDTO,
  OperationIncidentDetailDTO,
  OperationTimelineItem,
} from '/@/types/operation/incident'

function mockSwitchValue () {
  const envValue = String(import.meta.env.VITE_OPERATION_MOCK || '').toLowerCase()
  if (['1', 'true', 'yes', 'on'].includes(envValue)) return true
  if (typeof localStorage === 'undefined') return false
  return ['1', 'true', 'yes', 'on'].includes(String(localStorage.getItem('uavfire_operation_mock') || '').toLowerCase())
}

export const useOperationMock = mockSwitchValue()

const realOperationIncidentApi = {
  create: (body: CreateOperationIncidentParam) =>
    client.post<ApiResult<OperationIncidentDTO>>('/api/operations/incidents', body),

  list: (params?: ListOperationIncidentsParams) =>
    client.get<ApiResult<OperationIncidentDTO[]>>('/api/operations/incidents', { params }),

  detail: (id: number) =>
    client.get<ApiResult<OperationIncidentDetailDTO>>(`/api/operations/incidents/${id}`),

  timeline: (id: number) =>
    client.get<ApiResult<OperationTimelineItem[]>>(`/api/operations/incidents/${id}/timeline`),

  assignMonitor: (id: number, body: AssignOperationResourceParam) =>
    client.post<ApiResult<OperationAssignmentDTO>>(`/api/operations/incidents/${id}/assign-monitor`, body),

  assignDelivery: (id: number, body: AssignOperationResourceParam) =>
    client.post<ApiResult<OperationAssignmentDTO>>(`/api/operations/incidents/${id}/assign-delivery`, body),

  dispatch: (id: number, body: OperationActionParam) =>
    client.post<ApiResult<OperationIncidentDTO>>(`/api/operations/incidents/${id}/dispatch`, body),

  abort: (id: number, body: OperationActionParam) =>
    client.post<ApiResult<OperationIncidentDTO>>(`/api/operations/incidents/${id}/abort`, body),

  close: (id: number, body: OperationActionParam) =>
    client.post<ApiResult<OperationIncidentDTO>>(`/api/operations/incidents/${id}/close`, body),

  markFalseAlarm: (id: number, body: OperationActionParam) =>
    client.post<ApiResult<OperationIncidentDTO>>(`/api/operations/incidents/${id}/mark-false-alarm`, body),
}

export const operationIncidentApi = useOperationMock
  ? operationIncidentMockApi
  : realOperationIncidentApi
